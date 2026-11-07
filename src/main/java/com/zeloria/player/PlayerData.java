package com.zeloria.player;

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
 *     <li>基本情報: レベル・経験値・所持金</li>
 *     <li>RPG ステータス: {@link PlayerStats}（HP / MP・攻撃力・防御力・クリティカル・
 *         命中 / 回避・クールダウン短縮・移動速度・リジェネ）</li>
 *     <li>クラス状態: 現在のクラス・解放済みクラス</li>
 *     <li>仮想装備（{@link EquipSlot} ごとの {@link ItemStack}）</li>
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

    private UUID uuid;
    private String name = "";

    private long money;

    /** スキルクールダウン終了時刻（ログアウト時のみ保存するシリアライズ文字列）。 */
    private String cooldownEndAt = "";

    /** RPG ステータス（HP / MP・攻撃力・クリティカル・リジェネなど）。 */
    private final PlayerStats stats = new PlayerStats();

    /** 仮想装備（防具 4 部位 + メイン武器 + オフハンド）。 */
    private final Map<EquipSlot, ItemStack> virtualEquipment = new EnumMap<>(EquipSlot.class);

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
                stats.getLevel(),
                stats.getExp(),
                money,
                classData.currentClassIdOrNull(),
                classData.serializedUnlocked(),
                ClassProgressJson.encodeIntMap(classLevels),
                ClassProgressJson.encodeLongMap(classExps),
                ClassProgressJson.encodeIntMap(classTreePoints),
                ClassProgressJson.encodeSetMap(allocatedClassNodes),
                InventorySerializer.encodeEquipment(virtualEquipment),
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
        data.stats.setLevel(snapshot.level());
        data.stats.setExp(snapshot.exp());
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
        data.cooldownEndAt = snapshot.cooldownEndAt();

        data.classData.restore(snapshot.currentClassId(), snapshot.unlockedClasses());
        data.restoreClassProgress(
                snapshot.classLevelsJson(),
                snapshot.classExpsJson(),
                snapshot.classTreePointsJson(),
                snapshot.allocatedClassNodesJson());

        // 旧データ（クラス別進行が未保存）は、グローバルレベルを現在のクラスへ引き継ぐ。
        data.ensureClassProgress(snapshot.currentClassId());
        data.mirrorCurrentClassProgress();

        data.virtualEquipment.putAll(InventorySerializer.decodeEquipment(snapshot.virtualEquipmentBase64()));

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
        ItemStack item = virtualEquipment.get(slot);
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
            virtualEquipment.remove(slot);
        } else {
            virtualEquipment.put(slot, item.clone());
        }
    }

    /**
     * 仮想装備のコピーを取得する（空枠は含まない）。
     */
    public synchronized Map<EquipSlot, ItemStack> getVirtualEquipment() {
        Map<EquipSlot, ItemStack> copy = new EnumMap<>(EquipSlot.class);
        virtualEquipment.forEach((slot, item) -> {
            if (item != null && !item.isEmpty()) {
                copy.put(slot, item.clone());
            }
        });
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
     * 指定クラスのレベルを取得する。未進行の場合は 0。
     */
    public synchronized int getClassLevel(String classId) {
        String normalized = normalizeClassId(classId);
        return normalized.isEmpty() ? 0 : classLevels.getOrDefault(normalized, 0);
    }

    /**
     * 指定クラスのレベルを設定する。
     */
    public synchronized void setClassLevel(String classId, int level) {
        String normalized = normalizeClassId(classId);
        if (!normalized.isEmpty()) {
            classLevels.put(normalized, Math.max(0, level));
        }
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
                .forEach((key, value) -> classLevels.put(normalizeClassId(key), value));
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

    /**
     * クラス別進行状況が未作成の場合、現在のグローバルレベル・経験値を引き継ぐ。
     * <p>
     * クラス別進行の導入前に育成したプレイヤーが、クラス選択時にレベルを失わないための
     * 移行処理。既に何らかの進行が保存されているクラスには何もしない。
     */
    public synchronized void ensureClassProgress(String classId) {
        String normalized = normalizeClassId(classId);
        if (normalized.isEmpty()) {
            return;
        }
        if (classLevels.containsKey(normalized) || classExps.containsKey(normalized)) {
            return;
        }

        int level = Math.max(0, stats.getLevel());
        long exp = Math.max(0L, stats.getExp());
        if (level == 0 && exp == 0L) {
            return;
        }
        classLevels.put(normalized, level);
        classExps.put(normalized, exp);
    }

    /**
     * 現在選択中のクラスのレベル・経験値をグローバル値（{@link PlayerStats}）へ反映する。
     * <p>
     * レベル表示・必要経験値計算・既存のステータス計算は {@link PlayerStats#getLevel()}
     * を参照するため、クラス切り替え時にもこのメソッドで同期する。
     */
    public synchronized void mirrorCurrentClassProgress() {
        String classId = classData.currentClassIdOrNull();
        if (classId == null || classId.isEmpty()) {
            return;
        }
        stats.setLevel(getClassLevel(classId));
        stats.setExp(getClassExp(classId));
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

    public synchronized int getLevel() {
        return stats.getLevel();
    }

    public synchronized void setLevel(int level) {
        stats.setLevel(level);
    }

    public synchronized long getExp() {
        return stats.getExp();
    }

    public synchronized void setExp(long exp) {
        stats.setExp(exp);
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
     * 現在 HP を取得する（旧 API。{@link #getStats()} の現在 HP を返す）。
     */
    public synchronized double getHp() {
        return stats.getCurrentHealth();
    }

    /**
     * 現在 HP を設定する（旧 API。{@link PlayerStats#setCurrentHealth(double)} に委譲）。
     */
    public synchronized void setHp(double hp) {
        stats.setCurrentHealth(hp);
    }

    /**
     * 現在 MP を取得する（旧 API。{@link #getStats()} の現在 MP を返す）。
     */
    public synchronized double getMp() {
        return stats.getCurrentMana();
    }

    /**
     * 現在 MP を設定する（旧 API。{@link PlayerStats#setCurrentMana(double)} に委譲）。
     */
    public synchronized void setMp(double mp) {
        stats.setCurrentMana(mp);
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

    /**
     * 攻撃力を取得する（旧 API。{@link PlayerStats#getAttackPower()} に委譲）。
     */
    public synchronized double getAtk() {
        return stats.getAttackPower();
    }

    public synchronized void setAtk(double atk) {
        stats.setAttackPower(atk);
    }

    /**
     * 防御力を取得する（旧 API。{@link PlayerStats#getDefense()} に委譲）。
     */
    public synchronized double getDef() {
        return stats.getDefense();
    }

    public synchronized void setDef(double def) {
        stats.setDefense(def);
    }

    /**
     * クリティカル率（%）を取得する（旧 API）。
     */
    public synchronized double getCritRate() {
        return stats.getCriticalRate();
    }

    public synchronized void setCritRate(double critRate) {
        stats.setCriticalRate(critRate);
    }

    /**
     * クリティカルダメージ（% 加算）を取得する（旧 API）。
     */
    public synchronized double getCritDmg() {
        return stats.getCriticalDamage();
    }

    /**
     * クリティカルダメージ（% 加算）を設定する（旧 API）。
     */
    public synchronized void setCritDmg(double critDmg) {
        stats.setCriticalDamage(critDmg);
    }

    /**
     * クールダウン短縮率（%）を取得する（旧 API。内部値は比率のため 100 倍して返す）。
     */
    public synchronized double getCdr() {
        return stats.getCooldownReduction() * 100.0;
    }

    /**
     * クールダウン短縮率（%）を設定する（旧 API。内部値は比率のため 100 で割る）。
     */
    public synchronized void setCdr(double cdr) {
        stats.setCooldownReduction(cdr / 100.0);
    }

    /**
     * 移動速度（%）を取得する（旧 API。{@link PlayerStats#DEFAULT_MOVE_SPEED} を 100% として換算）。
     */
    public synchronized double getMoveSpeed() {
        return toLegacyMoveSpeedPercent(stats.getMoveSpeed());
    }

    /**
     * 移動速度（%）を設定する（旧 API。100% が {@link PlayerStats#DEFAULT_MOVE_SPEED} に相当）。
     */
    public synchronized void setMoveSpeed(double moveSpeed) {
        stats.setMoveSpeed(fromLegacyMoveSpeedPercent(moveSpeed));
    }

    /**
     * ヘルス固定リジェネを取得する（旧 API。{@link PlayerStats#getHealthRegenFlat()} に委譲）。
     */
    public synchronized double getHpRegen() {
        return stats.getHealthRegenFlat();
    }

    public synchronized void setHpRegen(double hpRegen) {
        stats.setHealthRegenFlat(hpRegen);
    }

    /**
     * マナ固定リジェネを取得する（旧 API）。
     */
    public synchronized double getMpRegen() {
        return stats.getManaRegenFlat();
    }

    public synchronized void setMpRegen(double mpRegen) {
        stats.setManaRegenFlat(mpRegen);
    }

    /**
     * WalkSpeed の比率を旧 API のパーセント表記（既定速度 = 100%）へ換算する。
     */
    private static double toLegacyMoveSpeedPercent(double walkSpeed) {
        return walkSpeed / PlayerStats.DEFAULT_MOVE_SPEED * 100.0;
    }

    /**
     * 旧 API のパーセント表記（既定速度 = 100%）を WalkSpeed の比率へ換算する。
     */
    private static double fromLegacyMoveSpeedPercent(double percent) {
        return percent / 100.0 * PlayerStats.DEFAULT_MOVE_SPEED;
    }
}
