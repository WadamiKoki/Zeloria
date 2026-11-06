package com.zeloria.world;

import java.util.Map;

/**
 * レイヤー内に設置するモブスポナー（定点湧き）の定義。
 * <p>
 * {@code LayerData} の {@code spawners} セクションにおける 1 エントリに対応する。
 * <pre>
 * spawners:
 *   - mobId: "goblin"   # MobData のモブ ID（ファイル名でも可）
 *     x: 10.5
 *     y: 65.0
 *     z: 10.5
 *     level: 1          # モブのレベル（1 以上。ステータスが倍率で強化される）
 *     maxAlive: 5       # 同時に生存させる最大数
 *     spawnAmount: 1    # 1 回の判定でスポーンさせる最大数
 *     interval: 100     # 判定間隔（tick）
 *     radius: 24.0      # プレイヤーがこの距離以内にいるときだけ湧く
 * </pre>
 * 実際のスポーン処理は {@link com.zeloria.mob.SpawnerManager} が行う。
 *
 * @param mobId       スポーンさせるモブ定義の ID
 * @param x           スポーン基準座標 X
 * @param y           スポーン基準座標 Y
 * @param z           スポーン基準座標 Z
 * @param level       スポーンさせるモブのレベル
 * @param maxAlive    同時に生存させる最大数
 * @param spawnAmount 1 回の判定でスポーンさせる最大数
 * @param interval    スポーン判定の間隔（tick）
 * @param radius      プレイヤーを検知する半径（ブロック）
 */
public record SpawnerPlacement(
        String mobId,
        double x,
        double y,
        double z,
        int level,
        int maxAlive,
        int spawnAmount,
        int interval,
        double radius
) {

    /** {@code maxAlive} の既定値。 */
    public static final int DEFAULT_MAX_ALIVE = 5;

    /** {@code spawnAmount} の既定値。 */
    public static final int DEFAULT_SPAWN_AMOUNT = 1;

    /** {@code interval} の既定値（tick）。 */
    public static final int DEFAULT_INTERVAL = 100;

    /** {@code radius} の既定値（ブロック）。 */
    public static final double DEFAULT_RADIUS = 24.0;

    /** {@code interval} の下限（tick）。1 秒未満の連続湧きを防ぐ。 */
    public static final int MIN_INTERVAL = 20;

    /** {@code radius} の上限（ブロック）。 */
    public static final double MAX_RADIUS = 128.0;

    public SpawnerPlacement {
        mobId = mobId == null ? "" : mobId.trim();
        level = Math.max(1, level);
        maxAlive = Math.max(1, maxAlive);
        spawnAmount = Math.max(1, spawnAmount);
        interval = Math.max(MIN_INTERVAL, interval);
        radius = radius <= 0.0 ? DEFAULT_RADIUS : Math.min(radius, MAX_RADIUS);
    }

    /**
     * スポナー定義として有効か判定する。
     */
    public boolean isValid() {
        return !mobId.isBlank();
    }

    /**
     * YAML のリスト要素（マップ）からスポナー定義を生成する。
     *
     * @param map 1 エントリ分のマップ
     * @return スポナー定義
     */
    public static SpawnerPlacement fromMap(Map<?, ?> map) {
        if (map == null) {
            return new SpawnerPlacement("", 0.0, 0.0, 0.0, 1,
                    DEFAULT_MAX_ALIVE, DEFAULT_SPAWN_AMOUNT, DEFAULT_INTERVAL, DEFAULT_RADIUS);
        }
        return new SpawnerPlacement(
                text(map.get("mobId"), text(map.get("mob_id"), text(map.get("mob"), ""))),
                number(map.get("x"), 0.0),
                number(map.get("y"), 0.0),
                number(map.get("z"), 0.0),
                integer(map.get("level"), 1),
                integer(map.get("maxAlive"), integer(map.get("max_alive"), DEFAULT_MAX_ALIVE)),
                integer(map.get("spawnAmount"),
                        integer(map.get("spawn_amount"), DEFAULT_SPAWN_AMOUNT)),
                integer(map.get("interval"), integer(map.get("intervalTicks"), DEFAULT_INTERVAL)),
                number(map.get("radius"), DEFAULT_RADIUS)
        );
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
