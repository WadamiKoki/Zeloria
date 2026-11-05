package com.zeloria.item;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 武器・防具 YAML のステータス補正ブロック。
 * <p>
 * {@code stats:} セクション配下、またはルート直下のどちらでも記述できる。
 * キーはテンプレートで読みやすい camelCase を基本とし、既存設定との互換のため
 * snake_case（{@code max_health} など）も受け付ける。
 * 値は GUI / Lore 表示と同じ単位（会心率は %、CT 短縮は %、移動速度は %、
 * 防御貫通率・吸血率は %）で保持する。
 * <pre>
 * requiredLevel: 5
 * stats:
 *   maxHealth: 10
 *   attackPower: 5
 *   defense1: 2
 *   defense2: 4
 *   criticalRate: 3
 *   criticalDamage: 20
 *   healthRegenFlat: 0.5
 *   moveSpeed: 2
 * </pre>
 *
 * @param maxHealth              最大 HP 補正
 * @param maxMana                最大 MP 補正
 * @param attackPower            攻撃力補正
 * @param defense1               第 1 段階の防御力補正（防御貫通の影響を受けない）
 * @param defense2               第 2 段階の防御力補正（防具用。防御貫通の影響を受ける）
 * @param criticalRate           会心率補正（%）
 * @param criticalDamage         会心ダメージ補正（%）
 * @param accuracy               命中率補正（%）
 * @param evasion                回避率補正（%）
 * @param dodgeCooldownReduction 回避 CT 短縮補正（%）
 * @param cooldownReduction      スキル CT 短縮補正（%）
 * @param moveSpeed              移動速度補正（%）
 * @param healthRegenFlat        HP 固定リジェネ補正
 * @param healthRegenPercent     HP 割合リジェネ補正（%）
 * @param manaRegenFlat          MP 固定リジェネ補正
 * @param manaRegenPercent       MP 割合リジェネ補正（%）
 * @param armorPenetration       防御貫通率補正（%）
 * @param lifesteal              与ダメージに対する吸血率補正（%）
 * @param requiredLevel          装着に必要な最低レベル
 */
public record ItemStatBonus(
        double maxHealth,
        double maxMana,
        double attackPower,
        double defense1,
        double defense2,
        double criticalRate,
        double criticalDamage,
        double accuracy,
        double evasion,
        double dodgeCooldownReduction,
        double cooldownReduction,
        double moveSpeed,
        double healthRegenFlat,
        double healthRegenPercent,
        double manaRegenFlat,
        double manaRegenPercent,
        double armorPenetration,
        double lifesteal,
        int requiredLevel
) {

    /** 補正なし・レベル制限なし。 */
    public static final ItemStatBonus NONE = new ItemStatBonus(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0);

    public ItemStatBonus {
        requiredLevel = Math.max(0, requiredLevel);
    }

    /**
     * YAML 設定からステータス補正を読み込む。
     *
     * @param config アイテム設定
     * @return 読み込んだ補正値
     */
    public static ItemStatBonus fromConfig(FileConfiguration config) {
        if (config == null) {
            return NONE;
        }
        return new ItemStatBonus(
                stat(config, "maxHealth", "max_health", "max_hp"),
                stat(config, "maxMana", "max_mana", "max_mp"),
                stat(config, "attackPower", "attack_power", "atk", "attack"),
                stat(config, "defense1", "defense_1"),
                stat(config, "defense2", "defense_2", "defense", "def"),
                stat(config, "criticalRate", "crit_rate", "critical_rate"),
                stat(config, "criticalDamage", "crit_dmg", "critical_damage"),
                stat(config, "accuracy", "hit"),
                stat(config, "evasion", "dodge"),
                stat(config, "dodgeCooldownReduction", "dodge_cdr", "dodge_cooldown_reduction"),
                stat(config, "cooldownReduction", "cdr", "cooldown_reduction"),
                stat(config, "moveSpeed", "move_speed", "walk_speed"),
                stat(config, "healthRegenFlat", "hp_regen", "health_regen_flat"),
                stat(config, "healthRegenPercent", "hp_regen_percent", "health_regen_percent"),
                stat(config, "manaRegenFlat", "mp_regen", "mana_regen_flat"),
                stat(config, "manaRegenPercent", "mp_regen_percent", "mana_regen_percent"),
                stat(config, "armorPenetration", "armor_penetration"),
                stat(config, "lifesteal", "life_steal"),
                requiredLevel(config));
    }

    /**
     * 第 2 段階の防御力（旧 API 互換）。{@link #defense2()} と同じ値を返す。
     */
    public double defense() {
        return defense2;
    }

    /**
     * いずれかのステータス補正が設定されているか判定する。
     *
     * @return 1 項目でも 0 以外なら {@code true}
     */
    public boolean hasStats() {
        return maxHealth != 0.0 || maxMana != 0.0 || attackPower != 0.0
                || defense1 != 0.0 || defense2 != 0.0
                || criticalRate != 0.0 || criticalDamage != 0.0
                || accuracy != 0.0 || evasion != 0.0
                || dodgeCooldownReduction != 0.0 || cooldownReduction != 0.0
                || moveSpeed != 0.0 || healthRegenFlat != 0.0
                || healthRegenPercent != 0.0 || manaRegenFlat != 0.0
                || manaRegenPercent != 0.0 || armorPenetration != 0.0
                || lifesteal != 0.0;
    }

    /**
     * 2 つの補正値を加算する。必要レベルは高い方を採用する。
     *
     * @param other 加算する補正値
     * @return 加算後の補正値
     */
    public ItemStatBonus plus(ItemStatBonus other) {
        if (other == null || other == NONE) {
            return this;
        }
        return new ItemStatBonus(
                maxHealth + other.maxHealth,
                maxMana + other.maxMana,
                attackPower + other.attackPower,
                defense1 + other.defense1,
                defense2 + other.defense2,
                criticalRate + other.criticalRate,
                criticalDamage + other.criticalDamage,
                accuracy + other.accuracy,
                evasion + other.evasion,
                dodgeCooldownReduction + other.dodgeCooldownReduction,
                cooldownReduction + other.cooldownReduction,
                moveSpeed + other.moveSpeed,
                healthRegenFlat + other.healthRegenFlat,
                healthRegenPercent + other.healthRegenPercent,
                manaRegenFlat + other.manaRegenFlat,
                manaRegenPercent + other.manaRegenPercent,
                armorPenetration + other.armorPenetration,
                lifesteal + other.lifesteal,
                Math.max(requiredLevel, other.requiredLevel));
    }

    /**
     * {@code requiredLevel} / {@code required_level} を読み込む。
     * <p>
     * ルート直下を優先し、無ければ {@code stats:} 配下も参照する。
     */
    private static int requiredLevel(FileConfiguration config) {
        for (String key : new String[]{"requiredLevel", "required_level"}) {
            if (config.contains(key)) {
                return Math.max(0, config.getInt(key, 0));
            }
            if (config.contains("stats." + key)) {
                return Math.max(0, config.getInt("stats." + key, 0));
            }
        }
        return 0;
    }

    /**
     * {@code stats.<key>} を優先し、無ければルート直下の同名キーを読む。
     */
    private static double stat(FileConfiguration config, String... keys) {
        for (String key : keys) {
            if (config.contains("stats." + key)) {
                return config.getDouble("stats." + key, 0.0);
            }
            if (config.contains(key)) {
                return config.getDouble(key, 0.0);
            }
        }
        return 0.0;
    }
}
