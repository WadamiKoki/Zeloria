package com.zeloria.mob;

import com.zeloria.core.MessageService;
import com.zeloria.core.Zeloria;
import com.zeloria.world.CollisionManager;
import com.zeloria.world.LayerConfigService;
import com.zeloria.world.LayerData;
import com.zeloria.world.SpawnerDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code LayerData} の {@code spawners} セクションに定義されたスポーン設定から
 * モブを定期的にスポーンさせる。
 * <p>
 * スポナー定義は {@code config/LayerData/*.yml} に直接記述し（{@link SpawnerDefinition}）、
 * 旧 {@code config/SpawnerData/*.yml} は使用しない。表示エンティティ
 * （ItemDisplay / BlockDisplay）は生成せず、位置確認は {@code /admin spawner mode}
 * のパーティクル表示で行う。
 * <p>
 * {@code LayerManager} がレイヤーワールドをロードしたタイミングで
 * {@link #startFor(World, String, String)}、アンロード・削除するタイミングで
 * {@link #stopFor(String)} を呼ぶ。
 * <p>
 * 負荷対策として、判定は 1 本の繰り返しタスク（{@value #TICK_INTERVAL} tick 間隔）だけで行い、
 * スポナーごとの {@code spawnRateSeconds} に達したものだけを処理する。さらに
 * プレイヤーが 1 人もいないワールドは判定ごとスキップし、非アクティブなレイヤーの
 * モブは {@link LivingEntity#setAware(boolean)} で AI を停止する。
 * <p>
 * 生成されたモブは {@code /admin mob spawn} と同一の PDC（{@code zeloria:mob_id}）と
 * スコアボードタグ（{@code zeloria_mob:<mobId>}）を持ち、カスタムダメージ計算の対象となる。
 * スポーン時に抽選したレベルは {@code zeloria:mob_level} として PDC に保存し、
 * {@link MobStatsService} が {@link MobData#baseLevel()} との差でステータスを補正する。
 */
public final class SpawnerManager implements Listener {

    /** モブを識別する PDC キー（{@code AdminCommand} / {@link MobStatsService} と同一）。 */
    public static final String MOB_ID_KEY = MobStatsService.MOB_ID_KEY;

    /** スポーン時に決定したモブレベルを保持する PDC キー。 */
    public static final String MOB_LEVEL_KEY = MobStatsService.MOB_LEVEL_KEY;

    /** スポーン済みモブへ付与するスコアボードタグの接頭辞（{@code AdminCommand} と同一）。 */
    public static final String MOB_TAG_PREFIX = "zeloria_mob:";

    /** 判定タスクの実行間隔（tick）。 */
    private static final int TICK_INTERVAL = 20;

    /** デバッグ用パーティクル表示の実行間隔（tick）。 */
    private static final int DEBUG_INTERVAL_TICKS = 20;

    /** デバッグ用パーティクルを表示する管理者からの距離（ブロック）。 */
    private static final double DEBUG_PARTICLE_RADIUS = 64.0;

    /** デバッグ用パーティクルの表示数。 */
    private static final int DEBUG_PARTICLE_COUNT = 2;

    /** デバッグ用パーティクルをスポナー座標から上へずらす量（ブロック）。 */
    private static final double DEBUG_PARTICLE_Y_OFFSET = 1.0;

    /** 奈落と判定する Y 座標。これ以下へ落ちたモブは消滅させる。 */
    private static final double VOID_DESPAWN_Y = -65.0;

    /** 水平ズレ座標の再抽選回数。全滅した場合はその周期のスポーンを見送る。 */
    private static final int SPAWN_ATTEMPTS = 10;

    /** 複数スポナーの初回判定をずらす最大 tick 数。 */
    private static final int MAX_STAGGER_TICKS = 200;

    private static final String DEBUG_PREFIX = "[SpawnerDebug] ";

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;
    private final NamespacedKey mobLevelKey;

    /** インスタンス ID → 稼働中のスポナー群。 */
    private final Map<String, ActiveLayer> active = new ConcurrentHashMap<>();

    /** 警告済みのモブ ID（存在しない定義でログが溢れるのを防ぐ）。 */
    private final Set<String> warnedMobs = ConcurrentHashMap.newKeySet();

    /** デバッグ表示モードが ON の管理者 UUID → 有効フラグ。 */
    private final Map<UUID, Boolean> debugPlayers = new ConcurrentHashMap<>();

    private BukkitTask task;
    private BukkitTask debugTask;
    private long tickCounter;

    public SpawnerManager(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
        this.mobLevelKey = new NamespacedKey(plugin, MOB_LEVEL_KEY);
    }

    /**
     * 指定インスタンスのスポーン判定を開始する。
     * <p>
     * 既に開始済みのインスタンスは一旦停止して置き換える。
     * レイヤーに {@code spawners} が定義されていない場合は何もしない。
     *
     * @param world      対象ワールド（インスタンス）
     * @param instanceId インスタンス ID
     * @param templateId テンプレート ID（{@code LayerData} の解決に使用）
     * @return 登録したスポナー数
     */
    public int startFor(World world, String instanceId, String templateId) {
        if (world == null || instanceId == null || instanceId.isBlank()) {
            return 0;
        }

        stopFor(instanceId);

        LayerConfigService layerConfigService = plugin.getLayerConfigService();
        if (layerConfigService == null) {
            debug("LayerConfigService が未初期化のためスポナーを開始できません: instance=" + instanceId);
            return 0;
        }

        // ワールド名から LayerData を解決する。LayerManager の対応表が未登録でも、
        // テンプレート ID が渡されていればそこから解決できる。
        Optional<LayerData> found = layerConfigService.findByWorldName(world.getName());
        if (found.isEmpty() && templateId != null && !templateId.isBlank()) {
            found = layerConfigService.findByTemplate(templateId);
        }
        if (found.isEmpty()) {
            debug("レイヤー定義が見つかりません: instance=" + instanceId + ", template=" + templateId);
            return 0;
        }

        List<SpawnerDefinition> spawners = found.get().getSpawners();
        if (spawners.isEmpty()) {
            return 0;
        }

        debug("レイヤーのスポナーを開始します: instance=" + instanceId
                + ", world=" + world.getName() + ", spawners=" + spawners.size());

        int started = 0;
        for (SpawnerDefinition definition : spawners) {
            if (registerSpawner(world, instanceId, definition)) {
                started++;
            }
        }
        return started;
    }

    /**
     * 1 スポナー分の稼働登録を行う。
     * <p>
     * 表示エンティティは生成しない（見えないスポナー方針）。
     *
     * @param world      対象ワールド
     * @param instanceId インスタンス ID
     * @param definition レイヤー側のスポーン定義
     * @return 登録した場合 {@code true}
     */
    private boolean registerSpawner(World world, String instanceId, SpawnerDefinition definition) {
        if (definition == null || !definition.isValid()) {
            return false;
        }

        String instanceKey = key(instanceId);
        ActiveLayer layer = active.computeIfAbsent(instanceKey,
                ignored -> new ActiveLayer(world, !world.getPlayers().isEmpty()));

        if (layer.contains(definition.mobId(), definition.x(), definition.y(), definition.z())) {
            debug("同一座標のスポナーは登録済みのためスキップします: instance=" + instanceId
                    + ", spawner=" + definition.displayId());
            return false;
        }

        Location location = new Location(world, definition.x(), definition.y(), definition.z());
        long offset = (long) (layer.spawners.size() * TICK_INTERVAL) % MAX_STAGGER_TICKS;
        layer.spawners.add(new ActiveSpawner(definition, location, tickCounter + offset));
        ensureTask();

        debug("スポナーを登録しました: instance=" + instanceId
                + ", spawner=" + definition.displayId()
                + ", level=" + definition.minLevel() + "-" + definition.maxLevel()
                + ", rate=" + definition.spawnRateSeconds() + "s"
                + ", maxAlive=" + definition.maxAlive()
                + ", radius=" + definition.spawnRadius()
                + ", range=" + definition.spawnRange());
        return true;
    }

    /**
     * 指定インスタンスのスポーン判定を停止する。
     *
     * @param instanceId インスタンス ID
     */
    public void stopFor(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        ActiveLayer layer = active.remove(key(instanceId));
        if (layer != null) {
            debug("スポナーを停止しました: instance=" + instanceId + ", world=" + layer.world.getName());
        }
        stopTaskIfIdle();
    }

    /**
     * 設定再読み込み時に、稼働中インスタンスのスポナーを最新の定義で再登録する。
     * <p>
     * {@code LayerData} は {@code LayerConfigService} が保持しているため、
     * ここでは稼働状態を組み直すだけでよい。
     */
    public void reload() {
        debug("スポナー設定を再読み込みします");

        active.clear();
        warnedMobs.clear();

        var layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            stopTaskIfIdle();
            return;
        }

        for (Map.Entry<String, World> entry : layerManager.getLoadedWorlds().entrySet()) {
            String instanceId = entry.getKey();
            String templateId = layerManager.getTemplateId(instanceId);
            startFor(entry.getValue(), instanceId, templateId);
        }

        stopTaskIfIdle();
    }

    /**
     * すべてのスポーン判定・デバッグ表示を停止する（プラグイン停止時用）。
     */
    public void shutdown() {
        active.clear();
        warnedMobs.clear();
        debugPlayers.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (debugTask != null) {
            debugTask.cancel();
            debugTask = null;
        }
    }

    /**
     * 稼働中のインスタンス数。
     */
    public int getActiveCount() {
        return active.size();
    }

    /**
     * スポナー位置のデバッグ表示モードを切り替える。
     * <p>
     * ON の間は {@link #renderDebugParticles()} が毎秒、管理者から 64 ブロック以内の
     * スポナーへパーティクルを送信する。
     *
     * @param player 対象の管理者
     * @return 切り替え後の状態（{@code true} で ON）
     */
    public boolean toggleDebugMode(Player player) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        if (isDebugMode(playerId)) {
            debugPlayers.remove(playerId);
            stopDebugTaskIfIdle();
            return false;
        }

        debugPlayers.put(playerId, Boolean.TRUE);
        ensureDebugTask();
        return true;
    }

    /**
     * 指定プレイヤーのデバッグ表示モードが ON か判定する。
     */
    public boolean isDebugMode(UUID playerId) {
        return playerId != null && debugPlayers.containsKey(playerId);
    }

    /**
     * デバッグ表示モードを解除する。
     *
     * @param playerId 対象プレイヤー
     */
    public void clearDebugMode(UUID playerId) {
        if (playerId == null) {
            return;
        }
        if (debugPlayers.remove(playerId) != null) {
            stopDebugTaskIfIdle();
        }
    }

    /**
     * 退出したプレイヤーのデバッグ表示モードを破棄する。
     * <p>
     * ログアウト後にモード状態が残らないよう、{@link PlayerQuitEvent} で解除する。
     *
     * @param event 退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearDebugMode(event.getPlayer().getUniqueId());
    }

    private void ensureDebugTask() {
        if (debugTask != null && !debugTask.isCancelled()) {
            return;
        }
        debugTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::renderDebugParticles, DEBUG_INTERVAL_TICKS, DEBUG_INTERVAL_TICKS);
    }

    private void stopDebugTaskIfIdle() {
        if (!debugPlayers.isEmpty()) {
            return;
        }
        if (debugTask != null) {
            debugTask.cancel();
            debugTask = null;
        }
    }
    /**
     * デバッグ表示モードが ON の管理者へスポナー位置のパーティクルを送信する。
     * <p>
     * 現在のワールドの {@code LayerData} から {@code spawners} を取得し、
     * 管理者から {@value #DEBUG_PARTICLE_RADIUS} ブロック以内（二乗距離で判定）の
     * スポナー座標（Y + 1）へ {@link Particle#HAPPY_VILLAGER} を表示する。
     * {@link Player#spawnParticle(Particle, Location, int, double, double, double, double)}
     * はそのプレイヤーのクライアントにのみパケットを送るため、他のプレイヤーには見えない。
     */
    private void renderDebugParticles() {
        if (debugPlayers.isEmpty()) {
            stopDebugTaskIfIdle();
            return;
        }

        LayerConfigService layerConfigService = plugin.getLayerConfigService();
        if (layerConfigService == null) {
            return;
        }

        double maxDistanceSquared = DEBUG_PARTICLE_RADIUS * DEBUG_PARTICLE_RADIUS;

        for (UUID playerId : new ArrayList<>(debugPlayers.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                debugPlayers.remove(playerId);
                continue;
            }

            // ワールドを移動した場合は、移動先のレイヤーデータを対象にする。
            Optional<LayerData> layer = layerConfigService.findByWorldName(player.getWorld().getName());
            if (layer.isEmpty() || layer.get().getSpawners().isEmpty()) {
                continue;
            }

            Location playerLocation = player.getLocation();
            for (SpawnerDefinition definition : layer.get().getSpawners()) {
                Location location = new Location(player.getWorld(),
                        definition.x(), definition.y() + DEBUG_PARTICLE_Y_OFFSET, definition.z());
                // sqrt を避けるため二乗距離で 64 ブロック以内か判定する。
                if (playerLocation.distanceSquared(location) > maxDistanceSquared) {
                    continue;
                }
                player.spawnParticle(Particle.HAPPY_VILLAGER, location,
                        DEBUG_PARTICLE_COUNT, 0.3, 0.3, 0.3, 0.0);
            }
        }

        stopDebugTaskIfIdle();
    }
    /**
     * 1 スポナー分の判定を行い、条件を満たせばモブをスポーンさせる。
     * <p>
     * デバッグログはスポーン成功時とスポーン地点を確保できなかった時のみ出力する。
     */
    private void runSpawner(World world, ActiveSpawner spawner) {
        SpawnerDefinition definition = spawner.definition;
        Location center = spawner.location;

        if (!hasNearbyPlayer(world, center, definition.spawnRadius())) {
            return;
        }

        MobConfigService mobConfigService = plugin.getMobConfigService();
        Optional<MobData> found = mobConfigService == null
                ? Optional.empty()
                : mobConfigService.find(definition.mobId());
        if (found.isEmpty()) {
            warnOnce(definition.mobId(), "スポナーが参照する MobData が見つかりません: mob=" + definition.mobId());
            return;
        }

        MobData mob = found.get();
        EntityType entityType = mob.spawnableEntityType();
        // バニラの isSpawnable() は自然湧き／スポーンエッグ可否に依存するため、
        // カスタムモブが弾かれることがある。LivingEntity として生成できるかだけを確認する。
        Class<? extends Entity> entityClass = entityType.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            warnOnce(definition.mobId(), "スポーンできないエンティティ種別です: mob=" + mob.id()
                    + ", entity=" + entityType.getKey());
            return;
        }

        int alive = countAlive(world, center, mob, definition.spawnRadius());
        if (alive >= definition.maxAlive()) {
            return;
        }

        int allowed = Math.min(definition.spawnLimit(), definition.maxAlive() - alive);
        for (int i = 0; i < allowed; i++) {
            Location location = rollSpawnLocation(world, definition);
            if (location == null) {
                debug("スポーン地点を確保できませんでした: spawner=" + definition.displayId());
                return;
            }
            int level = definition.randomLevel();
            spawn(world, location, mob, level, entityType);
            plugin.getLogger().info("[Spawner] " + definition.displayId()
                    + " スポーン成功 loc=" + formatLocation(location) + " level=" + level);
        }
    }
    /**
     * スポーン座標を決定する。
     * <p>
     * Y はスポナーの {@code y} に固定し、X / Z のみを {@code spawnRange} の範囲で
     * ランダムにずらす（足場が無くても空中にスポーンさせる方針のため、
     * ブロックの有無は判定しない）。ズレ幅は計算後に必ず
     * {@code spawner ± spawnRange} へクランプし、未ロードチャンク・ワールド外は
     * 再抽選する。全候補が使用不可の場合は {@code null} を返し、その周期は見送る。
     *
     * @param world      対象ワールド
     * @param definition スポーン定義
     * @return スポーン座標。確保できなかった場合は {@code null}
     */
    private Location rollSpawnLocation(World world, SpawnerDefinition definition) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double baseX = definition.x();
        double baseZ = definition.z();
        double range = definition.spawnRange();

        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double offsetX = range <= 0.0 ? 0.0 : random.nextDouble(-range, range);
            double offsetZ = range <= 0.0 ? 0.0 : random.nextDouble(-range, range);
            double x = clamp(baseX + offsetX, baseX - range, baseX + range);
            double z = clamp(baseZ + offsetZ, baseZ - range, baseZ + range);

            Location candidate = new Location(world, x, definition.y(), z,
                    random.nextFloat() * 360.0f, 0.0f);
            if (!isSpawnable(candidate)) {
                continue;
            }
            // 未ロードチャンクへのスポーンは行わない（同期ロードによるラグを避ける）。
            if (!world.isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                continue;
            }
            return candidate;
        }

        return null;
    }

    /**
     * 座標がスポーン可能な範囲か判定する（ワールド境界・ワールドボーダー）。
     */
    private boolean isSpawnable(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        int blockY = location.getBlockY();
        if (blockY <= world.getMinHeight() || blockY >= world.getMaxHeight() - 1) {
            return false;
        }

        // ワールド外（ワールドボーダー外）へはスポーンさせない。
        return world.getWorldBorder().isInside(location);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    /**
     * モブをスポーンさせ、定義とレベルを反映する。
     */
    private void spawn(World world, Location location, MobData data, int level, EntityType entityType) {
        // spawnEntity の直後に設定すると Paper 側の再適用に上書きされることがあるため、
        // 生成コールバック内で PDC・表示名・最大 HP を設定する。
        world.spawnEntity(location, entityType, CreatureSpawnEvent.SpawnReason.SPAWNER,
                created -> applyMobData(created, data, level));
    }

    /**
     * スポーンしたエンティティへ {@link MobData} とスポーンレベルを反映する。
     * <p>
     * 表示名（MiniMessage）・最大 HP・スコアボードタグ・PDC のモブ ID とレベルを設定する。
     * 最大 HP は {@link MobStats#levelMultiplier(int, int)} で補正した値を使用する。
     */
    private void applyMobData(Entity entity, MobData data, int level) {
        int normalizedLevel = Math.max(1, level);
        entity.customName(displayName(data.name(), normalizedLevel));
        entity.setCustomNameVisible(data.alwaysShowName());
        entity.addScoreboardTag(MOB_TAG_PREFIX + data.id());
        entity.getPersistentDataContainer().set(mobIdKey, PersistentDataType.STRING, data.id());
        entity.getPersistentDataContainer().set(mobLevelKey, PersistentDataType.INTEGER, normalizedLevel);

        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        MobStatsService mobStatsService = plugin.getMobStatsService();
        if (mobStatsService != null) {
            mobStatsService.attach(living, data, normalizedLevel);
        }

        if (data.maxHp() <= 0.0) {
            return;
        }

        AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double scaled = Math.max(1.0,
                data.maxHp() * MobStats.levelMultiplier(data.baseLevel(), normalizedLevel));
        maxHealth.setBaseValue(scaled);
        living.setHealth(Math.min(scaled, maxHealth.getValue()));
    }

    /**
     * モブの表示名を MiniMessage として解決する。レベル 2 以上は {@code Lv.X} を前置する。
     */
    private Component displayName(String name, int level) {
        String text = level > 1 ? "<gray>Lv." + level + "</gray> " + name : name;

        MessageService messageService = plugin.getMessageService();
        if (messageService == null) {
            return Component.text(text);
        }
        return messageService.deserialize(text, Map.of());
    }
    /**
     * 同じモブ ID の警告は 1 回だけ出力する。
     */
    private void warnOnce(String mobId, String message) {
        if (warnedMobs.add(mobId == null ? "" : mobId.toLowerCase(Locale.ROOT))) {
            plugin.getLogger().warning(message);
        }
    }

    private static String key(String instanceId) {
        return instanceId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 検知半径内にスポーン対象のプレイヤーがいるか判定する。
     * <p>
     * {@link GameMode#SPECTATOR} と {@link GameMode#CREATIVE} は除外し、
     * サバイバル・アドベンチャーのプレイヤーだけを対象にする。
     */
    private boolean hasNearbyPlayer(World world, Location center, double radius) {
        double squared = radius * radius;
        for (Player player : world.getPlayers()) {
            GameMode mode = player.getGameMode();
            if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
                continue;
            }
            if (player.getLocation().distanceSquared(center) <= squared) {
                return true;
            }
        }
        return false;
    }

    /**
     * 検知半径内に生存している同種モブの数を数える。
     * <p>
     * ワールド全体のエンティティを走査せず、検知半径の範囲内だけを取得する。
     * {@link World#getNearbyEntities(Location, double, double, double)} は
     * 直方体で判定するため、球状判定に合わせて距離でも絞り込む。
     */
    private int countAlive(World world, Location center, MobData data, double radius) {
        String tag = MOB_TAG_PREFIX + data.id();
        double squared = radius * radius;
        int count = 0;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living.isDead() || !living.getScoreboardTags().contains(tag)) {
                continue;
            }
            if (living.getLocation().distanceSquared(center) <= squared) {
                count++;
            }
        }
        return count;
    }
    /**
     * レイヤーのモブの AI を一括で有効／無効にする。
     */
    private void setMobsAware(World world, boolean aware) {
        if (world == null) {
            return;
        }
        for (LivingEntity living : world.getLivingEntities()) {
            if (isManagedMob(living) && living instanceof Mob mob) {
                mob.setAware(aware);
            }
        }
        debug("モブの AI を" + (aware ? "再開" : "停止") + "しました: world=" + world.getName());
    }

    private static boolean isManagedMob(LivingEntity living) {
        for (String tag : living.getScoreboardTags()) {
            if (tag.startsWith(MOB_TAG_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 奈落・溶岩・壁の中にいる管理対象モブを消滅させる。
     * <p>
     * 壁の中の判定は、モブの足元ブロックと頭のブロックが<b>両方とも</b>
     * {@link CollisionManager#isSolidBlock(Material)} で {@code true} の場合のみ行う。
     * これにより葉っぱ・水・溶岩・植物の中にいるモブは消滅しない。
     *
     * @param world 対象ワールド
     */
    private void despawnInvalidMobs(World world) {
        CollisionManager collisionManager = plugin.getCollisionManager();

        for (LivingEntity living : new ArrayList<>(world.getLivingEntities())) {
            if (!isManagedMob(living)) {
                continue;
            }

            Location location = living.getLocation();
            if (location.getY() <= VOID_DESPAWN_Y
                    || location.getBlock().getType() == Material.LAVA
                    || (collisionManager != null && isInsideWall(location, collisionManager))) {
                removeManagedMob(living);
            }
        }
    }

    /**
     * 足元と頭の両方が壁ブロックか判定する。
     */
    private static boolean isInsideWall(Location location, CollisionManager collisionManager) {
        Material feet = location.getBlock().getType();
        Material head = location.clone().add(0, 1, 0).getBlock().getType();
        return collisionManager.isSolidBlock(feet) && collisionManager.isSolidBlock(head);
    }

    /**
     * 管理対象モブをワールドから除去し、実行時ステータスの登録も破棄する。
     */
    private void removeManagedMob(LivingEntity living) {
        MobStatsService mobStatsService = plugin.getMobStatsService();
        if (mobStatsService != null) {
            mobStatsService.remove(living.getUniqueId());
        }
        living.remove();
    }

    private void ensureTask() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    private void stopTaskIfIdle() {
        if (!active.isEmpty()) {
            return;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 1 周期分の判定処理。
     * <p>
     * プレイヤーが不在のワールドはスポーン判定を行わず、AI だけ停止する。
     * 個々のスポナーの失敗が他へ波及しないよう例外を握りつぶす。
     */
    private void tick() {
        if (active.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        tickCounter += TICK_INTERVAL;

        for (Map.Entry<String, ActiveLayer> entry : new ArrayList<>(active.entrySet())) {
            ActiveLayer layer = entry.getValue();
            World world = layer.world;

            if (world == null) {
                active.remove(entry.getKey());
                continue;
            }

            // 奈落・溶岩・壁の中にいるモブは毎秒消滅させる（プレイヤー不在でも実行する）。
            despawnInvalidMobs(world);

            boolean hasPlayers = !world.getPlayers().isEmpty();
            if (hasPlayers != layer.mobsAware) {
                layer.mobsAware = hasPlayers;
                setMobsAware(world, hasPlayers);
            }

            // プレイヤー不在のワールドはこの周期のスポーン判定を丸ごと間引く。
            if (!hasPlayers) {
                continue;
            }

            for (ActiveSpawner spawner : new ArrayList<>(layer.spawners)) {
                if (tickCounter < spawner.nextRun) {
                    continue;
                }
                spawner.nextRun = tickCounter + spawner.definition.spawnRateTicks();

                try {
                    runSpawner(world, spawner);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("スポナー処理に失敗しました: instance="
                            + entry.getKey() + ", spawner=" + spawner.definition.displayId()
                            + " (" + e.getMessage() + ")");
                }
            }
        }

        stopTaskIfIdle();
    }

    private void debug(String message) {
        plugin.getLogger().fine(DEBUG_PREFIX + message);
    }

    private static String formatLocation(Location location) {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                location.getX(), location.getY(), location.getZ());
    }
    /**
     * 1 インスタンス分の稼働状態。スポナーごとの次回実行 tick を保持する。
     */
    private static final class ActiveLayer {

        private final World world;
        private final List<ActiveSpawner> spawners = new ArrayList<>();
        private boolean mobsAware;

        private ActiveLayer(World world, boolean mobsAware) {
            this.world = world;
            this.mobsAware = mobsAware;
        }

        private boolean contains(String mobId, double x, double y, double z) {
            for (ActiveSpawner spawner : spawners) {
                if (!spawner.definition.mobId().equalsIgnoreCase(mobId)) {
                    continue;
                }
                Location location = spawner.location;
                if (Math.abs(location.getX() - x) < 0.001
                        && Math.abs(location.getY() - y) < 0.001
                        && Math.abs(location.getZ() - z) < 0.001) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 稼働中の 1 スポナー。レイヤー側の定義と次回実行 tick を保持する。
     */
    private static final class ActiveSpawner {

        private final SpawnerDefinition definition;
        private final Location location;
        private long nextRun;

        private ActiveSpawner(SpawnerDefinition definition, Location location, long nextRun) {
            this.definition = definition;
            this.location = location;
            this.nextRun = nextRun;
        }
    }
}
