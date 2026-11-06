package com.zeloria.mob;

/**
 * スポーン済みカスタムモブの戦闘ステータス。
 * <p>
 * {@link MobData} は YAML の定義値だが、本クラスは実際にワールドへ存在する
 * 1 体ごとの計算用ステータスを表す。防御力はプレイヤーと同じ 2 段階方式で、
 * {@code defense1}（バフ・スキル用）と {@code defense2}（防具用）に分かれる。
 * <p>
 * HP は {@link org.bukkit.entity.LivingEntity#getHealth()} を正とし、本クラスでは
 * 最大 HP と攻撃・防御ステータスのみを保持する。
 *
 * @param id              モブ ID
 * @param maxHealth       最大 HP
 * @param defense1        第 1 段階の防御力
 * @param defense2        第 2 段階の防御力（防具相当）
 * @param attackPower     攻撃力
 * @param criticalRate    クリティカル率（%）
 * @param criticalDamage  クリティカルダメージ（% 加算。50.0 = +50%）
 * @param accuracy        命中率（%）
 * @param evasion         回避率（%）
 */
public record MobStats(
        String id,
        double maxHealth,
        double defense1,
        double defense2,
        double attackPower,
        double criticalRate,
        double criticalDamage,
        double accuracy,
        double evasion
) {

    /** 既定の攻撃力。 */
    public static final double DEFAULT_ATTACK_POWER = 2.0;

    /** 既定のクリティカル率（%）。 */
    public static final double DEFAULT_CRITICAL_RATE = 5.0;

    /** 既定のクリティカルダメージ（% 加算）。 */
    public static final double DEFAULT_CRITICAL_DAMAGE = 50.0;

    /** 既定の命中率（%）。 */
    public static final double DEFAULT_ACCURACY = 90.0;

    /** 既定の回避率（%）。 */
    public static final double DEFAULT_EVASION = 0.0;

    /**
     * レベル 1 を基準とした 1 レベルあたりのステータス増加倍率。
     * <p>
     * 倍率は {@code 1 + 0.15 × (レベル - 1)} の単利計算で、
     * レベル 2 で 1.15 倍、レベル 3 で 1.30 倍となる。
     */
    public static final double LEVEL_SCALING_PER_LEVEL = 0.15;

    public MobStats {
        id = id == null ? "" : id;
        maxHealth = Math.max(1.0, maxHealth);
        defense1 = Math.max(0.0, defense1);
        defense2 = Math.max(0.0, defense2);
        attackPower = Math.max(0.0, attackPower);
        criticalRate = clampPercent(criticalRate);
        criticalDamage = Math.max(0.0, criticalDamage);
        accuracy = clampPercent(accuracy);
        evasion = clampPercent(evasion);
    }

    /**
     * {@link MobData} の定義値から実行時ステータスを生成する。
     * <p>
     * レベルは {@link MobData#level()} を使用する。
     *
     * @param data モブ定義
     * @return 実行時ステータス
     */
    public static MobStats from(MobData data) {
        return from(data, data == null ? 1 : data.level());
    }

    /**
     * {@link MobData} の定義値とレベルから実行時ステータスを生成する。
     * <p>
     * 最大 HP・防御力・攻撃力は {@link #levelFactor(int)} 倍され、
     * 確率系ステータス（クリティカル率・命中率・回避率）は定義値のまま使用する。
     *
     * @param data  モブ定義
     * @param level モブレベル（1 未満は 1 として扱う）
     * @return 実行時ステータス
     */
    public static MobStats from(MobData data, int level) {
        if (data == null) {
            return new MobStats("", 1.0, 0.0, 0.0, DEFAULT_ATTACK_POWER,
                    DEFAULT_CRITICAL_RATE, DEFAULT_CRITICAL_DAMAGE,
                    DEFAULT_ACCURACY, DEFAULT_EVASION);
        }

        double factor = levelFactor(level);
        return new MobStats(
                data.id(),
                (data.maxHp() > 0.0 ? data.maxHp() : 1.0) * factor,
                0.0,
                data.defense() * factor,
                data.attackPower() * factor,
                data.criticalRate(),
                data.criticalDamage(),
                data.accuracy(),
                data.evasion());
    }

    /**
     * レベルに応じたステータス倍率を返す（{@code 1 + 0.15 × (レベル - 1)}）。
     *
     * @param level モブレベル（1 未満は 1 として扱う）
     */
    public static double levelFactor(int level) {
        int normalized = Math.max(1, level);
        return 1.0 + LEVEL_SCALING_PER_LEVEL * (normalized - 1);
    }

    private static double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
