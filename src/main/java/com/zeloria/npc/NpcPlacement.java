package com.zeloria.npc;

import java.util.Map;

/**
 * レイヤー内に配置する NPC の座標定義。
 * <p>
 * {@code LayerData} の {@code npcs} セクションにおける 1 エントリに対応する。
 * <pre>
 * npcs:
 *   - npcId: guide
 *     x: 10.5
 *     y: 65.0
 *     z: 10.5
 *     yaw: 0.0
 *     pitch: 0.0
 * </pre>
 *
 * @param npcId 配置する NPC 定義の ID
 * @param x     配置座標 X
 * @param y     配置座標 Y
 * @param z     配置座標 Z
 * @param yaw   向き（左右）
 * @param pitch 向き（上下）
 */
public record NpcPlacement(
        String npcId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    public NpcPlacement {
        npcId = npcId == null ? "" : npcId.trim();
    }

    /**
     * 配置定義として有効か判定する。
     */
    public boolean isValid() {
        return !npcId.isBlank();
    }

    /**
     * YAML のリスト要素（マップ）から配置定義を生成する。
     *
     * @param map 1 エントリ分のマップ
     * @return 配置定義
     */
    public static NpcPlacement fromMap(Map<?, ?> map) {
        if (map == null) {
            return new NpcPlacement("", 0.0, 0.0, 0.0, 0.0f, 0.0f);
        }
        return new NpcPlacement(
                text(map.get("npcId"), text(map.get("npc_id"), text(map.get("id"), ""))),
                number(map.get("x"), 0.0),
                number(map.get("y"), 0.0),
                number(map.get("z"), 0.0),
                (float) number(map.get("yaw"), 0.0),
                (float) number(map.get("pitch"), 0.0)
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
