package com.zeloria.player;

import com.zeloria.gathering.GatheringType;
import com.zeloria.player.PlayerClassData;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーの永続データを保持するモデルクラス。
 * <p>
 * DB の {@code players} テーブルと対応し、次の情報を保持する。
 * <ul>
 *     <li>基本情報: 所持金、クラス別レベル・経験値</li>
 *     <li>RPG ステータス: {@link PlayerStats}（HP / MP・攻撃力・防御力・クリティカル・
 *         命中 / 回避・クールダウン短縮・移動速度・リジェネ・防御貫通・吸血）</li>
 *     <li>クラス状態: 現在のクラス・解放済みクラス</li>
 *     <li>仮想装備（{@link EquipSlot} ごとの {@link ItemStack}）</li>
 *     <li>仮想インベントリ（保管庫・装備保管庫の 2 タブ。{@link VirtualItemEntry}）</li>
 * </ul>
 * ログイン中のプレイヤーデータはクラス内のオンメモリキャッシュで共有する。
 * GUI からの読み書きはメインスレッドで行われるが、非同期保存と競合しないよう
 * 各操作は {@code synchronized} で保護している。保存時は {@link #snapshot()} で
 * 不変の {@link PlayerSnapshot} を生成してから非同期スレッドへ渡す。
 */
public class PlayerData {

    /**
     * ログイン中のプレイヤーデータを保持するオンメモリキャッシュ（UUID キー）。
     */
    private static final Map<UUID, PlayerData> CACHE = new ConcurrentHashMap<>();

    /** クラス別レベルの既定値（クラス選択時に 1 から開始する）。 */
    public static final int DEFAULT_CLASS_LEVEL = 1;

    /** ギャザリングレベルの既定値（全種別 1 から開始する）。 */
    public static final int DEFAULT_GATHERING_LEVEL = 1;

    private UUID uuid;
    private String name = "";

    private long money;

    /** スキルクールダウン終了時刻（ログアウト時のみ保存するシリアライズ文字列）。 */
    private String cooldownEndAt = "";

    /** RPG ステータス（HP / MP・攻撃力・クリティカル・リジェネなど）。 */
    private final PlayerStats stats = new PlayerStats();

    /** 装備中アイテム（防具 4 部位 + メイン武器 + オフハンド）。 */
    private final Map<EquipSlot, ItemStack> equippedItems = new EnumMap<>(EquipSlot.class);

    /** 通常の保管庫タブの仮想アイテム。 */
    private final Map<String, VirtualItemEntry> virtualInventory = new LinkedHashMap<>();

    /** 装備保管庫タブの仮想アイテム（武器・防具）。 */
    private final Map<String, VirtualItemEntry> virtualEquipment = new LinkedHashMap<>();

    /** クラス状態（現在のクラス・解放済みクラス）。 */
    private final PlayerClassData classData = new PlayerClassData();

    /** クラス ID → クラス別レベル。 */
    private final Map<String, Integer> classLevels = new LinkedHashMap<>();

    /** クラス ID → クラス別経験値。 */
    private final Map<String, Long> classExps = new LinkedHashMap<>();

    /** クラス ID → 未使用のツリーポイント。 */
    private final Map<String, Integer> classTreePoints = new LinkedHashMap<>();

    /** クラス ID → 解放済みツリーノード ID。 */
    private final Map<String, Set<String>> allocatedClassNodes = new LinkedHashMap<>();

    /** ギャザリング種別 → アカウント共通レベル。 */
    private final Map<GatheringType, Integer> gatheringLevels = new EnumMap<>(GatheringType.class);

    /** ギャザリング種別 → アカウント共通経験値。 */
    private final Map<GatheringType, Long> gatheringExps = new EnumMap<>(GatheringType.class);

    public PlayerData() {
    }

    public PlayerData(UUID uuid, String name) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = name == null ? "" : name;
    }

    /* ------------------------------------------------------------------
     * キャッシュ操作
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーデータをオンメモリキャッシュへ登録する。
     *
     * @param data 登録するデータ
     * @throws IllegalArgumentException UUID が未設定の場合
     */
    public static void cache(PlayerData data) {
        Objects.requireNonNull(data, "data");
        if (data.getUuid() == null) {
            throw new IllegalArgumentException("UUID が未設定の PlayerData はキャッシュできません。");
        }
        CACHE.put(data.getUuid(), data);
    }

    /**
     * キャッシュされたプレイヤーデータを取得する。
     *
     * @param uuid プレイヤー UUID
     * @return キャッシュされたデータ。存在しない場合は {@code null}
     */
    public static PlayerData getCached(UUID uuid) {
        return uuid == null ? null : CACHE.get(uuid);
    }

    /**
     * キャッシュからプレイヤーデータを削除する。
     *
     * @param uuid プレイヤー UUID
     * @return 削除されたデータ。存在しない場合は {@code null}
     */
    public static PlayerData removeCached(UUID uuid) {
        return uuid == null ? null : CACHE.remove(uuid);
    }

    /**
     * キャッシュをすべて破棄する（プラグイン停止時などに使用）。
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * キャッシュされているプレイヤー数を取得する。
     */
    public static int getCachedCount() {
        return CACHE.size();
    }

    /* ------------------------------------------------------------------
     * スナップショット変換
     * ------------------------------------------------------------------ */

    /**
     * 現在の状態から不変スナップショットを生成する。
     * <p>
     * アイテム列はこの時点で Base64 へ変換されるため、以降メインスレッドで
     * データが変更されても保存内容には影響しない。
     *
     * @return スナップショット
     * @throws IllegalStateException アイテム列のシリアライズに失敗した場合
     */
    public synchronized PlayerSnapshot snapshot() {
        return new PlayerSnapshot(
                uuid,
                name,
                money,
                classData.currentClassIdOrNull(),
                classData.serializedUnlocked(),
                ClassProgressJson.encodeIntMap(classLevels),
                ClassProgressJson.encodeLongMap(classExps),
                ClassProgressJson.encodeIntMap(classTreePoints),
                ClassProgressJson.encodeSetMap(allocatedClassNodes),
                GatheringJson.encodeLevels(gatheringLevels),
                GatheringJson.encodeExps(gatheringExps),
                InventorySerializer.encodeEquipment(equippedItems),
                VirtualInventoryJson.encode(virtualInventory),
                VirtualInventoryJson.encode(virtualEquipment),
                stats.getBaseMaxHealth(),
                stats.getBaseCurrentHealth(),
                stats.getBaseMaxMana(),
                stats.getBaseCurrentMana(),
                stats.getBaseAttackPower(),
                stats.getBaseDefense1(),
                stats.getBaseDefense2(),
                stats.getBaseCriticalRate(),
                stats.getBaseCriticalDamage(),
                stats.getBaseAccuracy(),
                stats.getBaseEvasion(),
                stats.getBaseDodgeCooldownReduction(),
                stats.getBaseCooldownReduction(),
                stats.getBaseMoveSpeed(),
                stats.getBaseHealthRegenFlat(),
                stats.getBaseHealthRegenPercent(),
                stats.getBaseManaRegenFlat(),
                stats.getBaseManaRegenPercent(),
                stats.getBaseArmorPenetration(),
                stats.getBaseLifesteal(),
                cooldownEndAt
        );
    }

    /**
     * スナップショットからプレイヤーデータを復元する。
     * <p>
     * 仮想装備の Base64 データをデコードする。
     *
     * @param snapshot スナップショット
     * @return 復元されたプレイヤーデータ
     * @throws IllegalArgumentException Base64 の形式が不正な場合
     * @throws IllegalStateException    デシリアライズに失敗した場合
     */
    public static PlayerData fromSnapshot(PlayerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        PlayerData data = new PlayerData(snapshot.uuid(), snapshot.playerName());
        data.money = snapshot.money();

        // 最大値 → 現在値の順で設定し、クランプが現在値を切り捨てないようにする。
        data.stats.setMaxHealth(snapshot.maxHealth());
        data.stats.setCurrentHealth(snapshot.currentHealth());
        data.stats.setMaxMana(snapshot.maxMana());
        data.stats.setCurrentMana(snapshot.currentMana());
        data.stats.setAttackPower(snapshot.attackPower());
        data.stats.setDefense1(snapshot.defense1());
        data.stats.setDefense2(snapshot.defense2());
        data.stats.setCriticalRate(snapshot.criticalRate());
        data.stats.setCriticalDamage(snapshot.criticalDamage());
        data.stats.setAccuracy(snapshot.accuracy());
        data.stats.setEvasion(snapshot.evasion());
        data.stats.setDodgeCooldownReduction(snapshot.dodgeCooldownReduction());
        data.stats.setCooldownReduction(snapshot.cooldownReduction());
        data.stats.setMoveSpeed(snapshot.walkSpeed());
        data.stats.setHealthRegenFlat(snapshot.healthRegenFlat());
        data.stats.setHealthRegenPercent(snapshot.healthRegenPercent());
        data.stats.setManaRegenFlat(snapshot.manaRegenFlat());
        data.stats.setManaRegenPercent(snapshot.manaRegenPercent());
        data.stats.setArmorPenetration(snapshot.armorPenetration());
        data.stats.setLifesteal(snapshot.lifesteal());
        data.cooldownEndAt = snapshot.cooldownEndAt();

        data.classData.restore(snapshot.currentClassId(), snapshot.unlockedClasses());
        data.restoreClassProgress(
                snapshot.classLevelsJson(),
                snapshot.classExpsJson(),
                snapshot.classTreePointsJson(),
                snapshot.allocatedClassNodesJson());
        data.restoreGatheringProgress(
                snapshot.gatheringLevelsJson(),
                snapshot.gatheringExpsJson());

        data.initializeCurrentClassProgress();

        data.equippedItems.putAll(InventorySerializer.decodeEquipment(snapshot.virtualEquipmentBase64()));
        data.virtualInventory.putAll(VirtualInventoryJson.decode(snapshot.virtualInventoryJson()));
        data.virtualEquipment.putAll(VirtualInventoryJson.decode(snapshot.virtualEquipmentJson()));

        return data;
    }

    /* ------------------------------------------------------------------
     * 仮想装備
     * ------------------------------------------------------------------ */

    /**
     * 指定スロットの装備を取得する。
     *
     * @param slot 装備スロット
     * @return 装備アイテム。空の場合は {@code null}
     */
    public synchronized ItemStack getEquipment(EquipSlot slot) {
        if (slot == null) {
            return null;
        }
        ItemStack item = equippedItems.get(slot);
        return item == null || item.isEmpty() ? null : item.clone();
    }

    /**
     * 指定スロットへ装備を設定する。
     *
     * @param slot 装備スロット
     * @param item 設定するアイテム（{@code null} で空にする）
     */
    public synchronized void setEquipment(EquipSlot slot, ItemStack item) {
        if (slot == null) {
            return;
        }
        if (item == null || item.isEmpty()) {
            equippedItems.remove(slot);
        } else {
            equippedItems.put(slot, item.clone());
        }
    }

    /**
     * 仮想装備のコピーを取得する（空枠は含まない）。
     */
    public synchronized Map<EquipSlot, ItemStack> getVirtualEquipment() {
        Map<EquipSlot, ItemStack> copy = new EnumMap<>(EquipSlot.class);
        equippedItems.forEach((slot, item) -> {
            if (item != null && !item.isEmpty()) {
                copy.put(slot, item.clone());
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /* ------------------------------------------------------------------
     * 仮想インベントリ（保管庫・装備保管庫タブ）
     * ------------------------------------------------------------------ */

    /**
     * 通常の保管庫タブのコピーを取得する。
     */
    public synchronized Map<String, VirtualItemEntry> getVirtualInventory() {
        return copyEntries(virtualInventory);
    }

    /**
     * 装備保管庫タブのコピーを取得する。
     */
    public synchronized Map<String, VirtualItemEntry> getVirtualEquipmentItems() {
        return copyEntries(virtualEquipment);
    }

    /**
     * 指定タブの仮想インベントリのコピーを取得する。
     *
     * @param tab タブ
     * @return キー → エントリのコピー
     */
    public synchronized Map<String, VirtualItemEntry> getVirtualBag(VirtualInventoryTab tab) {
        return copyEntries(bagOf(tab));
    }

    /**
     * 指定タブの 1 枠を取得する。
     *
     * @param tab タブ
     * @param key 枠キー
     * @return エントリ。存在しない場合は {@code null}
     */
    public synchronized VirtualItemEntry getVirtualBagEntry(VirtualInventoryTab tab, String key) {
        if (key == null) {
            return null;
        }
        return bagOf(tab).get(key);
    }

    /**
     * 指定タブの 1 枠を設定する。
     *
     * @param tab   タブ
     * @param key   枠キー
     * @param entry エントリ（{@code null} で削除）
     */
    public synchronized void putVirtualBagEntry(VirtualInventoryTab tab, String key,
                                                VirtualItemEntry entry) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (entry == null || entry.amount() <= 0 || entry.itemId().isBlank()) {
            bagOf(tab).remove(key);
        } else {
            bagOf(tab).put(key, entry);
        }
    }

    /**
     * 指定タブの 1 枠を削除する。
     *
     * @param tab タブ
     * @param key 枠キー
     * @return 削除されたエントリ。存在しない場合は {@code null}
     */
    public synchronized VirtualItemEntry removeVirtualBagEntry(VirtualInventoryTab tab, String key) {
        if (key == null) {
            return null;
        }
        return bagOf(tab).remove(key);
    }

    private Map<String, VirtualItemEntry> bagOf(VirtualInventoryTab tab) {
        if (tab == null) {
            return virtualInventory;
        }
        return switch (tab) {
            case STORAGE -> virtualInventory;
            case EQUIPMENT_STORAGE -> virtualEquipment;
        };
    }

    private static Map<String, VirtualItemEntry> copyEntries(Map<String, VirtualItemEntry> source) {
        Map<String, VirtualItemEntry> copy = new LinkedHashMap<>(source);
        return Collections.unmodifiableMap(copy);
    }

    /* ------------------------------------------------------------------
     * 基本情報・戦闘ステータス
     * ------------------------------------------------------------------ */

    /**
     * クラス状態を取得する。
     * <p>
     * 返される {@link PlayerClassData} はプレイヤーデータが保持する実体であり、
     * GUI からの変更はそのまま {@link #snapshot()} に反映される。
     *
     * @return クラス状態
     */
    public PlayerClassData getClassData() {
        return classData;
    }

    /* ------------------------------------------------------------------
     * クラス別の進行状況（レベル・経験値・ツリーポイント・解放ノード）
     * ------------------------------------------------------------------ */

    /**
     * 指定クラスのレベルを取得する。未進行の場合も既定値の 1 を返す。
     */
    public synchronized int getClassLevel(String classId) {
        String normalized = normalizeClassId(classId);
        if (normalized.isEmpty()) {
            return 0;
        }
        return Math.max(DEFAULT_CLASS_LEVEL,
                classLevels.getOrDefault(normalized, DEFAULT_CLASS_LEVEL));
    }

    /**
     * 指定クラスのレベルを設定する（1 未満は 1）。
     */
    public synchronized void setClassLevel(String classId, int level) {
        String normalized = normalizeClassId(classId);
        if (!normalized.isEmpty()) {
            classLevels.put(normalized, Math.max(DEFAULT_CLASS_LEVEL, level));
        }
    }

    /**
     * 指定クラスの進行データが未作成なら、レベル 1・経験値 0 で初期化する。
     * <p>
     * 既存の進行データがある場合は上書きしない。
     *
     * @param classId クラス ID
     */
    public synchronized void initializeClassProgress(String classId) {
        String normalized = normalizeClassId(classId);
        if (!normalized.isEmpty()) {
            int currentLevel = classLevels.getOrDefault(normalized, DEFAULT_CLASS_LEVEL);
            classLevels.put(normalized, Math.max(DEFAULT_CLASS_LEVEL, currentLevel));
            classExps.putIfAbsent(normalized, 0L);
        }
    }

    /**
     * 現在選択中のクラスのレベルを取得する。
     *
     * @return 現在クラスのレベル。未選択の場合は 0
     */
    public synchronized int getCurrentClassLevel() {
        String classId = classData.currentClassIdOrNull();
        return classId == null ? 0 : getClassLevel(classId);
    }

    /**
     * 現在選択中のクラスの経験値を取得する。
     *
     * @return 現在クラスの経験値。未選択の場合は 0
     */
    public synchronized long getCurrentClassExp() {
        String classId = classData.currentClassIdOrNull();
        return classId == null ? 0L : getClassExp(classId);
    }

    /**
     * 指定クラスの経験値を取得する。未進行の場合は 0。
     */
    public synchronized long getClassExp(String classId) {
        String normalized = normalizeClassId(classId);
        return normalized.isEmpty() ? 0L : classExps.getOrDefault(normalized, 0L);
    }

    /**
     * 指定クラスの経験値を設定する。
     */
    public synchronized void setClassExp(String classId, long exp) {
        String normalized = normalizeClassId(classId);
        if (!normalized.isEmpty()) {
            classExps.put(normalized, Math.max(0L, exp));
        }
    }

    /**
     * 指定クラスの未使用ツリーポイントを取得する。
     */
    public synchronized int getClassTreePoints(String classId) {
        String normalized = normalizeClassId(classId);
        return normalized.isEmpty() ? 0 : classTreePoints.getOrDefault(normalized, 0);
    }

    /**
     * 指定クラスの未使用ツリーポイントを設定する。
     */
    public synchronized void setClassTreePoints(String classId, int points) {
        String normalized = normalizeClassId(classId);
        if (!normalized.isEmpty()) {
            classTreePoints.put(normalized, Math.max(0, points));
        }
    }

    /**
     * 指定クラスのツリーポイントを加算する（マイナスで減算）。
     */
    public synchronized void addClassTreePoints(String classId, int amount) {
        String normalized = normalizeClassId(classId);
        if (normalized.isEmpty() || amount == 0) {
            return;
        }
        long current = classTreePoints.getOrDefault(normalized, 0);
        long next = Math.max(0L, Math.min(Integer.MAX_VALUE, current + amount));
        classTreePoints.put(normalized, (int) next);
    }

    /**
     * 指定クラスで解放済みのツリーノード ID を取得する（不変コピー）。
     */
    public synchronized Set<String> getAllocatedClassNodes(String classId) {
        String normalized = normalizeClassId(classId);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> nodes = allocatedClassNodes.get(normalized);
        return nodes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
    }

    /**
     * 指定クラスのツリーノードが解放済みか判定する。
     */
    public synchronized boolean isClassNodeAllocated(String classId, String nodeId) {
        String classKey = normalizeClassId(classId);
        String nodeKey = normalizeNodeId(nodeId);
        if (classKey.isEmpty() || nodeKey.isEmpty()) {
            return false;
        }
        Set<String> nodes = allocatedClassNodes.get(classKey);
        return nodes != null && nodes.contains(nodeKey);
    }

    /**
     * 指定クラスのツリーノードを解放済みとして記録する（ポイント消費は行わない）。
     *
     * @return 新たに記録した場合は {@code true}
     */
    public synchronized boolean allocateClassNode(String classId, String nodeId) {
        String classKey = normalizeClassId(classId);
        String nodeKey = normalizeNodeId(nodeId);
        if (classKey.isEmpty() || nodeKey.isEmpty()) {
            return false;
        }
        Set<String> nodes = allocatedClassNodes.computeIfAbsent(
                classKey, ignored -> new LinkedHashSet<>());
        return nodes.add(nodeKey);
    }

    /**
     * 指定クラスの解放済みツリーノードをすべて破棄する（ポイント返還は行わない）。
     */
    public synchronized void clearAllocatedClassNodes(String classId) {
        String normalized = normalizeClassId(classId);
        if (!normalized.isEmpty()) {
            allocatedClassNodes.remove(normalized);
        }
    }

    /**
     * DB から復元したクラス別進行状況を反映する。
     */
    public synchronized void restoreClassProgress(String classLevelsJson,
                                                  String classExpsJson,
                                                  String classTreePointsJson,
                                                  String allocatedClassNodesJson) {
        classLevels.clear();
        classExps.clear();
        classTreePoints.clear();
        allocatedClassNodes.clear();

        ClassProgressJson.decodeIntMap(classLevelsJson)
                .forEach((key, value) -> classLevels.put(
                        normalizeClassId(key), Math.max(DEFAULT_CLASS_LEVEL, value)));
        ClassProgressJson.decodeLongMap(classExpsJson)
                .forEach((key, value) -> classExps.put(normalizeClassId(key), value));
        ClassProgressJson.decodeIntMap(classTreePointsJson)
                .forEach((key, value) -> classTreePoints.put(normalizeClassId(key), value));
        ClassProgressJson.decodeSetMap(allocatedClassNodesJson).forEach((key, value) -> {
            Set<String> normalized = new LinkedHashSet<>();
            for (String nodeId : value) {
                String nodeKey = normalizeNodeId(nodeId);
                if (!nodeKey.isEmpty()) {
                    normalized.add(nodeKey);
                }
            }
            if (!normalized.isEmpty()) {
                allocatedClassNodes.put(normalizeClassId(key), normalized);
            }
        });
    }

    /* ------------------------------------------------------------------
     * ギャザリング（採取）の進行状況（アカウント共通）
     * ------------------------------------------------------------------ */

    /**
     * 指定種別のギャザリングレベルを取得する。未進行の場合は 1。
     *
     * @param type ギャザリング種別
     * @return レベル（1 以上）
     */
    public synchronized int getGatheringLevel(GatheringType type) {
        if (type == null) {
            return DEFAULT_GATHERING_LEVEL;
        }
        return Math.max(DEFAULT_GATHERING_LEVEL,
                gatheringLevels.getOrDefault(type, DEFAULT_GATHERING_LEVEL));
    }

    /**
     * 指定種別のギャザリングレベルを設定する（1 未満は 1）。
     *
     * @param type  ギャザリング種別
     * @param level レベル
     */
    public synchronized void setGatheringLevel(GatheringType type, int level) {
        if (type != null) {
            gatheringLevels.put(type, Math.max(DEFAULT_GATHERING_LEVEL, level));
        }
    }

    /**
     * 指定種別のギャザリング経験値を取得する。未進行の場合は 0。
     *
     * @param type ギャザリング種別
     * @return 経験値（0 以上）
     */
    public synchronized long getGatheringExp(GatheringType type) {
        return type == null ? 0L : Math.max(0L, gatheringExps.getOrDefault(type, 0L));
    }

    /**
     * 指定種別のギャザリング経験値を設定する（0 未満は 0）。
     *
     * @param type ギャザリング種別
     * @param exp  経験値
     */
    public synchronized void setGatheringExp(GatheringType type, long exp) {
        if (type != null) {
            gatheringExps.put(type, Math.max(0L, exp));
        }
    }

    /**
     * DB から復元したギャザリング進行状況を反映する。
     *
     * @param gatheringLevelsJson レベル JSON
     * @param gatheringExpsJson   経験値 JSON
     */
    public synchronized void restoreGatheringProgress(String gatheringLevelsJson,
                                                      String gatheringExpsJson) {
        gatheringLevels.clear();
        gatheringExps.clear();
        GatheringJson.decodeLevels(gatheringLevelsJson).forEach(
                (type, level) -> gatheringLevels.put(type, Math.max(DEFAULT_GATHERING_LEVEL, level)));
        GatheringJson.decodeExps(gatheringExpsJson).forEach(
                (type, exp) -> gatheringExps.put(type, Math.max(0L, exp)));
    }

    /**
     * 復元した現在クラスの進行データが未作成ならレベル 1・経験値 0 で初期化する。
     */
    private void initializeCurrentClassProgress() {
        String classId = classData.currentClassIdOrNull();
        if (classId != null && !classId.isBlank()) {
            initializeClassProgress(classId);
        }
    }

    private static String normalizeClassId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeNodeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public synchronized String getName() {
        return name;
    }

    public synchronized void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /**
     * RPG ステータスを取得する。
     * <p>
     * 返される {@link PlayerStats} はプレイヤーデータが保持する実体であり、
     * 変更はそのまま {@link #snapshot()} に反映される。WalkSpeed などの
     * ゲーム内への適用は {@code PlayerStatsService#apply(Player)} を呼び出すこと。
     *
     * @return RPG ステータス
     */
    public PlayerStats getStats() {
        return stats;
    }

    /**
     * スキルクールダウン終了時刻のシリアライズ文字列を取得する。
     */
    public synchronized String getCooldownEndAt() {
        return cooldownEndAt;
    }

    /**
     * スキルクールダウン終了時刻のシリアライズ文字列を設定する。
     *
     * @param cooldownEndAt シリアライズ文字列（{@code null} は空として扱う）
     */
    public synchronized void setCooldownEndAt(String cooldownEndAt) {
        this.cooldownEndAt = cooldownEndAt == null ? "" : cooldownEndAt;
    }

    public synchronized long getMoney() {
        return money;
    }

    public synchronized void setMoney(long money) {
        this.money = money;
    }

}
