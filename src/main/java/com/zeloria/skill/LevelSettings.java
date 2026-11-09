package com.zeloria.skill;

import com.zeloria.core.ConfigCache;
import com.zeloria.core.EffectSettings;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

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
            new LevelSettings(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL,
                    DEFAULT_TREE_POINTS_PER_LEVEL, LevelUpSettings.DEFAULT);


    private final long baseExp;
    private final int maxLevel;
    private final int treePointsPerLevel;
    private final LevelUpSettings levelUp;

    /**
     * 設定を生成する（レベルアップ演出は既定値）。
     *
     * @param baseExp            基準経験値（1 以上）
     * @param maxLevel           レベル上限（1 以上）
     * @param treePointsPerLevel レベルアップ 1 回あたりに付与するクラスツリーポイント
     */
    public LevelSettings(long baseExp, int maxLevel, int treePointsPerLevel) {
        this(baseExp, maxLevel, treePointsPerLevel, LevelUpSettings.DEFAULT);
    }

    /**
     * 設定を生成する。
     *
     * @param baseExp            基準経験値（1 以上）
     * @param maxLevel           レベル上限（1 以上）
     * @param treePointsPerLevel レベルアップ 1 回あたりに付与するクラスツリーポイント
     * @param levelUp            レベルアップ通知の音・パーティクル
     */
    public LevelSettings(long baseExp, int maxLevel, int treePointsPerLevel,
                         LevelUpSettings levelUp) {
        this.baseExp = Math.max(1L, baseExp);
        this.maxLevel = Math.max(1, maxLevel);
        this.treePointsPerLevel = Math.max(0, treePointsPerLevel);
        this.levelUp = levelUp == null ? LevelUpSettings.DEFAULT : levelUp;
    }

    /**
     * YAML 設定から読み込む（解決キャッシュなし）。
     *
     * @param config 設定（{@code null} の場合は {@link #DEFAULT}）
     * @return 読み込んだ設定
     */
    public static LevelSettings fromConfig(FileConfiguration config) {
        return fromConfig(config, null);
    }

    /**
     * YAML 設定から読み込む。
     *
     * @param config 設定（{@code null} の場合は {@link #DEFAULT}）
     * @param cache  Sound / Particle の解決キャッシュ
     * @return 読み込んだ設定
     */
    public static LevelSettings fromConfig(FileConfiguration config, ConfigCache cache) {
        if (config == null) {
            return DEFAULT;
        }
        return new LevelSettings(
                config.getLong("base_exp", DEFAULT_BASE_EXP),
                config.getInt("max_level", DEFAULT_MAX_LEVEL),
                config.getInt("tree_points_per_level", DEFAULT_TREE_POINTS_PER_LEVEL),
                LevelUpSettings.fromConfig(
                        config.getConfigurationSection("level_up"), cache));
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
     * レベルアップ通知の音・パーティクル設定を返す。
     */
    public LevelUpSettings levelUp() {
        return levelUp;
    }

    /**
     * レベルアップ通知の演出設定。
     *
     * @param sound           再生する効果音
     * @param particle        発生させるパーティクル
     * @param particleYOffset プレイヤー座標から上へずらす量
     */
    public record LevelUpSettings(
            EffectSettings.SoundEffect sound,
            EffectSettings.ParticleEffect particle,
            double particleYOffset
    ) {

        /** 既存のレベルアップ演出と同じ既定値。 */
        public static final LevelUpSettings DEFAULT = new LevelUpSettings(
                new EffectSettings.SoundEffect(
                        "entity.player.levelup", Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f),
                new EffectSettings.ParticleEffect(
                        Particle.HAPPY_VILLAGER, 30, 0.5, 0.8, 0.5, 0.0),
                1.0);

        public LevelUpSettings {
            sound = Objects.requireNonNull(sound, "sound");
            particle = Objects.requireNonNull(particle, "particle");
            particleYOffset = Math.max(0.0, particleYOffset);
        }

        /**
         * YAML の {@code level_up} セクションから読み込む。
         *
         * @param section 設定セクション（未設定の場合は {@link #DEFAULT}）
         * @param cache   Sound / Particle の解決キャッシュ
         * @return 読み込んだ設定
         */
        public static LevelUpSettings fromConfig(
                ConfigurationSection section, ConfigCache cache) {
            if (section == null) {
                return DEFAULT;
            }
            EffectSettings.SoundEffect sound = EffectSettings.loadSound(
                    section.getConfigurationSection("sound"), LevelService.SETTINGS_KEY, cache,
                    Sound.ENTITY_PLAYER_LEVELUP);
            EffectSettings.ParticleEffect particle = EffectSettings.loadParticle(
                    section.getConfigurationSection("particle"), LevelService.SETTINGS_KEY, cache,
                    Particle.HAPPY_VILLAGER);
            return new LevelUpSettings(
                    sound, particle, section.getDouble("particle_y_offset", 1.0));
        }
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

