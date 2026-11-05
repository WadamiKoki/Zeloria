package com.zeloria.npc;

import com.zeloria.core.YamlLoader;
import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code config/NpcData/*.yml} を解析し、NPC の生成・削除・識別を担うマネージャ。
 * <p>
 * スポーンした NPC は UUID を保持し、{@link NpcDamageListener} による
 * 無敵判定に使用する。また、エンティティの PDC に {@code npc_id} を格納するため、
 * メモリ上の UUID キャッシュが失われた場合でも NPC を識別できる。
 * <p>
 * NPC は {@link com.zeloria.mob.MobStats} を持たないため、
 * {@link com.zeloria.mob.MobStatsService#get(Entity)} は {@code null} を返し、
 * 戦闘システムのダメージ計算対象外となる。
 * <p>
 * ワールド・エンティティ操作を伴うため、各メソッドはメインスレッドから呼び出すこと。
 */
public final class NpcManager {

    /** NPC を識別する PersistentDataContainer キー。 */
    public static final String NPC_ID_KEY = "npc_id";

    private static final String KEY_PREFIX = "NpcData/";
    private static final String YAML_EXTENSION = ".yml";
    private static final String YAML_ALT_EXTENSION = ".yaml";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;
    private final NamespacedKey npcIdKey;

    /** 読み込み済みの NPC 定義（読み込み順）。 */
    private final List<NpcData> npcs = new CopyOnWriteArrayList<>();

    /** NPC ID・ファイル名 → NPC 定義。 */
    private final Map<String, NpcData> byId = new ConcurrentHashMap<>();

    /** スポーン済み NPC の UUID。 */
    private final Set<UUID> npcUuids = ConcurrentHashMap.newKeySet();

    public NpcManager(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        this.npcIdKey = new NamespacedKey(plugin, NPC_ID_KEY);
        reload();
    }

    /**
     * {@code NpcData/} 配下の YAML を再解析し、定義キャッシュを置き換える。
     * <p>
     * スポーン済み NPC の UUID キャッシュには触れない。
     */
    public synchronized void reload() {
        npcs.clear();
        byId.clear();

        for (String key : yamlLoader.getKeys()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            NpcData data;
            try {
                data = NpcData.fromConfig(key, config);
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "NpcData の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
                continue;
            }

            npcs.add(data);
            register(data.id(), data, key);
            register(fileNameOf(key), data, key);
        }

        plugin.getLogger().info("NpcData を " + npcs.size() + " 件読み込みました。");
    }

    /**
     * NPC ID（またはファイル名）から NPC 定義を取得する（大文字小文字は区別しない）。
     *
     * @param id NPC ID
     * @return NPC 定義。見つからない場合は空
     */
    public Optional<NpcData> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 読み込み済みの NPC 定義を取得する（不変ビュー）。
     */
    public List<NpcData> getAll() {
        return List.copyOf(npcs);
    }

    /**
     * 読み込み済みの NPC ID 一覧を取得する（タブ補完用）。
     */
    public List<String> getIds() {
        return npcs.stream().map(NpcData::id).toList();
    }

    /**
     * 読み込み済みの NPC 定義数。
     */
    public int size() {
        return npcs.size();
    }

    /**
     * スポーン済み NPC の数。
     */
    public int spawnedCount() {
        return npcUuids.size();
    }

    /**
     * NPC を指定座標へスポーンする。
     *
     * @param npcId    NPC 定義の ID
     * @param location スポーン座標（ワールド必須）
     * @return スポーンした NPC。定義が見つからない・生成に失敗した場合は空
     */
    public Optional<LivingEntity> spawnNpc(String npcId, Location location) {
        Optional<NpcData> found = find(npcId);
        if (found.isEmpty()) {
            plugin.getLogger().warning("NPC 定義が見つかりません: " + npcId);
            return Optional.empty();
        }
        return spawnNpc(found.get(), location);
    }

    /**
     * NPC を指定座標へスポーンする。
     *
     * @param data     NPC 定義
     * @param location スポーン座標（ワールド必須）
     * @return スポーンした NPC。生成に失敗した場合は空
     */
    public Optional<LivingEntity> spawnNpc(NpcData data, Location location) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(location, "location");

        World world = location.getWorld();
        if (world == null) {
            plugin.getLogger().warning(
                    "NPC '" + data.id() + "' のスポーン先ワールドが未指定です。");
            return Optional.empty();
        }

        EntityType entityType = data.spawnableMobType();
        if (!entityType.isSpawnable()) {
            plugin.getLogger().warning(
                    "NPC '" + data.id() + "' のエンティティ種別はスポーンできません: "
                            + entityType.getKey());
            return Optional.empty();
        }

        Entity spawned;
        try {
            spawned = world.spawnEntity(location, entityType);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "NPC '" + data.id() + "' のスポーンに失敗しました: " + e.getMessage());
            return Optional.empty();
        }

        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            plugin.getLogger().warning(
                    "NPC '" + data.id() + "' は LivingEntity ではないため設定できません: "
                            + entityType.getKey());
            return Optional.empty();
        }

        applyData(living, data);
        npcUuids.add(living.getUniqueId());
        return Optional.of(living);
    }

    /**
     * NPC 定義をエンティティへ反映する。
     */
    private void applyData(LivingEntity living, NpcData data) {
        living.setPersistent(true);
        living.setSilent(true);
        living.setGravity(false);
        living.setAI(false);
        // プレイヤーが離れてもデスポーンさせない（レイヤー破棄時にワールドごと消える）。
        living.setRemoveWhenFarAway(false);

        living.customName(MINI.deserialize(data.name()));
        living.setCustomNameVisible(data.alwaysShowName());

        living.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, data.id());
        data.equipment().apply(living.getEquipment());
    }

    /**
     * エンティティが NPC か判定する。
     * <p>
     * UUID キャッシュに加え、PDC の {@code npc_id} でも判定する。
     *
     * @param entity 対象エンティティ
     * @return NPC の場合は {@code true}
     */
    public boolean isNpc(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (npcUuids.contains(entity.getUniqueId())) {
            return true;
        }
        String npcId = entity.getPersistentDataContainer()
                .get(npcIdKey, PersistentDataType.STRING);
        return npcId != null && !npcId.isBlank();
    }

    /**
     * 指定 UUID が NPC か判定する。
     */
    public boolean isNpc(UUID uuid) {
        return uuid != null && npcUuids.contains(uuid);
    }

    /**
     * エンティティに紐づく NPC 定義を取得する。
     *
     * @param entity 対象エンティティ
     * @return NPC 定義。NPC でない・定義が見つからない場合は空
     */
    public Optional<NpcData> findNpc(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        String npcId = entity.getPersistentDataContainer()
                .get(npcIdKey, PersistentDataType.STRING);
        return find(npcId);
    }

    /**
     * 指定座標から最も近い NPC を削除する（デバッグ用）。
     *
     * @param center 中心座標
     * @param radius 探索半径（ブロック）
     * @return 削除した NPC。見つからない場合は空
     */
    public Optional<LivingEntity> removeNearest(Location center, double radius) {
        if (center == null || center.getWorld() == null || radius <= 0.0) {
            return Optional.empty();
        }

        World world = center.getWorld();
        LivingEntity nearest = null;
        double nearestDistance = radius * radius;

        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !isNpc(living)) {
                continue;
            }
            double distance = living.getLocation().distanceSquared(center);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = living;
            }
        }

        if (nearest == null) {
            return Optional.empty();
        }

        UUID uuid = nearest.getUniqueId();
        nearest.remove();
        npcUuids.remove(uuid);
        return Optional.of(nearest);
    }

    /**
     * スポーン済みの NPC をすべて削除する（プラグイン停止時用）。
     */
    public void removeAll() {
        for (UUID uuid : List.copyOf(npcUuids)) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }
        npcUuids.clear();
    }

    private void register(String key, NpcData data, String sourceKey) {
        if (key == null || key.isBlank()) {
            return;
        }
        NpcData previous = byId.putIfAbsent(key.toLowerCase(Locale.ROOT), data);
        if (previous != null && previous != data) {
            // 重複は先勝ち。運用者が気づけるよう警告のみ行う。
            plugin.getLogger().warning(
                    "NpcData のキーが重複しています: '" + key + "' (" + sourceKey + ")");
        }
    }

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        String fileName = slash < 0 ? key : key.substring(slash + 1);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(YAML_ALT_EXTENSION)) {
            return fileName.substring(0, fileName.length() - YAML_ALT_EXTENSION.length());
        }
        return lower.endsWith(YAML_EXTENSION)
                ? fileName.substring(0, fileName.length() - YAML_EXTENSION.length())
                : fileName;
    }
}
