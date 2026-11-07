package com.zeloria.player;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * クラス別進行状況を JSON 形式の文字列へ変換するユーティリティ。
 * <p>
 * DB には 1 カラムのテキストとして保存する。外部ライブラリを追加せず、
 * クラス ID（英数字・ハイフン・アンダースコア）だけをキーとする単純な
 * JSON オブジェクトを読み書きする。壊れた値は読み飛ばし、空のマップとして扱う。
 */
final class ClassProgressJson {

    private static final Pattern NUMBER_ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)");

    private static final Pattern ARRAY_ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);

    private static final Pattern ARRAY_ITEM =
            Pattern.compile("\"([^\"]*)\"");

    private ClassProgressJson() {
    }

    /**
     * 整数マップを JSON 文字列へ変換する（キー順は安定）。
     */
    static String encodeIntMap(Map<String, Integer> values) {
        Map<String, Integer> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    sorted.put(key, Math.max(0, value));
                }
            });
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return builder.append('}').toString();
    }

    /**
     * 長整数マップを JSON 文字列へ変換する（キー順は安定）。
     */
    static String encodeLongMap(Map<String, Long> values) {
        Map<String, Long> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    sorted.put(key, Math.max(0L, value));
                }
            });
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return builder.append('}').toString();
    }

    /**
     * 文字列セットのマップを JSON 文字列へ変換する（キー・値とも安定した順序）。
     */
    static String encodeSetMap(Map<String, ? extends Set<String>> values) {
        Map<String, Set<String>> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    sorted.put(key, new TreeSet<>(value));
                }
            });
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Set<String>> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":[");
            boolean firstItem = true;
            for (String item : entry.getValue()) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                if (!firstItem) {
                    builder.append(',');
                }
                firstItem = false;
                builder.append('"').append(item).append('"');
            }
            builder.append(']');
        }
        return builder.append('}').toString();
    }

    /**
     * JSON 文字列から整数マップを復元する。
     */
    static Map<String, Integer> decodeIntMap(String json) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        Matcher matcher = NUMBER_ENTRY.matcher(json);
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(2));
                result.put(matcher.group(1), Math.max(0, value));
            } catch (NumberFormatException ignored) {
                // 壊れた値は読み飛ばす。
            }
        }
        return result;
    }

    /**
     * JSON 文字列から長整数マップを復元する。
     */
    static Map<String, Long> decodeLongMap(String json) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        Matcher matcher = NUMBER_ENTRY.matcher(json);
        while (matcher.find()) {
            try {
                long value = Long.parseLong(matcher.group(2));
                result.put(matcher.group(1), Math.max(0L, value));
            } catch (NumberFormatException ignored) {
                // 壊れた値は読み飛ばす。
            }
        }
        return result;
    }

    /**
     * JSON 文字列から文字列セットのマップを復元する。
     */
    static Map<String, Set<String>> decodeSetMap(String json) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        Matcher matcher = ARRAY_ENTRY.matcher(json);
        while (matcher.find()) {
            Set<String> values = new LinkedHashSet<>();
            Matcher itemMatcher = ARRAY_ITEM.matcher(matcher.group(2));
            while (itemMatcher.find()) {
                String item = itemMatcher.group(1);
                if (item != null && !item.isBlank()) {
                    values.add(item.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (!values.isEmpty()) {
                result.put(matcher.group(1), values);
            }
        }
        return result;
    }
}
