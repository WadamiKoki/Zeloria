package com.zeloria.player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤーデータの不変スナップショット。
 * <p>
 * DB の {@code players} テーブル 1 行と 1 対 1 で対応し、
 * アイテム列は Base64 文字列として保持する。メインスレッドで {@link PlayerData#snapshot()}
 * により生成し、そのまま非同期スレッドで DB へ書き込むことで、
 * GUI 操作との競合を避けつつ完全非同期に保存できる。
 *
 * @param uuid                    プレイヤー UUID
 * @param playerName              プレイヤー名
 * @param money                   所持金
 * @param currentClassId         現在のクラス ID（未選択時は空文字）
 * @param unlockedClasses        解放済みクラス ID のカンマ区切り文字列
 * @param classLevelsJson        クラス ID → クラス別レベル（JSON）
 * @param classExpsJson          クラス ID → クラス別経験値（JSON）
 * @param classTreePointsJson    クラス ID → 未使用ツリーポイント（JSON）
 * @param allocatedClassNodesJson クラス ID → 解放済みツリーノード ID（JSON）
 * @param gatheringLevelsJson     ギャザリング種別 → レベル（JSON）
 * @param gatheringExpsJson       ギャザリング種別 → 経験値（JSON）
 * @param virtualEquipmentBase64  装備中アイテムの Base64 データ
 * @param virtualInventoryJson    保管庫タブ（JSON）
 * @param virtualEquipmentJson    装備保管庫タブ（JSON）
 * @param playerSettingsJson      プレイヤー設定（JSON）
 * @param maxHealth               最大 HP
 * @param currentHealth           現在 HP
 * @param maxMana                 最大 MP
 * @param currentMana             現在 MP
 * @param attackPower             攻撃力
 * @param defense1                第 1 段階の防御力（バフ・スキル用）
 * @param defense2                第 2 段階の防御力（防具用）
 * @param criticalRate            クリティカル率（%）
 * @param criticalDamage          クリティカルダメージ（% 加算。50.0 = +50%）
 * @param accuracy                命中率（%）
 * @param evasion                 回避率（%）
 * @param dodgeCooldownReduction  回避クールダウン短縮率（0.0〜0.5）
 * @param cooldownReduction       スキルクールタイム短縮率（0.0〜0.5）
 * @param walkSpeed               移動速度（WalkSpeed 比率。既定 0.2）
 * @param healthRegenFlat         ヘルス固定リジェネ（マイナス可）
 * @param healthRegenPercent      ヘルス割合リジェネ（比率。マイナス可）
 * @param manaRegenFlat           マナ固定リジェネ（マイナス可）
 * @param manaRegenPercent        マナ割合リジェネ（比率。マイナス可）
 * @param armorPenetration        防御貫通率（0.0〜1.0）
 * @param lifesteal               吸血率（0.0〜1.0）
 * @param cooldownEndAt           スキルクールダウン終了時刻のシリアライズ文字列
 */
public record PlayerSnapshot(
        UUID uuid,
        String playerName,
        long money,
        String currentClassId,
        String unlockedClasses,
        String classLevelsJson,
        String classExpsJson,
        String classTreePointsJson,
        String allocatedClassNodesJson,
        String gatheringLevelsJson,
        String gatheringExpsJson,
        String virtualEquipmentBase64,
        String virtualInventoryJson,
        String virtualEquipmentJson,
        String playerSettingsJson,
        double maxHealth,
        double currentHealth,
        double maxMana,
        double currentMana,
        double attackPower,
        double defense1,
        double defense2,
        double criticalRate,
        double criticalDamage,
        double accuracy,
        double evasion,
        double dodgeCooldownReduction,
        double cooldownReduction,
        double walkSpeed,
        double healthRegenFlat,
        double healthRegenPercent,
        double manaRegenFlat,
        double manaRegenPercent,
        double armorPenetration,
        double lifesteal,
        String cooldownEndAt
) {

    /** 既定の HP。 */
    public static final double DEFAULT_HP = 20.0;

    /** 既定の攻撃力。 */
    public static final double DEFAULT_ATK = 1.0;

    /** 既定のクリティカル率（%）。 */
    public static final double DEFAULT_CRIT_RATE = 5.0;

    /** 既定のクリティカルダメージ（% 加算。50.0 = +50%）。 */
    public static final double DEFAULT_CRIT_DMG = 50.0;

    /** 既定の移動速度（%）。 */
    public static final double DEFAULT_MOVE_SPEED = 100.0;

    public PlayerSnapshot {
        Objects.requireNonNull(uuid, "uuid");
        playerName = playerName == null ? "" : playerName;
        currentClassId = currentClassId == null ? "" : currentClassId;
        unlockedClasses = unlockedClasses == null ? "" : unlockedClasses;
        classLevelsJson = classLevelsJson == null || classLevelsJson.isBlank() ? "{}" : classLevelsJson;
        classExpsJson = classExpsJson == null || classExpsJson.isBlank() ? "{}" : classExpsJson;
        classTreePointsJson = classTreePointsJson == null || classTreePointsJson.isBlank() ? "{}" : classTreePointsJson;
        allocatedClassNodesJson = allocatedClassNodesJson == null || allocatedClassNodesJson.isBlank()
                ? "{}" : allocatedClassNodesJson;
        gatheringLevelsJson = blankToEmptyObject(gatheringLevelsJson);
        gatheringExpsJson = blankToEmptyObject(gatheringExpsJson);
        virtualEquipmentBase64 = virtualEquipmentBase64 == null ? InventorySerializer.EMPTY : virtualEquipmentBase64;
        virtualInventoryJson = blankToEmptyObject(virtualInventoryJson);
        virtualEquipmentJson = blankToEmptyObject(virtualEquipmentJson);
        playerSettingsJson = playerSettingsJson == null || playerSettingsJson.isBlank()
                ? SettingsJson.DEFAULT_JSON : playerSettingsJson;
        cooldownEndAt = cooldownEndAt == null ? "" : cooldownEndAt;
    }

    /**
     * 初回参加時の既定スナップショットを生成する。
     *
     * @param uuid プレイヤー UUID
     * @param name プレイヤー名
     * @return 既定値のみを持つスナップショット
     */
    public static PlayerSnapshot defaults(UUID uuid, String name) {
        return new PlayerSnapshot(
                uuid,
                name,
                0L,
                "",
                "",
                "{}",
                "{}",
                "{}",
                "{}",
                "{}",
                "{}",
                InventorySerializer.EMPTY,
                "{}",
                "{}",
                SettingsJson.DEFAULT_JSON,
                PlayerStats.DEFAULT_MAX_HEALTH,
                PlayerStats.DEFAULT_MAX_HEALTH,
                PlayerStats.DEFAULT_MAX_MANA,
                PlayerStats.DEFAULT_MAX_MANA,
                PlayerStats.DEFAULT_ATTACK_POWER,
                PlayerStats.DEFAULT_DEFENSE1,
                PlayerStats.DEFAULT_DEFENSE2,
                PlayerStats.DEFAULT_CRITICAL_RATE,
                PlayerStats.DEFAULT_CRITICAL_DAMAGE,
                PlayerStats.DEFAULT_ACCURACY,
                PlayerStats.DEFAULT_EVASION,
                PlayerStats.DEFAULT_DODGE_COOLDOWN_REDUCTION,
                PlayerStats.DEFAULT_COOLDOWN_REDUCTION,
                PlayerStats.DEFAULT_MOVE_SPEED,
                PlayerStats.DEFAULT_HEALTH_REGEN_FLAT,
                PlayerStats.DEFAULT_HEALTH_REGEN_PERCENT,
                PlayerStats.DEFAULT_MANA_REGEN_FLAT,
                PlayerStats.DEFAULT_MANA_REGEN_PERCENT,
                PlayerStats.DEFAULT_ARMOR_PENETRATION,
                PlayerStats.DEFAULT_LIFESTEAL,
                ""
        );
    }

    /**
     * プレイヤー名だけを差し替えたスナップショットを返す。
     *
     * @param name 新しいプレイヤー名
     * @return 名前を差し替えたコピー
     */
    public PlayerSnapshot withName(String name) {
        return new PlayerSnapshot(uuid, name, money,
                currentClassId, unlockedClasses,
                classLevelsJson, classExpsJson, classTreePointsJson, allocatedClassNodesJson,
                gatheringLevelsJson, gatheringExpsJson,
                virtualEquipmentBase64,
                virtualInventoryJson, virtualEquipmentJson, playerSettingsJson,
                maxHealth, currentHealth, maxMana, currentMana,
                attackPower, defense1, defense2, criticalRate, criticalDamage,
                accuracy, evasion, dodgeCooldownReduction, cooldownReduction,
                walkSpeed, healthRegenFlat, healthRegenPercent,
                manaRegenFlat, manaRegenPercent, armorPenetration, lifesteal,
                cooldownEndAt);
    }

    /**
     * 現在選択中のクラスのレベルを取得する。
     * <p>
     * 未選択の場合は 0、進行データが未保存のクラスは既定値の 1 を返す。
     *
     * @return 現在クラスのレベル
     */
    public int currentClassLevel() {
        String classId = normalizedCurrentClassId();
        if (classId.isEmpty()) {
            return 0;
        }
        Integer stored = ClassProgressJson.decodeIntMap(classLevelsJson).get(classId);
        return stored == null
                ? PlayerData.DEFAULT_CLASS_LEVEL
                : Math.max(PlayerData.DEFAULT_CLASS_LEVEL, stored);
    }

    /**
     * 現在選択中のクラスの経験値を取得する。
     *
     * @return 現在クラスの経験値。未選択の場合は 0
     */
    public long currentClassExp() {
        String classId = normalizedCurrentClassId();
        if (classId.isEmpty()) {
            return 0L;
        }
        Long stored = ClassProgressJson.decodeLongMap(classExpsJson).get(classId);
        return stored == null ? 0L : Math.max(0L, stored);
    }

    private String normalizedCurrentClassId() {
        return currentClassId == null ? "" : currentClassId.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToEmptyObject(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }
}
