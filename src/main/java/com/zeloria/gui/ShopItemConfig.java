package com.zeloria.gui;

import java.util.Map;

/**
 * {@code config/ShopData/*.yml} の商品 1 件分の設定。
 * <p>
 * 店舗ごとの商品リストは次の形式で記述する。
 * <pre>
 * items:
 *   - item-id: "iron_sword"
 *     price: 500
 *     slot: 10
 * </pre>
 *
 * @param itemId 商品のアイテム ID
 * @param price  販売価格（1 個あたり）
 * @param slot   商品を表示する GUI スロット
 */
public record ShopItemConfig(String itemId, long price, int slot) {

    public ShopItemConfig {
        itemId = itemId == null ? "" : itemId.trim();
        price = Math.max(0L, price);
    }

    /**
     * 商品として有効か判定する。
     */
    public boolean isValid() {
        return !itemId.isBlank() && price > 0L && slot >= 0;
    }

    /**
     * YAML の 1 エントリから商品設定を生成する。
     *
     * @param map YAML のリスト要素
     * @return 商品設定。不正な値は 0 / 空文字として扱う
     */
    public static ShopItemConfig fromMap(Map<?, ?> map) {
        if (map == null) {
            return new ShopItemConfig("", 0L, -1);
        }
        String itemId = text(map.get("item-id"));
        if (itemId.isBlank()) {
            itemId = text(map.get("item_id"));
        }
        long price = number(map.get("price"), 0L);
        int slot = (int) number(map.get("slot"), -1L);
        return new ShopItemConfig(itemId, price, slot);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
