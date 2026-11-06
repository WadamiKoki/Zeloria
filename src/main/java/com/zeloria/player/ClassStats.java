package com.zeloria.player;

import org.bukkit.configuration.ConfigurationSection;

/**
 * クラス定義の基礎ステータス／レベルアップ成長率を保持する不変モデル。
 * <p>
 * {@code ClassData/<class>.yml} の {@code baseStats} と {@code levelUpPercent} を
 * そのまま読み込み、{@link #atLevel(ClassStats, ClassStats, int)} で
 * 「基礎値 × (1 + 成長率[%] / 100 × (レベル - 1))」を算出する（単利成長）。
 * <p>
 * レベル 0（新規プレイヤー）とレベル 1 は基礎値のみを返す。レベル 0 で
 * 成長率を適用するとステータスが負になるため、成長回数は
 * {@code max(0, level - 1)} として扱う。
 * <p>
 * 成長率は「基礎値に対する割合（%）」であるため、基礎値が 0 の項目は
 * 何レベルになっても 0 のままとなる。
 * <p>
 * ここで扱う値はプレイヤーの基礎値であり、装備補正（{@link PlayerStats.Bonuses}）
 * は {@link PlayerStats} 側で加算される。
 *
 * @param maxHealth              最大 HP
 * @param maxMana                最大 MP
 * @param attackPower            攻撃力
 * @param defense1               防御力 1（バフ・スキル用）
 * @param defense2               防御力 2（防具用）
 * @param criticalRate           クリティカル率（%）
 * @param criticalDamage         クリティカルダメージ（% 加算）
 * @param accuracy               命中率（%）
 * @param evasion                回避率（%）
 * @param dodgeCooldownReduction 回避クールダウン短縮率（0.0〜0.5）
 * @param cooldownReduction      スキルクールダウン短縮率（0.0〜0.5）
 * @param moveSpeed              移動速度（WalkSpeed）
 * @param healthRegenFlat        ヘルス固定リジェネ
 * @param healthRegenPercent     ヘルス割合リジェネ
 * @param manaRegenFlat          マナ固定リジェネ
 * @param manaRegenPercent       マナ割合リジェネ
 * @param armorPenetration       防御貫通率（0.0〜1.0）
 * @param lifesteal              吸血率（0.0〜1.0）
 */
public record ClassStats(
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
        double lifesteal
) {

    /** すべて 0 の成長値。 */
    public static final ClassStats NONE = new ClassStats(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    /**
     * YAML に {@code baseStats} が存在しない場合の既定値。
     * <p>
     * {@link PlayerStats} の既定値と揃え、クラス未選択のプレイヤーの挙動を
     * 従来どおりに保つ。
     */
    public static final ClassStats DEFAULT = new ClassStats(
            PlayerStats.DEFAULT_MAX_HEALTH,
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
            0.0,
            0.0);

    /**
     * 2 つの値を加算する。
     *
     * @param other 加算する値（{@code null} は加算なし）
     * @return 加算結果
     */
    public ClassStats plus(ClassStats other) {
        if (other == null) {
            return this;
        }
        return new ClassStats(
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
                lifesteal + other.lifesteal);
    }

    /**
     * すべての値を指定倍率する。
     *
     * @param factor 倍率
     * @return 倍率適用後の値
     */
    public ClassStats multiply(double factor) {
        if (factor == 1.0) {
            return this;
        }
        return new ClassStats(
                maxHealth * factor,
                maxMana * factor,
                attackPower * factor,
                defense1 * factor,
                defense2 * factor,
                criticalRate * factor,
                criticalDamage * factor,
                accuracy * factor,
                evasion * factor,
                dodgeCooldownReduction * factor,
                cooldownReduction * factor,
                moveSpeed * factor,
                healthRegenFlat * factor,
                healthRegenPercent * factor,
                manaRegenFlat * factor,
                manaRegenPercent * factor,
                armorPenetration * factor,
                lifesteal * factor);
    }

    /**
     * 指定レベルでの実効基礎値を算出する（単利成長）。
     * <p>
     * {@code baseStats × (1 + levelUpPercent / 100 × (level - 1))}。
     * レベル 0 と 1 は基礎値のみを返す。
     *
     * @param baseStats      基礎ステータス（{@code null} は {@link #DEFAULT}）
     * @param levelUpPercent レベルアップ成長率（%／レベル、{@code null} は {@link #NONE}）
     * @param level          レベル
     * @return 実効基礎値
     */
    public static ClassStats atLevel(ClassStats baseStats, ClassStats levelUpPercent, int level) {
        ClassStats base = baseStats == null ? DEFAULT : baseStats;
        int growthLevels = Math.max(0, level - 1);
        if (levelUpPercent == null || growthLevels == 0) {
            return base;
        }
        return base.grownByPercent(levelUpPercent, growthLevels);
    }

    /**
     * 各項目を「この値 × (1 + 成長率[%] / 100 × 成長回数)」へ単利で成長させる。
     *
     * @param percentPerLevel 1 レベルあたりの成長率（%、{@code null} は変化なし）
     * @param growthLevels    成長回数（0 以下は変化なし）
     * @return 成長後の値
     */
    public ClassStats grownByPercent(ClassStats percentPerLevel, int growthLevels) {
        if (percentPerLevel == null || growthLevels <= 0) {
            return this;
        }
        return new ClassStats(
                scale(maxHealth, percentPerLevel.maxHealth, growthLevels),
                scale(maxMana, percentPerLevel.maxMana, growthLevels),
                scale(attackPower, percentPerLevel.attackPower, growthLevels),
                scale(defense1, percentPerLevel.defense1, growthLevels),
                scale(defense2, percentPerLevel.defense2, growthLevels),
                scale(criticalRate, percentPerLevel.criticalRate, growthLevels),
                scale(criticalDamage, percentPerLevel.criticalDamage, growthLevels),
                scale(accuracy, percentPerLevel.accuracy, growthLevels),
                scale(evasion, percentPerLevel.evasion, growthLevels),
                scale(dodgeCooldownReduction, percentPerLevel.dodgeCooldownReduction, growthLevels),
                scale(cooldownReduction, percentPerLevel.cooldownReduction, growthLevels),
                scale(moveSpeed, percentPerLevel.moveSpeed, growthLevels),
                scale(healthRegenFlat, percentPerLevel.healthRegenFlat, growthLevels),
                scale(healthRegenPercent, percentPerLevel.healthRegenPercent, growthLevels),
                scale(manaRegenFlat, percentPerLevel.manaRegenFlat, growthLevels),
                scale(manaRegenPercent, percentPerLevel.manaRegenPercent, growthLevels),
                scale(armorPenetration, percentPerLevel.armorPenetration, growthLevels),
                scale(lifesteal, percentPerLevel.lifesteal, growthLevels));
    }

    /**
     * 単利成長の計算（{@code value × (1 + percent / 100 × levels)}）。
     */
    private static double scale(double value, double percentPerLevel, int growthLevels) {
        if (percentPerLevel == 0.0 || value == 0.0) {
            return value;
        }
        return value * (1.0 + (percentPerLevel / 100.0) * growthLevels);
    }

    /**
     * キー名（YAML の項目名）から値を取得する。
     * <p>
     * GUI などで {@code levelUpPercent} の値を表示する際に使用する。
     *
     * @param key 項目名（例: {@code maxHealth}）
     * @return 該当する値。未知のキーは {@code 0.0}
     */
    public double valueOfKey(String key) {
        if (key == null) {
            return 0.0;
        }
        return switch (key.trim()) {
            case "maxHealth" -> maxHealth;
            case "maxMana" -> maxMana;
            case "attackPower" -> attackPower;
            case "defense1" -> defense1;
            case "defense2" -> defense2;
            case "criticalRate" -> criticalRate;
            case "criticalDamage" -> criticalDamage;
            case "accuracy" -> accuracy;
            case "evasion" -> evasion;
            case "dodgeCooldownReduction" -> dodgeCooldownReduction;
            case "cooldownReduction" -> cooldownReduction;
            case "moveSpeed" -> moveSpeed;
            case "healthRegenFlat" -> healthRegenFlat;
            case "healthRegenPercent" -> healthRegenPercent;
            case "manaRegenFlat" -> manaRegenFlat;
            case "manaRegenPercent" -> manaRegenPercent;
            case "armorPenetration" -> armorPenetration;
            case "lifesteal" -> lifesteal;
            default -> 0.0;
        };
    }

    /**
     * YAML セクションから読み込む。
     * <p>
     * セクションが存在しない場合は {@code fallback} を返し、個別キーが
     * 存在しない場合は {@code fallback} の同項目を既定値として使用する。
     *
     * @param section  {@code baseStats} または {@code levelUpPercent}
     * @param fallback 既定値
     * @return 読み込んだ値
     */
    public static ClassStats fromConfig(ConfigurationSection section, ClassStats fallback) {
        ClassStats defaults = fallback == null ? NONE : fallback;
        if (section == null) {
            return defaults;
        }
        return new ClassStats(
                section.getDouble("maxHealth", defaults.maxHealth),
                section.getDouble("maxMana", defaults.maxMana),
                section.getDouble("attackPower", defaults.attackPower),
                section.getDouble("defense1", defaults.defense1),
                section.getDouble("defense2", defaults.defense2),
                section.getDouble("criticalRate", defaults.criticalRate),
                section.getDouble("criticalDamage", defaults.criticalDamage),
                section.getDouble("accuracy", defaults.accuracy),
                section.getDouble("evasion", defaults.evasion),
                section.getDouble("dodgeCooldownReduction", defaults.dodgeCooldownReduction),
                section.getDouble("cooldownReduction", defaults.cooldownReduction),
                section.getDouble("moveSpeed", defaults.moveSpeed),
                section.getDouble("healthRegenFlat", defaults.healthRegenFlat),
                section.getDouble("healthRegenPercent", defaults.healthRegenPercent),
                section.getDouble("manaRegenFlat", defaults.manaRegenFlat),
                section.getDouble("manaRegenPercent", defaults.manaRegenPercent),
                section.getDouble("armorPenetration", defaults.armorPenetration),
                section.getDouble("lifesteal", defaults.lifesteal));
    }

    /**
     * この値をプレイヤーの基礎ステータスへ反映する。
     * <p>
     * 装備補正は {@link PlayerStats#applyBonuses(PlayerStats.Bonuses)} 側で
     * 別途保持されるため、ここでは基礎値のみを上書きする。
     *
     * @param stats 対象ステータス（{@code null} は何もしない）
     */
    public void applyTo(PlayerStats stats) {
        if (stats == null) {
            return;
        }
        stats.setMaxHealth(maxHealth);
        stats.setMaxMana(maxMana);
        stats.setAttackPower(attackPower);
        stats.setDefense1(defense1);
        stats.setDefense2(defense2);
        stats.setCriticalRate(criticalRate);
        stats.setCriticalDamage(criticalDamage);
        stats.setAccuracy(accuracy);
        stats.setEvasion(evasion);
        stats.setDodgeCooldownReduction(dodgeCooldownReduction);
        stats.setCooldownReduction(cooldownReduction);
        stats.setMoveSpeed(moveSpeed);
        stats.setHealthRegenFlat(healthRegenFlat);
        stats.setHealthRegenPercent(healthRegenPercent);
        stats.setManaRegenFlat(manaRegenFlat);
        stats.setManaRegenPercent(manaRegenPercent);
        stats.setArmorPenetration(armorPenetration);
        stats.setLifesteal(lifesteal);
    }
}
