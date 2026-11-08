package com.zeloria.skill;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * {@code config/SystemData/level_settings.yml} から読み込んだレベル・経験値の設定。
 * <p>
 * 必要経験値は Wynncraft 方式の二次関数で算出する。
 * <ul>
 *     <li>次レベルまで: {@code base_exp * (2 * level - 1)}</li>
 *     <li>レベル 1 からの累計: {@code base_exp * (level - 1)^2}</li>
 * </ul>
 * 計算は {@code long} で行い、オーバーフロー時は {@link Long#MAX_VALUE} にクランプする。
 * <pre>
 * base_exp: 1200
 * max_level: 100
 * tree_points_per_level: 1
 * </pre>
 */
public final class LevelSettings {

    /** 既定の基準経験値。 */
    public static final long DEFAULT_BASE_EXP = 1200L;

    /** 既定のレベル上限。 */
    public static final int DEFAULT_MAX_LEVEL = 100;

    /** 既定のレベルアップ 1 回あたりに付与するツリーポイント。 */
    public static final int DEFAULT_TREE_POINTS_PER_LEVEL = 1;

    /** 設定が読み込めなかった場合に使用する既定値。 */
    public static final LevelSettings DEFAULT =
            new LevelSettings(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL, DEFAULT_TREE_POINTS_PER_LEVEL);


    private final long baseExp;
    private final int maxLevel;
    private final int treePointsPerLevel;

    /**
     * 設定を生成する。
     *
     * @param baseExp            基準経験値（1 以上）
     * @param maxLevel           レベル上限（1 以上）
     * @param treePointsPerLevel レベルアップ 1 回あたりに付与するクラスツリーポイント
     */
    public LevelSettings(long baseExp, int maxLevel, int treePointsPerLevel) {
        this.baseExp = Math.max(1L, baseExp);
        this.maxLevel = Math.max(1, maxLevel);
        this.treePointsPerLevel = Math.max(0, treePointsPerLevel);
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
        return new LevelSettings(
                config.getLong("base_exp", DEFAULT_BASE_EXP),
                config.getInt("max_level", DEFAULT_MAX_LEVEL),
                config.getInt("tree_points_per_level", DEFAULT_TREE_POINTS_PER_LEVEL));
    }

    public long baseExp() {
        return baseExp;
    }

    /**
     * 到達可能な最大レベルを返す。
     */
    public int maxLevel() {
        return maxLevel;
    }

    /**
     * レベルアップ 1 回あたりに付与するクラスツリーポイントを返す。
     */
    public int treePointsPerLevel() {
        return treePointsPerLevel;
    }

    /**
     * 指定レベルの「次レベルまでに必要な経験値」を返す。
     * <p>
     * {@code base_exp * (2 * level - 1)} を {@code long} で計算する。
     * レベル 1 では {@code base_exp}、レベル 2 では {@code 3 * base_exp} となり、
     * レベル 1 からの累計は {@code base_exp * (level - 1)^2} と一致する。
     * オーバーフローする場合は {@link Long#MAX_VALUE} を返す。
     *
     * @param level 現在レベル（1 未満は 1 として扱う）
     * @return 次のレベルへ上がるために必要な経験値（最低 1）
     */
    public long requiredExp(int level) {
        int clamped = Math.max(1, level);
        long factor = 2L * clamped - 1L;
        if (baseExp > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, baseExp * factor);
    }
}

