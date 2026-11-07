package com.zeloria.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仮想インベントリの Map を JSON 文字列へ変換するユーティリティ。
 * <p>
 * 外部ライブラリを追加せず、次の形式だけを読み書きする。
 * <pre>
 * {"キー":{"itemId":"...","amount":1,"serializedData":""}}
 * </pre>
 * キー・itemId・serializedData に含まれる引用符とバックスラッシュはエスケープする。
 * 壊れた値は読み飛ばし、空の Map として扱う。
 */
final class VirtualInventoryJson {

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\{"
                    + "\\s*\"itemId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,"
                    + "\\s*\"amount\"\\s*:\\s*(-?\\d+)\\s*,"
                    + "\\s*\"serializedData\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");

    private VirtualInventoryJson() {
    }

    /**
     * 仮想インベントリの Map を JSON 文字列へ変換する（キー順は安定）。
     *
     * @param entries 仮想インベントリ
     * @return JSON 文字列。空の場合は {@code "{}"}
     */
    static String encode(Map<String, VirtualItemEntry> entries) {
        Map<String, VirtualItemEntry> sorted = new TreeMap<>();
        if (entries != null) {
            entries.forEach((key, entry) -> {
                if (key != null && !key.isBlank() && entry != null) {
                    sorted.put(key, entry);
                }
            });
        }

        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, VirtualItemEntry> mapEntry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            VirtualItemEntry entry = mapEntry.getValue();
            builder.append('"').append(escape(mapEntry.getKey())).append("\":{")
                    .append("\"itemId\":\"").append(escape(entry.itemId())).append("\",")
                    .append("\"amount\":").append(entry.amount()).append(',')
                    .append("\"serializedData\":\"").append(escape(entry.serializedData())).append("\"}");
        }
        return builder.append('}').toString();
    }

    /**
     * JSON 文字列から仮想インベントリの Map を復元する。
     *
     * @param json JSON 文字列（{@code null} / 空文字可）
     * @return 復元された Map
     */
    static Map<String, VirtualItemEntry> decode(String json) {
        Map<String, VirtualItemEntry> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }

        Matcher matcher = ENTRY_PATTERN.matcher(json);
        while (matcher.find()) {
            String key = unescape(matcher.group(1));
            String itemId = unescape(matcher.group(2));
            if (key.isBlank() || itemId.isBlank()) {
                continue;
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(3));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String serializedData = unescape(matcher.group(4));
            result.put(key, new VirtualItemEntry(itemId, amount, serializedData));
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                builder.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
