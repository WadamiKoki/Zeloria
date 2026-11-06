package com.zeloria.mob;

import com.zeloria.core.MessageService;
import com.zeloria.core.Zeloria;
import com.zeloria.world.LayerConfigService;
import com.zeloria.world.LayerData;
import com.zeloria.world.SpawnerPlacement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code LayerData} の {@code spawners} 定義に従ってモブを定期的にスポーンさせるマネージャ。
 * <p>
 * {@code LayerManager} がレイヤーワールドをロードしたタイミングで
 * {@link #startFor(World, String, String)}、アンロード・削除するタイミングで
 * {@link #stopFor(String)} を呼ぶ。
 * <p>
 * 判定は 1 本の繰り返しタスク（{@value #TICK_INTERVAL} tick 間隔）で行い、
 * スポナーごとの {@code interval} に達したものだけを処理する。スポーン条件は次のすべて。
 * <ul>
 *   <li>{@code radius} 以内にプレイヤーがいる</li>
 *   <li>その範囲に生存している同種モブが {@code maxAlive} 未満</li>
 *   <li>基準座標のチャンクがロード済み</li>
 *   <li>MobData の {@code entityType} がスポーン可能</li>
 * </ul>
 * 生成されたモブは {@code /admin mob spawn} と同一の PDC（{@code zeloria:mob_id}）と
 * スコアボードタグ（{@code zeloria_mob:<mobId>}）を持ち、カスタムダメージ計算の対象となる。
 */
public final class SpawnerManager {

    /** モブを識別する PDC キー（{@code AdminCommand} / {@link MobStatsService} と同一）。 */
    public static final String MOB_ID_KEY = MobStatsService.MOB_ID_KEY;

    /** スポーン済みモブへ付与するスコアボードタグの接頭辞（{@code AdminCommand} と同一）。 */
    public static final String MOB_TAG_PREFIX = "zeloria_mob:";

    /** 判定タスクの実行間隔（tick）。 */
    private static final int TICK_INTERVAL = 20;

    /** 安全なスポーン地点を探す試行回数。 */
    private static final int SPAWN_ATTEMPTS = 8;

    /** 基準座標からずらして安全地点を探す最大距離（ブロック）。 */
    private static final int SPAWN_SPREAD = 4;

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;

    /** インスタンス ID → 稼働中のスポナー群。 */
    private final Map<String, ActiveLayer> active = new ConcurrentHashMap<>();

    /** 警告済みのモブ ID（存在しない定義でログが溢れるのを防ぐ）。 */
    private final Set<String> warnedMobs = ConcurrentHashMap.newKeySet();

    private BukkitTask task;
    private long tickCounter;

    public SpawnerManager(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
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
     */
    public void startFor(World world, String instanceId, String templateId) {
        if (world == null || instanceId == null || instanceId.isBlank()) {
            return;
        }

        stopFor(instanceId);

        LayerConfigService layerConfigService = plugin.getLayerConfigService();
        if (layerConfigService == null) {
            return;
        }

        Optional<LayerData> found = templateId == null || templateId.isBlank()
                ? layerConfigService.findById(instanceId)
                : layerConfigService.findByTemplate(templateId);
        if (found.isEmpty()) {
            return;
        }

        List<SpawnerPlacement> spawners = found.get().getSpawners();
        if (spawners.isEmpty()) {
            return;
        }

        active.put(key(instanceId), new ActiveLayer(world, spawners, tickCounter));
        ensureTask();

        plugin.getLogger().info("レイヤーのスポナーを開始しました: instance=" + instanceId
                + ", spawners=" + spawners.size());
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
        if (active.remove(key(instanceId)) != null) {
            stopTaskIfIdle();
        }
    }

    /**
     * 設定再読み込み時に、稼働中インスタンスのスポナーを最新の定義で再登録する。
     * <p>
     * {@code LayerManager} が保持する「インスタンス → テンプレート」の対応を使い、
     * ロード済みワールドだけを対象とする。
     */
    public void reload() {
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
     * 1 スポナー分の判定を行い、条件を満たせばモブをスポーンさせる。
     */
    private void runSpawner(World world, SpawnerPlacement spawner) {
        Location center = new Location(world, spawner.x(), spawner.y(), spawner.z());

        if (!world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            return;
        }
        if (!hasNearbyPlayer(world, center, spawner.radius())) {
            return;
        }

        MobConfigService mobConfigService = plugin.getMobConfigService();
        Optional<MobData> found = mobConfigService == null
                ? Optional.empty()
                : mobConfigService.find(spawner.mobId());
        if (found.isEmpty()) {
            warnOnce(spawner.mobId(), "スポナーが参照する MobData が見つかりません: mob="
                    + spawner.mobId());
            return;
        }

        MobData data = found.get();
        EntityType entityType = data.spawnableEntityType();
        if (!entityType.isSpawnable()) {
            warnOnce(spawner.mobId(), "スポーンできないエンティティ種別です: mob=" + data.id()
                    + ", entity=" + entityType.getKey());
            return;
        }

        int alive = countAlive(world, center, data, spawner.radius());
        int allowed = Math.min(spawner.spawnAmount(), spawner.maxAlive() - alive);
        for (int i = 0; i < allowed; i++) {
            Location location = findSpawnLocation(world, center);
            if (location == null) {
                return;
            }
            spawn(world, location, data, spawner.level(), entityType);
        }
    }

    /**
     * 検知半径内にスポーン対象のプレイヤーがいるか判定する。
     */
    private boolean hasNearbyPlayer(World world, Location center, double radius) {
        double squared = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
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
     */
    private int countAlive(World world, Location center, MobData data, double radius) {
        String tag = MOB_TAG_PREFIX + data.id();
        double squared = radius * radius;
        int count = 0;
        for (LivingEntity entity : world.getLivingEntities()) {
            if (entity.isDead() || !entity.getScoreboardTags().contains(tag)) {
                continue;
            }
            if (entity.getLocation().distanceSquared(center) <= squared) {
                count++;
            }
        }
        return count;
    }

    /**
     * 基準座標の周辺から、足元と頭上に空間があり直下が固体ブロックの地点を探す。
     *
     * @return スポーン地点。見つからない場合は {@code null}
     */
    private Location findSpawnLocation(World world, Location center) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        Location first = center.clone();
        first.setYaw(random.nextFloat() * 360.0f);
        if (isSpawnable(first)) {
            return first;
        }

        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double offsetX = random.nextInt(-SPAWN_SPREAD, SPAWN_SPREAD + 1) + 0.5;
            double offsetZ = random.nextInt(-SPAWN_SPREAD, SPAWN_SPREAD + 1) + 0.5;
            Location candidate = new Location(world,
                    center.getBlockX() + offsetX,
                    center.getBlockY(),
                    center.getBlockZ() + offsetZ,
                    random.nextFloat() * 360.0f, 0.0f);
            if (!world.isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                continue;
            }
            if (isSpawnable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * その地点にモブを立たせられるか判定する。
     */
    private boolean isSpawnable(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        return feet.isPassable() && head.isPassable() && isSolidGround(ground.getType());
    }

    private static boolean isSolidGround(Material material) {
        return material.isSolid() && material != Material.LAVA && material != Material.MAGMA_BLOCK;
    }

    /**
     * すべてのスポーン判定を停止し、タスクを破棄する（プラグイン停止時用）。
     */
    public void shutdown() {
        active.clear();
        warnedMobs.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 稼働中のインスタンス数。
     */
    public int getActiveCount() {
        return active.size();
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
     * 1 周期分の判定処理。個々のスポナーの失敗が他へ波及しないよう例外を握りつぶす。
     */
    private void tick() {
        if (active.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        tickCounter += TICK_INTERVAL;

        for (Map.Entry<String, ActiveLayer> entry : new ArrayList<>(active.entrySet())) {
            ActiveLayer layer = entry.getValue();

            for (int i = 0; i < layer.spawners.size(); i++) {
                SpawnerPlacement spawner = layer.spawners.get(i);
                if (tickCounter < layer.nextRun[i]) {
                    continue;
                }
                layer.nextRun[i] = tickCounter + spawner.interval();

                try {
                    runSpawner(layer.world, spawner);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("スポナー処理に失敗しました: instance="
                            + entry.getKey() + ", mob=" + spawner.mobId()
                            + " (" + e.getMessage() + ")");
                }
            }
        }

        stopTaskIfIdle();
    }

    /**
     * モブを 1 体スポーンし、定義値とレベルを反映する。
     */
    private void spawn(World world, Location location, MobData data, int level, EntityType entityType) {
        world.spawnEntity(location, entityType, CreatureSpawnEvent.SpawnReason.SPAWNER,
                created -> applyMobData(created, data, level));
    }

    /**
     * スポーンしたエンティティへ {@link MobData} とレベルを反映する。
     * <p>
     * {@code AdminCommand#applyMobData} と同じ付与内容（表示名・タグ・PDC・最大 HP）に加え、
     * レベル倍率を掛けた最大 HP と実行時ステータスを設定する。
     */
    private void applyMobData(Entity entity, MobData data, int level) {
        entity.customName(displayName(data.name(), level));
        entity.setCustomNameVisible(true);
        entity.addScoreboardTag(MOB_TAG_PREFIX + data.id());
        entity.getPersistentDataContainer().set(mobIdKey, PersistentDataType.STRING, data.id());

        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        MobStatsService mobStatsService = plugin.getMobStatsService();
        if (mobStatsService != null) {
            mobStatsService.attach(living, data, level);
        }

        if (data.maxHp() <= 0.0) {
            return;
        }

        AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }

        double scaled = Math.max(1.0, data.maxHp() * MobStats.levelFactor(level));
        maxHealth.setBaseValue(scaled);
        living.setHealth(Math.min(scaled, maxHealth.getValue()));
    }

    /**
     * モブの表示名を解決する。レベル 2 以上は {@code Lv.X} を前置する。
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
     * 1 インスタンス分の稼働状態。スポナーごとの次回実行 tick を保持する。
     */
    private static final class ActiveLayer {

        private final World world;
        private final List<SpawnerPlacement> spawners;
        private final long[] nextRun;

        private ActiveLayer(World world, List<SpawnerPlacement> spawners, long now) {
            this.world = world;
            this.spawners = List.copyOf(spawners);
            this.nextRun = new long[this.spawners.size()];
            Arrays.fill(this.nextRun, now);
        }
    }
}

