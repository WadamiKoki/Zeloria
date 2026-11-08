package com.zeloria.world;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code config/LayerData/*.yml} の {@code spawners} セクションに記述するモブスポーン定義。
 * <p>
 * 旧 {@code config/SpawnerData/*.yml}（{@code SpawnerData}）と {@code spawnerId} 参照による
 * 配置定義（{@code SpawnerPlacement}）を統合したもので、1 エントリが 1 スポナーに対応する。
 * <pre>
 * spawners:
 *   - mobId: "goblin"
 *     minLevel: 1
 *     maxLevel: 10
 *     x: 100.5
 *     y: 65.0
 *     z: 200.5
 *     spawnRateSeconds: 30
 *     maxAlive: 3
 *     spawnLimit: 10
 *     spawnRadius: 16.0
 *     spawnRange: 3.0
 * </pre>
 * スポーン座標は Y を {@code y} に固定し、X / Z だけを {@code spawnRange} の範囲で
 * ランダムにずらす。実際のスポーン処理は {@link com.zeloria.mob.SpawnerManager} が行う。
 * <p>
 * 負荷対策として {@code spawnRateSeconds} は最低 {@value #MIN_SPAWN_RATE_SECONDS} 秒、
 * {@code maxAlive} は最大 {@value #MAX_ALIVE} 体にクランプする。
 * デバッグ表示は {@code /admin spawner mode} で一元管理するため、本定義には含めない。
 *
 * @param mobId            スポーンさせるモブ定義（{@code MobData}）の ID
 * @param minLevel         スポーン時のレベル下限
 * @param maxLevel         スポーン時のレベル上限（{@code minLevel} 未満なら {@code minLevel} に揃える）
 * @param x                スポナー座標 X
 * @param y                スポナー座標 Y（スポーン時の Y としても固定使用する）
 * @param z                スポナー座標 Z
 * @param spawnRateSeconds スポーン判定の間隔（秒）
 * @param maxAlive         同時に生存させる最大数
 * @param spawnLimit       1 回の判定でスポーンさせる最大数
 * @param spawnRadius      プレイヤーを検知する半径（ブロック）
 * @param spawnRange       スポナー座標から水平方向にずらす最大距離（ブロック）
 */
public record SpawnerDefinition(
        String mobId,
        int minLevel,
        int maxLevel,
        double x,
        double y,
        double z,
        int spawnRateSeconds,
        int maxAlive,
        int spawnLimit,
        double spawnRadius,
        double spawnRange
) {

    /** {@code spawnRateSeconds} の下限（秒）。 */
    public static final int MIN_SPAWN_RATE_SECONDS = 30;

    /** {@code maxAlive} の上限。 */
    public static final int MAX_ALIVE = 5;

    /** {@code maxAlive} の既定値。 */
    public static final int DEFAULT_MAX_ALIVE = 3;

    /** {@code spawnLimit} の既定値。 */
    public static final int DEFAULT_SPAWN_LIMIT = 10;

    /** {@code spawnRadius} の既定値（ブロック）。 */
    public static final double DEFAULT_SPAWN_RADIUS = 16.0;

    /** {@code spawnRadius} の下限（ブロック）。 */
    public static final double MIN_SPAWN_RADIUS = 1.0;

    /** {@code spawnRadius} の上限（ブロック）。 */
    public static final double MAX_SPAWN_RADIUS = 128.0;

    /** {@code spawnRange} の既定値（ブロック）。 */
    public static final double DEFAULT_SPAWN_RANGE = 3.0;

    /** {@code spawnRange} の上限（ブロック）。過剰なズレで別チャンクへ飛ぶのを防ぐ。 */
    public static final double MAX_SPAWN_RANGE = 64.0;

    /** {@code minLevel} / {@code maxLevel} の最小値。 */
    public static final int MIN_LEVEL = 1;

    public SpawnerDefinition {
        mobId = mobId == null ? "" : mobId.trim();
        minLevel = Math.max(MIN_LEVEL, minLevel);
        maxLevel = Math.max(minLevel, maxLevel);
        spawnRateSeconds = Math.max(MIN_SPAWN_RATE_SECONDS, spawnRateSeconds);
        maxAlive = Math.max(1, Math.min(MAX_ALIVE, maxAlive));
        spawnLimit = Math.max(1, spawnLimit);
        spawnRadius = clamp(spawnRadius, MIN_SPAWN_RADIUS, MAX_SPAWN_RADIUS, DEFAULT_SPAWN_RADIUS);
        spawnRange = clamp(spawnRange, 0.0, MAX_SPAWN_RANGE, DEFAULT_SPAWN_RANGE);
    }

    /**
     * スポーン定義として有効か判定する。
     */
    public boolean isValid() {
        return !mobId.isBlank();
    }

    /**
     * スポーン判定の間隔を tick で返す。
     */
    public int spawnRateTicks() {
        return spawnRateSeconds * 20;
    }

    /**
     * {@code minLevel} 〜 {@code maxLevel} の範囲でスポーンレベルを抽選する。
     * <p>
     * 範囲が 1 レベルだけの場合は乱数を消費せずそのレベルを返す。
     */
    public int randomLevel() {
        if (maxLevel <= minLevel) {
            return minLevel;
        }
        return ThreadLocalRandom.current().nextInt(minLevel, maxLevel + 1);
    }

    /**
     * ログ表示用の識別子（{@code mobId@x,y,z}）を返す。
     */
    public String displayId() {
        return String.format(Locale.ROOT, "%s@%.1f,%.1f,%.1f", mobId, x, y, z);
    }

    /**
     * YAML のリスト要素（マップ）からスポーン定義を生成する。
     * <p>
     * 省略されたキーは既定値を使用する。{@code mobId} が空のエントリは
     * {@link #isValid()} が {@code false} となり、呼び出し側で読み飛ばされる。
     *
     * @param map 1 エントリ分のマップ
     * @return スポーン定義
     */
    public static SpawnerDefinition fromMap(Map<?, ?> map) {
        if (map == null) {
            return new SpawnerDefinition("", MIN_LEVEL, MIN_LEVEL, 0.0, 0.0, 0.0,
                    MIN_SPAWN_RATE_SECONDS, DEFAULT_MAX_ALIVE, DEFAULT_SPAWN_LIMIT,
                    DEFAULT_SPAWN_RADIUS, DEFAULT_SPAWN_RANGE);
        }

        int minLevel = number(map.get("minLevel"), MIN_LEVEL).intValue();
        return new SpawnerDefinition(
                text(map.get("mobId")),
                minLevel,
                number(map.get("maxLevel"), minLevel).intValue(),
                number(map.get("x"), 0.0).doubleValue(),
                number(map.get("y"), 0.0).doubleValue(),
                number(map.get("z"), 0.0).doubleValue(),
                number(map.get("spawnRateSeconds"), MIN_SPAWN_RATE_SECONDS).intValue(),
                number(map.get("maxAlive"), DEFAULT_MAX_ALIVE).intValue(),
                number(map.get("spawnLimit"), DEFAULT_SPAWN_LIMIT).intValue(),
                number(map.get("spawnRadius"), DEFAULT_SPAWN_RADIUS).doubleValue(),
                number(map.get("spawnRange"), DEFAULT_SPAWN_RANGE).doubleValue()
        );
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || value < 0.0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Number number(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                // 不正な値は既定値へフォールバックする。
            }
        }
        return fallback;
    }
}
