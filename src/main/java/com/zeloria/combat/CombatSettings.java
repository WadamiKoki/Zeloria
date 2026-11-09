package com.zeloria.combat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * {@code config/SystemData/combat_settings.yml} から読み込んだ戦闘・表示設定。
 * <p>
 * モブ攻撃の受付間隔、レベル差によるステータス補正、防御軽減曲線、
 * ダメージインジケーター、頭上ステータス表示の描画値を保持する。
 * 起動時に 1 回だけ解析し、各サービスは毎 tick YAML を読み直さない。
 */
public final class CombatSettings {

    /** 設定ファイルの識別子。 */
    public static final String SETTINGS_KEY = "SystemData/combat_settings.yml";

    private final long mobAttackCooldownMillis;
    private final LevelScaling levelScaling;
    private final Defense defense;
    private final DamageIndicator damageIndicator;
    private final StatusDisplay statusDisplay;

    public CombatSettings(
            long mobAttackCooldownMillis,
            LevelScaling levelScaling,
            Defense defense,
            DamageIndicator damageIndicator,
            StatusDisplay statusDisplay
    ) {
        this.mobAttackCooldownMillis = Math.max(1L, mobAttackCooldownMillis);
        this.levelScaling = Objects.requireNonNull(levelScaling, "levelScaling");
        this.defense = Objects.requireNonNull(defense, "defense");
        this.damageIndicator = Objects.requireNonNull(damageIndicator, "damageIndicator");
        this.statusDisplay = Objects.requireNonNull(statusDisplay, "statusDisplay");
    }

    /**
     * YAML 設定から読み込む。
     *
     * @param config 設定（必須）
     * @return 読み込んだ設定
     */
    public static CombatSettings fromConfig(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        LevelScaling levelScaling = new LevelScaling(
                config.getDouble("level_scaling.per_level"),
                config.getDouble("level_scaling.min_multiplier"));
        Defense defense = new Defense(config.getDouble("defense.curve_constant"));
        DamageIndicator damageIndicator = new DamageIndicator(
                config.getInt("damage_indicator.max_per_tick"),
                config.getLong("damage_indicator.lifetime_ticks"),
                (float) config.getDouble("damage_indicator.scale"),
                config.getDouble("damage_indicator.horizontal_spread"),
                config.getDouble("damage_indicator.y_offset"));
        StatusDisplay statusDisplay = new StatusDisplay(
                (float) config.getDouble("status_display.y_offset"),
                (float) config.getDouble("status_display.scale"),
                config.getInt("status_display.gauge_length"));

        return new CombatSettings(
                config.getLong("mob_attack.cooldown_millis"),
                levelScaling,
                defense,
                damageIndicator,
                statusDisplay);
    }

    public long mobAttackCooldownMillis() {
        return mobAttackCooldownMillis;
    }

    public LevelScaling levelScaling() {
        return levelScaling;
    }

    public Defense defense() {
        return defense;
    }

    public DamageIndicator damageIndicator() {
        return damageIndicator;
    }

    public StatusDisplay statusDisplay() {
        return statusDisplay;
    }

    /**
     * スポーンレベルと基準レベルの差に応じた補正。
     *
     * @param perLevel     レベル差 1 あたりの倍率加算（例: {@code 0.03}）
     * @param minMultiplier 倍率の下限
     */
    public record LevelScaling(double perLevel, double minMultiplier) {

        public LevelScaling {
            perLevel = Math.max(0.0, perLevel);
            minMultiplier = Math.max(0.0, minMultiplier);
        }
    }

    /**
     * 防御軽減曲線の設定。
     *
     * @param curveConstant 軽減式 {@code c / (c + defense)} の定数
     */
    public record Defense(double curveConstant) {

        public Defense {
            curveConstant = Math.max(1.0, curveConstant);
        }
    }

    /**
     * ダメージインジケーターの表示設定。
     *
     * @param maxPerTick       1 tick あたりの生成上限
     * @param lifetimeTicks    表示を維持する tick 数
     * @param scale            テキストの拡大率
     * @param horizontalSpread 水平方向のばらつき幅
     * @param yOffset          エンティティ上端からの表示オフセット
     */
    public record DamageIndicator(
            int maxPerTick,
            long lifetimeTicks,
            float scale,
            double horizontalSpread,
            double yOffset
    ) {

        public DamageIndicator {
            maxPerTick = Math.max(1, maxPerTick);
            lifetimeTicks = Math.max(1L, lifetimeTicks);
            scale = Math.max(0.01f, scale);
            horizontalSpread = Math.max(0.0, horizontalSpread);
            yOffset = Math.max(0.0, yOffset);
        }
    }

    /**
     * 頭上ステータス表示の設定。
     *
     * @param yOffset     頭上表示の縦位置
     * @param scale       頭上表示の拡大率
     * @param gaugeLength ゲージのマス数
     */
    public record StatusDisplay(float yOffset, float scale, int gaugeLength) {

        public StatusDisplay {
            scale = Math.max(0.01f, scale);
            gaugeLength = Math.max(1, gaugeLength);
        }
    }
}
