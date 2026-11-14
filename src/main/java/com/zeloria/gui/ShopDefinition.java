package com.zeloria.gui;

import java.util.List;

/**
 * NPC の YAML（{@code shop} セクション）1 件分の店舗定義。
 *
 * @param id    NPC ID（商品を定義した NPC の識別子）
 * @param items 販売する商品一覧（スロット割り当て済み）
 */
public record ShopDefinition(String id, List<ShopItemConfig> items) {

    public ShopDefinition {
        id = id == null ? "" : id.trim();
        items = items == null ? List.of() : List.copyOf(items);
    }
}
