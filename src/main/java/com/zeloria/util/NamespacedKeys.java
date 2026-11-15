package com.zeloria.util;

import org.bukkit.NamespacedKey;

/**
 * 文字列から NamespacedKey を解決するユーティリティ。
 * ":" を含まなければ minecraft 名前空間として扱う。
 */
public final class NamespacedKeys {

    private NamespacedKeys() {}

    public static NamespacedKey parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.contains(":")) {
                return NamespacedKey.fromString(value);
            }
            return NamespacedKey.minecraft(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
