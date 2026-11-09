package com.zeloria.world;

import com.zeloria.core.ConfigCache;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * {@code config/SystemData/spawner_settings.yml} から読み込んだスポナー設定。
 * <p>
 * スポーン試行回数・スポナーの既定値／クランプ上限・デバッグ表示を保持する。
 * 判定タスクとデバッグタスクの実行周期はリロードで反映できないため、
 * {@code SpawnerManager} 側の固定値を使用する。
 */
public final class SpawnerSettings {

    /** 設定ファイルの識別子。 */
    public static final String SETTINGS_KEY = "SystemData/spawner_settings.yml";

    private final Spawner spawner;

    public SpawnerSettings(Spawner spawner) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    /**
     * YAML 設定から読み込む。
     *
     * @param config 設定（必須）
     * @param cache  Particle の解決キャッシュ
     * @return 読み込んだ設定
     */
    public static SpawnerSettings fromConfig(FileConfiguration config, ConfigCache cache) {
        Objects.requireNonNull(config, "config");

        ConfigurationSection spawner = config.getConfigurationSection("spawner");
        ConfigurationSection debug = spawner == null
                ? null
                : spawner.getConfigurationSection("debug");
        ConfigurationSection defaults = spawner == null
                ? null
                : spawner.getConfigurationSection("defaults");
        ConfigurationSection limits = spawner == null
                ? null
                : spawner.getConfigurationSection("limits");

        Debug debugSettings = new Debug(
                cache == null
                        ? ConfigCache.resolveParticle(debug == null ? null : debug.getString("particle"))
                        : cache.particle(SETTINGS_KEY, debug, "particle", Particle.HAPPY_VILLAGER),
                debug == null ? 64.0 : debug.getDouble("radius"),
                debug == null ? 2 : debug.getInt("count"),
                debug == null ? 1.0 : debug.getDouble("y_offset"),
                debug == null ? 0.3 : debug.getDouble("offset"));
        Defaults defaultSettings = new Defaults(
                defaults == null ? 30 : defaults.getInt("spawn_rate_seconds"),
                defaults == null ? 3 : defaults.getInt("max_alive"),
                defaults == null ? 10 : defaults.getInt("spawn_limit"),
                defaults == null ? 16.0 : defaults.getDouble("spawn_radius"),
                defaults == null ? 3.0 : defaults.getDouble("spawn_range"));
        Limits limitSettings = new Limits(
                limits == null ? 30 : limits.getInt("min_spawn_rate_seconds"),
                limits == null ? 5 : limits.getInt("max_alive"),
                limits == null ? 1.0 : limits.getDouble("min_spawn_radius"),
                limits == null ? 128.0 : limits.getDouble("max_spawn_radius"),
                limits == null ? 64.0 : limits.getDouble("max_spawn_range"));

        return new SpawnerSettings(new Spawner(
                spawner == null ? 10 : spawner.getInt("spawn_attempts"),
                spawner == null ? 200 : spawner.getInt("max_stagger_ticks"),
                spawner == null ? -65.0 : spawner.getDouble("void_despawn_y"),
                debugSettings,
                defaultSettings,
                limitSettings));
    }

    public Spawner spawner() {
        return spawner;
    }

    /**
     * スポナー全体の設定。
     *
     * @param spawnAttempts   スポーン座標の再抽選回数
     * @param maxStaggerTicks 初回判定をずらす最大 tick 数
     * @param voidDespawnY    奈落と判定する Y 座標
     * @param debug           デバッグ表示の設定
     * @param defaults        レイヤー側で省略された場合の既定値
     * @param limits          設定値のクランプ上限
     */
    public record Spawner(
            int spawnAttempts,
            int maxStaggerTicks,
            double voidDespawnY,
            Debug debug,
            Defaults defaults,
            Limits limits
    ) {

        public Spawner {
            spawnAttempts = Math.max(1, spawnAttempts);
            maxStaggerTicks = Math.max(1, maxStaggerTicks);
            debug = Objects.requireNonNull(debug, "debug");
            defaults = Objects.requireNonNull(defaults, "defaults");
            limits = Objects.requireNonNull(limits, "limits");
        }
    }

    /**
     * デバッグ表示の設定。
     *
     * @param particle 表示するパーティクル
     * @param radius   管理者からの表示距離
     * @param count    1 スポナーあたりの生成数
     * @param yOffset  スポナー座標から上へずらす量
     * @param offset   パーティクルのばらつき
     */
    public record Debug(
            Particle particle,
            double radius,
            int count,
            double yOffset,
            double offset
    ) {

        public Debug {
            particle = particle == null ? Particle.HAPPY_VILLAGER : particle;
            radius = Math.max(1.0, radius);
            count = Math.max(1, count);
            offset = Math.max(0.0, offset);
        }
    }

    /**
     * スポナー定義で省略された場合の既定値。
     *
     * @param spawnRateSeconds スポーン判定の間隔（秒）
     * @param maxAlive         同時に生存させる最大数
     * @param spawnLimit       1 回の判定でスポーンさせる最大数
     * @param spawnRadius      プレイヤーを検知する半径
     * @param spawnRange       座標を水平方向にずらす最大距離
     */
    public record Defaults(
            int spawnRateSeconds,
            int maxAlive,
            int spawnLimit,
            double spawnRadius,
            double spawnRange
    ) {

        public Defaults {
            spawnRateSeconds = Math.max(1, spawnRateSeconds);
            maxAlive = Math.max(1, maxAlive);
            spawnLimit = Math.max(1, spawnLimit);
            spawnRadius = Math.max(0.01, spawnRadius);
            spawnRange = Math.max(0.0, spawnRange);
        }
    }

    /**
     * スポナー定義のクランプ上限。
     *
     * @param minSpawnRateSeconds スポーン間隔の下限（秒）
     * @param maxAlive            同時生存数の上限
     * @param minSpawnRadius      検知半径の下限
     * @param maxSpawnRadius      検知半径の上限
     * @param maxSpawnRange       座標ズレ幅の上限
     */
    public record Limits(
            int minSpawnRateSeconds,
            int maxAlive,
            double minSpawnRadius,
            double maxSpawnRadius,
            double maxSpawnRange
    ) {

        public Limits {
            minSpawnRateSeconds = Math.max(1, minSpawnRateSeconds);
            maxAlive = Math.max(1, maxAlive);
            minSpawnRadius = Math.max(0.01, minSpawnRadius);
            maxSpawnRadius = Math.max(minSpawnRadius, maxSpawnRadius);
            maxSpawnRange = Math.max(0.0, maxSpawnRange);
        }
    }
}
