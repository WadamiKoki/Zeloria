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
     * 基準レベル（{@link MobData#baseLevel()}）との差 1 レベルあたりのステータス補正率。
     * <p>
     * 倍率は {@code 1 + 0.03 × (スポーンレベル - 基準レベル)} の単利計算で、
     * 基準レベル +3 で 1.09 倍、基準レベル -4 で 0.88 倍となる。
     */
    public static final double LEVEL_SCALING_PER_LEVEL = 0.03;

    /** レベル補正の下限倍率（極端に低いレベル指定でも 0 以下にしない）。 */
    public static final double MIN_LEVEL_MULTIPLIER = 0.1;

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
     * {@link MobData} の定義値から基準レベル（{@link MobData#baseLevel()}）時の実行時ステータスを生成する。
     *
     * @param data モブ定義
     * @return 実行時ステータス
     */
    public static MobStats from(MobData data) {
        return from(data, data == null ? 1 : data.baseLevel());
    }

    /**
     * {@link MobData} の定義値とスポーンレベルから実行時ステータスを生成する。
     * <p>
     * レベル補正（{@link #levelMultiplier(int, int)}）は最大 HP・防御力・攻撃力・
     * クリティカルダメージにのみ適用し、確率系ステータス（クリティカル率・命中率・
     * 回避率）は定義値のまま使用する。
     *
     * @param data  モブ定義
     * @param level スポーンレベル（{@code baseLevel} との差で補正する）
     * @return 実行時ステータス
     */
    public static MobStats from(MobData data, int level) {
        if (data == null) {
            return new MobStats("", 1.0, 0.0, 0.0, DEFAULT_ATTACK_POWER,
                    DEFAULT_CRITICAL_RATE, DEFAULT_CRITICAL_DAMAGE,
                    DEFAULT_ACCURACY, DEFAULT_EVASION);
        }

        double factor = levelMultiplier(data.baseLevel(), level);
        return new MobStats(
                data.id(),
                (data.maxHp() > 0.0 ? data.maxHp() : 1.0) * factor,
                0.0,
                data.defense() * factor,
                data.attackPower() * factor,
                data.criticalRate(),
                data.criticalDamage() * factor,
                data.accuracy(),
                data.evasion());
    }

    /**
     * スポーンレベルと基準レベルの差に応じたステータス倍率を返す。
     * <p>
     * {@code 1.0 + 0.03 × (スポーンレベル - 基準レベル)}。
     * 下限は {@link #MIN_LEVEL_MULTIPLIER} でクランプする。
     *
     * @param baseLevel    ステータス定義の基準レベル（1 未満は 1 として扱う）
     * @param spawnedLevel スポーン時のレベル（1 未満は 1 として扱う）
     */
    public static double levelMultiplier(int baseLevel, int spawnedLevel) {
        int diff = Math.max(1, spawnedLevel) - Math.max(1, baseLevel);
        return Math.max(MIN_LEVEL_MULTIPLIER, 1.0 + LEVEL_SCALING_PER_LEVEL * diff);
    }

    private static double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
