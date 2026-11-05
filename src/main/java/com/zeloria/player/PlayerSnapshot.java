package com.zeloria.player;

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
 * @param level                   レベル
 * @param exp                     経験値
 * @param hp                      HP（旧カラム互換。現在 HP と同値）
 * @param mp                      MP（旧カラム互換。現在 MP と同値）
 * @param money                   所持金
 * @param atk                     攻撃力
 * @param def                     防御力
 * @param critRate                クリティカル率（%）
 * @param critDmg                 クリティカルダメージ（%）
 * @param cdr                     クールダウン短縮率（%）
 * @param moveSpeed               移動速度（%）
 * @param hpRegen                 HP 自然回復量（旧カラム互換。固定リジェネと同値）
 * @param mpRegen                 MP 自然回復量（旧カラム互換。固定リジェネと同値）
 * @param currentClassId         現在のクラス ID（未選択時は空文字）
 * @param unlockedClasses        解放済みクラス ID のカンマ区切り文字列
 * @param virtualEquipmentBase64  仮想装備の Base64 データ
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
 * @param cooldownEndAt           スキルクールダウン終了時刻のシリアライズ文字列
 */
public record PlayerSnapshot(
        UUID uuid,
        String playerName,
        int level,
        long exp,
        double hp,
        double mp,
        long money,
        double atk,
        double def,
        double critRate,
        double critDmg,
        double cdr,
        double moveSpeed,
        double hpRegen,
        double mpRegen,
        String currentClassId,
        String unlockedClasses,
        String virtualEquipmentBase64,
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
        String cooldownEndAt
) {

    /** 既定のレベル。 */
    public static final int DEFAULT_LEVEL = 0;

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
        virtualEquipmentBase64 = virtualEquipmentBase64 == null ? InventorySerializer.EMPTY : virtualEquipmentBase64;
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
                DEFAULT_LEVEL,
                0L,
                DEFAULT_HP,
                0.0,
                0L,
                DEFAULT_ATK,
                0.0,
                DEFAULT_CRIT_RATE,
                DEFAULT_CRIT_DMG,
                0.0,
                DEFAULT_MOVE_SPEED,
                0.0,
                0.0,
                "",
                "",
                InventorySerializer.EMPTY,
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
        return new PlayerSnapshot(uuid, name, level, exp, hp, mp, money, atk, def,
                critRate, critDmg, cdr, moveSpeed, hpRegen, mpRegen,
                currentClassId, unlockedClasses, virtualEquipmentBase64,
                maxHealth, currentHealth, maxMana, currentMana,
                attackPower, defense1, defense2, criticalRate, criticalDamage,
                accuracy, evasion, dodgeCooldownReduction, cooldownReduction,
                walkSpeed, healthRegenFlat, healthRegenPercent,
                manaRegenFlat, manaRegenPercent, cooldownEndAt);
    }
}
