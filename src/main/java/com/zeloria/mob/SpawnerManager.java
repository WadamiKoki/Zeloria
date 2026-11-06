package com.zeloria.mob;

import com.zeloria.core.MessageService;
import com.zeloria.core.YamlLoader;
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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
 * {@code SpawnerData} の定義と {@code LayerData} の座標からモブを定期的にスポーンさせる。
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
 */
public final class SpawnerManager {

    /** モブを識別する PDC キー（{@code AdminCommand} / {@link MobStatsService} と同一）。 */
    public static final String MOB_ID_KEY = MobStatsService.MOB_ID_KEY;

    /** スポーン済みモブへ付与するスコアボードタグの接頭辞（{@code AdminCommand} と同一）。 */
    public static final String MOB_TAG_PREFIX = "zeloria_mob:";

    /** スポナー表示エンティティを識別する PDC キー。 */
    public static final String SPAWNER_ID_KEY = "spawner_id";

    /** スポナー定義 YAML の格納ディレクトリ。 */
    private static final String SPAWNER_DATA_DIRECTORY = "SpawnerData/";

    /** 判定タスクの実行間隔（tick）。 */
    private static final int TICK_INTERVAL = 20;

    /** 安全なスポーン地点を探す最大試行回数。 */
    private static final int SPAWN_ATTEMPTS = 10;

    /** 基準座標からずらして安全地点を探す最大距離（ブロック）。 */
    private static final int SPAWN_SPREAD = 4;

    /** 基準座標から上下にずらして安全地点を探す最大距離（ブロック）。 */
    private static final int SPAWN_Y_RANGE = 4;

    /** 複数スポナーの初回判定をずらす最大 tick 数。 */
    private static final int MAX_STAGGER_TICKS = 200;

    private static final String DEBUG_PREFIX = "[SpawnerDebug] ";

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;
    private final NamespacedKey spawnerIdKey;

    /** スポナー ID（小文字） → 定義。 */
    private final Map<String, SpawnerData> definitions = new ConcurrentHashMap<>();

    /** インスタンス ID → 稼働中のスポナー群。 */
    private final Map<String, ActiveLayer> active = new ConcurrentHashMap<>();

    /** 警告済みのモブ ID（存在しない定義でログが溢れるのを防ぐ）。 */
    private final Set<String> warnedMobs = ConcurrentHashMap.newKeySet();

    /** 警告済みのスポナー ID。 */
    private final Set<String> warnedSpawners = ConcurrentHashMap.newKeySet();

    private BukkitTask task;
    private long tickCounter;

    public SpawnerManager(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
        this.spawnerIdKey = new NamespacedKey(plugin, SPAWNER_ID_KEY);
        reloadDefinitions();
    }

    /**
     * {@code SpawnerData/*.yml} を読み直して定義を再構築する。
     */
    public void reloadDefinitions() {
        definitions.clear();
        warnedSpawners.clear();

        YamlLoader yamlLoader = plugin.getYamlLoader();
        if (yamlLoader == null) {
            return;
        }

        for (Map.Entry<String, FileConfiguration> entry : yamlLoader.getConfigs().entrySet()) {
            String sourceKey = entry.getKey();
            if (sourceKey == null || !sourceKey.startsWith(SPAWNER_DATA_DIRECTORY)) {
                continue;
            }
            FileConfiguration config = entry.getValue();
            if (config == null) {
                continue;
            }

            SpawnerData data = SpawnerData.fromConfig(sourceKey, config);
            if (!data.isValid()) {
                plugin.getLogger().warning("スポナー定義が不正です（id または mobId が空）: " + sourceKey);
                continue;
            }
            definitions.put(data.id().toLowerCase(Locale.ROOT), data);
        }

        debug("スポナー定義を読み込みました: count=" + definitions.size());
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
            debug("LayerConfigService が未初期化のためスポナーを開始できません: instance=" + instanceId);
            return;
        }

        Optional<LayerData> found = templateId == null || templateId.isBlank()
                ? layerConfigService.findById(instanceId)
                : layerConfigService.findByTemplate(templateId);
        if (found.isEmpty()) {
            debug("レイヤー定義が見つかりません: instance=" + instanceId + ", template=" + templateId);
            return;
        }

        List<SpawnerPlacement> spawners = found.get().getSpawners();

        // サーバー停止時に消し切れなかった表示が残っていても二重表示にならないよう、
        // このワールドのスポナー表示を一度すべて除去してから登録し直す。
        removeSpawnerDisplays(world);

        if (spawners.isEmpty()) {
            debug("スポナー未定義のレイヤーです: instance=" + instanceId);
            return;
        }

        debug("レイヤーのスポナーを開始します: instance=" + instanceId
                + ", world=" + world.getName() + ", spawners=" + spawners.size());

        for (SpawnerPlacement placement : spawners) {
            startFor(world, instanceId, placement);
        }
    }

    /**
     * 1 スポナー分の稼働登録と表示エンティティの生成を行う。
     *
     * @param world      対象ワールド
     * @param instanceId インスタンス ID
     * @param placement  レイヤー側の配置定義（ID と座標）
     */
    public void startFor(World world, String instanceId, SpawnerPlacement placement) {
        if (world == null || instanceId == null || instanceId.isBlank() || placement == null) {
            return;
        }

        SpawnerData data = findDefinition(placement.spawnerId());
        if (data == null) {
            warnSpawnerOnce(placement.spawnerId(), "スポナー定義が見つかりません: spawner="
                    + placement.spawnerId() + ", instance=" + instanceId);
            return;
        }

        String instanceKey = key(instanceId);
        boolean hasPlayers = !world.getPlayers().isEmpty();
        ActiveLayer layer = active.get(instanceKey);
        if (layer == null || layer.world != world) {
            layer = new ActiveLayer(world, hasPlayers);
            active.put(instanceKey, layer);
            if (!hasPlayers) {
                setMobsAware(world, false);
            }
        }

        if (layer.contains(placement.spawnerId(), placement.x(), placement.y(), placement.z())) {
            debug("同一座標のスポナーは登録済みのためスキップします: instance=" + instanceId
                    + ", spawner=" + data.id());
            return;
        }

        Location location = new Location(world, placement.x(), placement.y(), placement.z());
        UUID displayId = null;
        try {
            displayId = spawnDisplay(world, location, data);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("スポナー表示の生成に失敗しました: spawner=" + data.id()
                    + ", world=" + world.getName() + " (" + e.getMessage() + ")");
        }

        long offset = (long) (layer.spawners.size() * TICK_INTERVAL) % MAX_STAGGER_TICKS;
        ActiveSpawner spawner = new ActiveSpawner(data, location, tickCounter + offset);
        layer.spawners.add(spawner);
        ensureTask();

        SpawnerData.Display display = data.display();
        debug("スポナーを登録しました: instance=" + instanceId
                + ", spawner=" + data.id()
                + ", mob=" + data.mobId()
                + ", level=" + data.level()
                + ", world=" + world.getName()
                + ", x=" + format(placement.x())
                + ", y=" + format(placement.y())
                + ", z=" + format(placement.z())
                + ", display=" + display.type()
                + ", material=" + display.material()
                + ", itemModel=" + (display.itemModel() == null ? "-" : display.itemModel())
                + ", scale=" + display.scale()
                + ", displayId=" + (displayId == null ? "-" : displayId));
    }


    /**
     * 指定インスタンスのスポーン判定を停止し、表示エンティティを除去する。
     *
     * @param instanceId インスタンス ID
     */
    public void stopFor(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        ActiveLayer layer = active.remove(key(instanceId));
        if (layer != null) {
            removeSpawnerDisplays(layer.world);
            debug("スポナーを停止しました: instance=" + instanceId + ", world=" + layer.world.getName());
        }
        stopTaskIfIdle();
    }

    /**
     * 設定再読み込み時に、稼働中インスタンスのスポナーを最新の定義で再登録する。
     * <p>
     * {@code LayerManager} が保持する「インスタンス → テンプレート」の対応を使い、
     * ロード済みワールドだけを対象とする。
     */
    public void reload() {
        debug("スポナー設定を再読み込みします");

        for (ActiveLayer layer : new ArrayList<>(active.values())) {
            removeSpawnerDisplays(layer.world);
        }
        active.clear();
        warnedMobs.clear();
        reloadDefinitions();

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
     * すべてのスポーン判定を停止し、表示とタスクを破棄する（プラグイン停止時用）。
     */
    public void shutdown() {
        for (ActiveLayer layer : new ArrayList<>(active.values())) {
            removeSpawnerDisplays(layer.world);
        }
        active.clear();
        warnedMobs.clear();
        warnedSpawners.clear();
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


    /**
     * 1 スポナー分の判定を行い、条件を満たせばモブをスポーンさせる。
     * <p>
     * デバッグログはスポーン成功時と安全地点が見つからなかった時のみ出力する。
     */
    private void runSpawner(World world, ActiveSpawner spawner) {
        SpawnerData data = spawner.data;
        Location center = spawner.location.clone();

        if (world.getPlayers().isEmpty()) {
            return;
        }
        if (!world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            return;
        }
        if (!hasNearbyPlayer(world, center, data.spawnRadius())) {
            return;
        }

        MobConfigService mobConfigService = plugin.getMobConfigService();
        Optional<MobData> found = mobConfigService == null
                ? Optional.empty()
                : mobConfigService.find(data.mobId());
        if (found.isEmpty()) {
            warnOnce(data.mobId(), "スポナーが参照する MobData が見つかりません: mob=" + data.mobId());
            return;
        }

        MobData mob = found.get();
        EntityType entityType = mob.spawnableEntityType();
        if (!entityType.isSpawnable()) {
            warnOnce(data.mobId(), "スポーンできないエンティティ種別です: mob=" + mob.id()
                    + ", entity=" + entityType.getKey());
            return;
        }

        int alive = countAlive(world, center, mob, data.spawnRadius());
        if (alive >= data.maxAlive()) {
            return;
        }

        int allowed = Math.min(data.spawnLimit(), data.maxAlive() - alive);
        for (int i = 0; i < allowed; i++) {
            Location location = findSpawnLocation(world, center);
            if (location == null) {
                plugin.getLogger().info("[Spawner] " + data.id()
                        + " 安全地点なし center=" + center);
                return;
            }

            spawn(world, location, mob, data.level(), entityType);
            plugin.getLogger().info("[Spawner] " + data.id()
                    + " スポーン成功 loc=" + location + " level=" + data.level());
        }
    }


    /**
     * スポナーの見た目（ItemDisplay / BlockDisplay）を生成する。
     *
     * @return 生成したエンティティの UUID（生成できなかった場合は {@code null}）
     */
    private UUID spawnDisplay(World world, Location location, SpawnerData data) {
        SpawnerData.Display display = data.display();
        Location centered = location.clone().add(0.5, 0.5, 0.5);

        if (display.type() == SpawnerData.DisplayType.BLOCK_DISPLAY) {
            BlockDisplay blockDisplay = world.spawn(centered, BlockDisplay.class);
            blockDisplay.setBlock(display.material().createBlockData());
            blockDisplay.setTransformation(transformation(display.scale()));
            blockDisplay.setInvulnerable(true);
            blockDisplay.getPersistentDataContainer().set(
                    spawnerIdKey, PersistentDataType.STRING, data.id());
            debug("BlockDisplay を生成しました: spawner=" + data.id()
                    + ", material=" + display.material() + ", scale=" + display.scale());
            return blockDisplay.getUniqueId();
        }

        ItemDisplay itemDisplay = world.spawn(centered, ItemDisplay.class);
        ItemStack stack = new ItemStack(display.material());
        if (display.itemModel() != null) {
            ItemMeta meta = stack.getItemMeta();
            NamespacedKey modelKey = namespacedKey(display.itemModel());
            if (meta != null && modelKey != null) {
                meta.setItemModel(modelKey);
                stack.setItemMeta(meta);
            } else {
                plugin.getLogger().warning("itemModel を解決できませんでした: spawner=" + data.id()
                        + ", itemModel=" + display.itemModel());
            }
        }
        itemDisplay.setItemStack(stack);
        // どの方向からでも見えるよう、プレイヤーの方を向かせる。
        itemDisplay.setBillboard(Display.Billboard.CENTER);
        itemDisplay.setTransformation(transformation(display.scale()));
        itemDisplay.setInvulnerable(true);
        itemDisplay.getPersistentDataContainer().set(
                spawnerIdKey, PersistentDataType.STRING, data.id());
        debug("ItemDisplay を生成しました: spawner=" + data.id()
                + ", material=" + display.material()
                + ", itemModel=" + (display.itemModel() == null ? "-" : display.itemModel())
                + ", scale=" + display.scale());
        return itemDisplay.getUniqueId();
    }

    /**
     * 表示用の拡大縮小変換を生成する。
     */
    private static Transformation transformation(float scale) {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    /**
     * {@code namespace:path} 形式の文字列を {@link NamespacedKey} に変換する。
     */
    private static NamespacedKey namespacedKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NamespacedKey.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * ワールド内のスポナー表示エンティティをすべて除去する。
     */
    private void removeSpawnerDisplays(World world) {
        if (world == null) {
            return;
        }
        int removed = 0;
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!(entity instanceof Display)) {
                continue;
            }
            if (entity.getPersistentDataContainer().get(spawnerIdKey, PersistentDataType.STRING) == null) {
                continue;
            }
            entity.remove();
            removed++;
        }
        if (removed > 0) {
            debug("スポナー表示を除去しました: world=" + world.getName() + ", count=" + removed);
        }
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

    /**
     * 同じスポナー ID の警告は 1 回だけ出力する。
     */
    private void warnSpawnerOnce(String spawnerId, String message) {
        if (warnedSpawners.add(spawnerId == null ? "" : spawnerId.toLowerCase(Locale.ROOT))) {
            plugin.getLogger().warning(message);
        }
    }

    private SpawnerData findDefinition(String spawnerId) {
        if (spawnerId == null || spawnerId.isBlank()) {
            return null;
        }
        return definitions.get(spawnerId.trim().toLowerCase(Locale.ROOT));
    }

    private static String key(String instanceId) {
        return instanceId.trim().toLowerCase(Locale.ROOT);
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
     * <p>
     * まず基準座標をそのまま確認し、見つからなければ X / Z をランダムにずらしつつ
     * Y 座標を {@code -4} ～ {@code +4} まで 1 ブロックずつ変えて確認する。
     *
     * @return スポーン地点。見つからない場合は {@code null}
     */
    private Location findSpawnLocation(World world, Location center) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (isSpawnable(center)) {
            return center.clone();
        }

        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int offsetX = random.nextInt(-SPAWN_SPREAD, SPAWN_SPREAD + 1);
            int offsetZ = random.nextInt(-SPAWN_SPREAD, SPAWN_SPREAD + 1);
            for (int offsetY = -SPAWN_Y_RANGE; offsetY <= SPAWN_Y_RANGE; offsetY++) {
                Location candidate = new Location(world,
                        center.getBlockX() + offsetX + 0.5,
                        center.getBlockY() + offsetY,
                        center.getBlockZ() + offsetZ + 0.5,
                        random.nextFloat() * 360.0f, 0.0f);
                if (!world.isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                    continue;
                }
                if (isSpawnable(candidate)) {
                    return candidate;
                }
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
                spawner.nextRun = tickCounter + spawner.data.spawnRateTicks();

                try {
                    runSpawner(world, spawner);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("スポナー処理に失敗しました: instance="
                            + entry.getKey() + ", spawner=" + spawner.data.id()
                            + ", mob=" + spawner.data.mobId()
                            + " (" + e.getMessage() + ")");
                }
            }
        }

        stopTaskIfIdle();
    }

    private void debug(String message) {
        plugin.getLogger().info(DEBUG_PREFIX + message);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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

        private boolean contains(String spawnerId, double x, double y, double z) {
            for (ActiveSpawner spawner : spawners) {
                if (!spawner.data.id().equalsIgnoreCase(spawnerId)) {
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
     * 稼働中の 1 スポナー。
     */
    private static final class ActiveSpawner {

        private final SpawnerData data;
        private final Location location;
        private long nextRun;

        private ActiveSpawner(SpawnerData data, Location location, long nextRun) {
            this.data = data;
            this.location = location;
            this.nextRun = nextRun;
        }
    }
}

