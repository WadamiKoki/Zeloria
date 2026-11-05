package com.zeloria.skill;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code config/LevelData/level_settings.yml} から読み込んだレベル・経験値の設定。
 * <p>
 * 必要経験値は「基準値 × 成長率 ^ レベル × 段階倍率」で算出する。
 * 段階（ステージ）は {@code min_level}〜{@code max_level} の範囲ごとに
 * {@code exp_multiplier} を持ち、レベル帯ごとに要求量を調整できる。
 * <pre>
 * base_exp: 100
 * exp_growth: 1.15
 * stages:
 *   - min_level: 0
 *     max_level: 9
 *     exp_multiplier: 1.0
 *   - min_level: 10
 *     max_level: 29
 *     exp_multiplier: 1.2
 * </pre>
 * {@code max_level} に {@code -1} を指定した段階は上限なしとして扱う。
 * 段階が 1 つも無い場合は倍率 1.0・レベル上限なしで動作する。
 */
public final class LevelSettings {

    /** 既定の基準経験値。 */
    public static final long DEFAULT_BASE_EXP = 100L;

    /** 既定のレベルごとの成長率。 */
    public static final double DEFAULT_EXP_GROWTH = 1.15;

    /** 上限なしを表す {@code max_level} の値。 */
    public static final int UNLIMITED = -1;

    /** 設定が読み込めなかった場合に使用する既定値。 */
    public static final LevelSettings DEFAULT =
            new LevelSettings(DEFAULT_BASE_EXP, DEFAULT_EXP_GROWTH, List.of());

    /**
     * レベル帯ごとの経験値倍率。
     *
     * @param minLevel      開始レベル（含む）
     * @param maxLevel      終了レベル（含む）。{@link #UNLIMITED} は上限なし
     * @param expMultiplier 必要経験値の倍率
     */
    public record Stage(int minLevel, int maxLevel, double expMultiplier) {

        public Stage {
            minLevel = Math.max(0, minLevel);
            maxLevel = maxLevel == UNLIMITED ? UNLIMITED : Math.max(minLevel, maxLevel);
            expMultiplier = expMultiplier <= 0.0 ? 1.0 : expMultiplier;
        }

        /**
         * 指定レベルがこの段階に含まれるか判定する。
         *
         * @param level 判定するレベル
         * @return 含まれる場合は {@code true}
         */
        public boolean contains(int level) {
            return level >= minLevel && (maxLevel == UNLIMITED || level <= maxLevel);
        }
    }

    private final long baseExp;
    private final double expGrowth;
    private final List<Stage> stages;
    private final int maxLevel;

    public LevelSettings(long baseExp, double expGrowth, List<Stage> stages) {
        this.baseExp = Math.max(1L, baseExp);
        this.expGrowth = expGrowth <= 0.0 ? DEFAULT_EXP_GROWTH : expGrowth;
        this.stages = stages == null
                ? List.of()
                : List.copyOf(new ArrayList<>(stages));
        this.maxLevel = resolveMaxLevel(this.stages);
    }

    /**
     * YAML 設定から読み込む。
     *
     * @param config 設定（{@code null} の場合は {@link #DEFAULT}）
     * @return 読み込んだ設定
     */
    public static LevelSettings fromConfig(FileConfiguration config) {
        if (config == null) {
            return DEFAULT;
        }

        List<Stage> parsed = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("stages")) {
            parsed.add(new Stage(
                    intValue(raw.get("min_level"), 0),
                    intValue(raw.get("max_level"), 0),
                    doubleValue(raw.get("exp_multiplier"), 1.0)));
        }
        // 範囲が重複した場合は、より狭い（min_level が大きい）段階を優先する。
        parsed.sort(Comparator.comparingInt(Stage::minLevel));

        return new LevelSettings(
                config.getLong("base_exp", DEFAULT_BASE_EXP),
                config.getDouble("exp_growth", DEFAULT_EXP_GROWTH),
                parsed);
    }

    public long baseExp() {
        return baseExp;
    }

    public double expGrowth() {
        return expGrowth;
    }

    public List<Stage> stages() {
        return stages;
    }

    /**
     * 到達可能な最大レベルを返す。上限がない場合は {@link Integer#MAX_VALUE}。
     */
    public int maxLevel() {
        return maxLevel;
    }

    /**
     * 指定レベルの必要経験値を算出する。
     * <p>
     * レベル 0 でも必ず 1 以上を返す（新規プレイヤーが即座にレベルアップしないよう
     * 基準値の下限を保証する）。
     *
     * @param level レベル（0 未満は 0 として扱う）
     * @return 次のレベルへ上がるために必要な経験値
     */
    public long requiredExp(int level) {
        int clamped = Math.max(0, level);
        double multiplier = multiplierFor(clamped);
        double value = baseExp * Math.pow(expGrowth, clamped) * multiplier;
        if (!Double.isFinite(value)) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, Math.round(value));
    }

    /**
     * 指定レベルに適用する経験値倍率を返す。
     *
     * @param level レベル
     * @return 該当段階の倍率。該当なしの場合は 1.0
     */
    public double multiplierFor(int level) {
        Stage matched = null;
        for (Stage stage : stages) {
            if (stage.contains(level)) {
                matched = stage;
            }
        }
        return matched == null ? 1.0 : matched.expMultiplier();
    }

    private static int resolveMaxLevel(List<Stage> stages) {
        if (stages.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int max = 0;
        boolean unlimited = false;
        for (Stage stage : stages) {
            if (stage.maxLevel() == UNLIMITED) {
                unlimited = true;
            } else {
                max = Math.max(max, stage.maxLevel());
            }
        }
        return unlimited ? Integer.MAX_VALUE : max;
    }

    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static double doubleValue(Object raw, double fallback) {
        return raw instanceof Number number ? number.doubleValue() : fallback;
    }
}
