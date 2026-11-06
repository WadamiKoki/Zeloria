package com.zeloria.world;

import java.util.Map;

/**
 * レイヤー内に設置するモブスポナーの配置定義。
 * <p>
 * 湧き数・間隔・見た目などの挙動は {@code config/SpawnerData/*.yml} で管理し、
 * ここでは「どのスポナーをどこへ置くか」だけを保持する。
 * <pre>
 * spawners:
 *   - spawnerId: "goblin_camp"
 *     x: 10.5
 *     y: 65.0
 *     z: 10.5
 * </pre>
 * 実際のスポーン処理は {@link com.zeloria.mob.SpawnerManager} が行う。
 *
 * @param spawnerId スポナー定義の ID（{@code SpawnerData} の {@code id}）
 * @param x         設置座標 X
 * @param y         設置座標 Y
 * @param z         設置座標 Z
 */
public record SpawnerPlacement(
        String spawnerId,
        double x,
        double y,
        double z
) {

    public SpawnerPlacement {
        spawnerId = spawnerId == null ? "" : spawnerId.trim();
    }

    /**
     * スポナー配置として有効か判定する。
     */
    public boolean isValid() {
        return !spawnerId.isBlank();
    }

    /**
     * YAML のリスト要素（マップ）からスポナー配置を生成する。
     *
     * @param map 1 エントリ分のマップ
     * @return スポナー配置
     */
    public static SpawnerPlacement fromMap(Map<?, ?> map) {
        if (map == null) {
            return new SpawnerPlacement("", 0.0, 0.0, 0.0);
        }
        return new SpawnerPlacement(
                text(map.get("spawnerId"), text(map.get("spawner_id"), "")),
                number(map.get("x"), 0.0),
                number(map.get("y"), 0.0),
                number(map.get("z"), 0.0)
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
}
