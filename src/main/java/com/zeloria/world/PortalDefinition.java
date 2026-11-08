package com.zeloria.world;

import java.util.Map;

/**
 * レイヤー内に配置するポータルの定義。
 * <p>
 * {@code LayerData} の {@code portals} セクションにおける 1 エントリに対応する。
 * <pre>
 * portals:
 *   - id: "to_field"
 *     from:
 *       world: "hub_instance_1"   # 空なら対象レイヤーの全インスタンスで有効
 *       x: 10.5
 *       y: 65.0
 *       z: 10.5
 *     to:
 *       world: "field_1"          # 転送先レイヤーのテンプレート ID
 *       x: 0.5
 *       y: 65.0
 *       z: 0.5
 *       yaw: 0.0
 *       pitch: 0.0
 *     radius: 1.0
 * </pre>
 * {@code radius} は記載のみで、実際の発動半径は常に {@link #PORTAL_RADIUS} を使用する。
 *
 * @param id             ポータル ID
 * @param world          ポータルを有効にする設置元ワールド名（{@code from.world}。空なら全インスタンス）
 * @param x              設置座標 X（{@code from.x}）
 * @param y              設置座標 Y（{@code from.y}）
 * @param z              設置座標 Z（{@code from.z}）
 * @param radius         発動半径
 * @param targetTemplate 転送先レイヤーのテンプレート ID（{@code to.world}）
 * @param targetX        転送先 X（{@code to.x}。未指定なら {@code null}）
 * @param targetY        転送先 Y（{@code to.y}。未指定なら {@code null}）
 * @param targetZ        転送先 Z（{@code to.z}。未指定なら {@code null}）
 * @param targetYaw      転送先 Yaw（{@code to.yaw}。未指定なら {@code null}）
 * @param targetPitch    転送先 Pitch（{@code to.pitch}。未指定なら {@code null}）
 */
public record PortalDefinition(
        String id,
        String world,
        double x,
        double y,
        double z,
        double radius,
        String targetTemplate,
        Double targetX,
        Double targetY,
        Double targetZ,
        Float targetYaw,
        Float targetPitch
) {

    /**
     * ポータルの統一半径（メートル）。
     * <p>
     * 発動判定（足元座標からの距離）と外周パーティクルの描画は、どちらもこの値を基準にそろえる。
     */
    public static final double PORTAL_RADIUS = 1.0;

    /** 既定の発動半径（{@link #PORTAL_RADIUS} に統一）。 */
    public static final double DEFAULT_RADIUS = PORTAL_RADIUS;

    public PortalDefinition {
        id = id == null ? "" : id;
        world = world == null ? "" : world;
        // 判定（足元からの距離）と外周パーティクルの描画を一致させるため、
        // 設定値に関わらず半径は常に PORTAL_RADIUS（1.0m）へ統一する。
        radius = PORTAL_RADIUS;
        targetTemplate = targetTemplate == null ? "" : targetTemplate;
    }

    /**
     * YAML のリスト要素（マップ）からポータル定義を生成する。
     *
     * @param map 1 エントリ分のマップ
     * @return ポータル定義
     */
    public static PortalDefinition fromMap(Map<?, ?> map) {
        if (map == null) {
            return empty();
        }

        Map<?, ?> from = nestedMap(map.get("from"));
        Map<?, ?> to = nestedMap(map.get("to"));

        return new PortalDefinition(
                text(map.get("id"), ""),
                text(from.get("world"), ""),
                number(from.get("x"), 0.0),
                number(from.get("y"), 0.0),
                number(from.get("z"), 0.0),
                // 半径は仕様固定のため、設定値は読み取らない。
                PORTAL_RADIUS,
                text(to.get("world"), ""),
                nullableNumber(to.get("x")),
                nullableNumber(to.get("y")),
                nullableNumber(to.get("z")),
                nullableFloat(to.get("yaw")),
                nullableFloat(to.get("pitch"))
        );
    }

    /**
     * ポータル ID が設定されているか判定する。
     */
    public boolean isValid() {
        return !id.isBlank();
    }

    /**
     * 転送先テンプレートが指定されているか判定する。
     */
    public boolean hasTargetTemplate() {
        return !targetTemplate.isBlank();
    }

    /**
     * 転送先座標が明示されているか判定する。
     */
    public boolean hasTargetLocation() {
        return targetX != null && targetY != null && targetZ != null;
    }

    private static PortalDefinition empty() {
        return new PortalDefinition("", "", 0.0, 0.0, 0.0, PORTAL_RADIUS, "",
                null, null, null, null, null);
    }

    private static Map<?, ?> nestedMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
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

    private static Double nullableNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Float nullableFloat(Object value) {
        Double number = nullableNumber(value);
        return number == null ? null : number.floatValue();
    }
}
