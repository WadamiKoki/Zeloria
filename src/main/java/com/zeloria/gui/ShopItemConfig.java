package com.zeloria.gui;

import java.util.Map;

/**
 * 店舗の商品 1 件分の設定。
 * <p>
 * NPC の定義ファイル（{@code EntityData/npc/*.yml}）の {@code shop.items} に
 * 次の形式で記述する。
 * <pre>
 * shop:
 *   template: "sell"
 *   items:
 *     - item-id: "iron_sword"
 *       price: 500
 * </pre>
 * {@code slot} は省略可能。未指定（{@link #AUTO_SLOT}）の場合は
 * {@link ShopConfigService} が商品エリアのスロットへ記述順に自動配置する。
 *
 * @param itemId 商品のアイテム ID
 * @param price  販売価格（1 個あたり）
 * @param slot   商品を表示する GUI スロット（未指定は {@link #AUTO_SLOT}）
 */
public record ShopItemConfig(String itemId, long price, int slot) {

    /** スロット未指定（自動配置）を表す値。 */
    public static final int AUTO_SLOT = -1;

    public ShopItemConfig {
        itemId = itemId == null ? "" : itemId.trim();
        price = Math.max(0L, price);
    }

    /**
     * 商品として有効か判定する。
     * <p>
     * スロットは省略可能なため、判定対象に含めない。
     */
    public boolean isValid() {
        return !itemId.isBlank() && price > 0L;
    }

    /**
     * スロットを割り当てた複製を返す。
     *
     * @param slot 割り当てる GUI スロット
     * @return スロットを差し替えた商品設定
     */
    public ShopItemConfig withSlot(int slot) {
        return new ShopItemConfig(itemId, price, slot);
    }

    /**
     * YAML の 1 エントリから商品設定を生成する。
     *
     * @param map YAML のリスト要素
     * @return 商品設定。不正な値は 0 / 空文字 / {@link #AUTO_SLOT} として扱う
     */
    public static ShopItemConfig fromMap(Map<?, ?> map) {
        if (map == null) {
            return new ShopItemConfig("", 0L, AUTO_SLOT);
        }
        String itemId = text(map.get("item-id"));
        if (itemId.isBlank()) {
            itemId = text(map.get("item_id"));
        }
        long price = number(map.get("price"), 0L);
        int slot = (int) number(map.get("slot"), AUTO_SLOT);
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
