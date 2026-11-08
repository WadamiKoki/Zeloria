package com.zeloria.item;

import java.util.Locale;

/**
 * アイテム種別。
 * <p>
 * 管理コマンドの引数（{@code armor} / {@code material} / {@code weapon}）と、
 * 設定フォルダ（{@code ItemData/armor/} / {@code ItemData/material/} / {@code ItemData/weapon/}）を対応付ける。
 */
public enum ItemCategory {

    /** 防具。{@code config/ItemData/armor/*.yml} */
    ARMOR("armor", "ItemData/armor"),

    /** 素材。{@code config/ItemData/material/*.yml} */
    MATERIAL("material", "ItemData/material"),

    /** 武器。{@code config/ItemData/weapon/*.yml} */
    WEAPON("weapon", "ItemData/weapon");

    private final String key;
    private final String folder;

    ItemCategory(String key, String folder) {
        this.key = key;
        this.folder = folder;
    }

    /**
     * コマンド引数として使用するキー（例: {@code material}）を取得する。
     */
    public String key() {
        return key;
    }

    /**
     * 設定フォルダ名（例: {@code ItemData/material}）を取得する。
     */
    public String folder() {
        return folder;
    }

    /**
     * {@code config/} からの相対キー接頭辞（例: {@code ItemData/material/}）を取得する。
     */
    public String keyPrefix() {
        return folder + "/";
    }

    /**
     * コマンド引数からアイテム種別を解決する（大文字小文字は区別しない）。
     *
     * @param key コマンド引数
     * @return アイテム種別。解決できない場合は {@code null}
     */
    public static ItemCategory fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (ItemCategory category : values()) {
            if (category.key.equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
