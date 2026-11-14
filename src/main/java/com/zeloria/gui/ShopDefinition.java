package com.zeloria.gui;

import java.util.List;

/**
 * {@code config/ShopData/*.yml} 1 ファイル分の店舗定義。
 *
 * @param id    店舗 ID（YAML の {@code id}、未指定時はファイル名）
 * @param items 販売する商品一覧
 */
public record ShopDefinition(String id, List<ShopItemConfig> items) {

    public ShopDefinition {
        id = id == null ? "" : id.trim();
        items = items == null ? List.of() : List.copyOf(items);
    }
}
