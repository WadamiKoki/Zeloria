package com.zeloria.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 派生スキル（{@link SkillVariantData}）が順番に実行する 1 つのアクション。
 * <p>
 * {@code type} は {@code SHOOT_PROJECTILE} / {@code DAMAGE} / {@code PARTICLE} /
 * {@code SOUND} / {@code TELEPORT} / {@code HEAL} / {@code APPLY_EFFECT} のいずれか。
 * {@code params} には {@code type} 以外の YAML キーをそのまま保持する。
 *
 * @param type   アクション種別（大文字へ正規化）
 * @param params アクションパラメータ（不変コピー）
 */
public record VariantAction(String type, Map<String, Object> params) {

    public VariantAction {
        type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> copy = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        params = Collections.unmodifiableMap(copy);
    }

    /**
     * 文字列パラメータを取得する。
     */
    public String string(String key, String fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    /**
     * 数値パラメータを取得する。数値でない場合は {@code fallback} を返す。
     */
    public double number(String key, double fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 整数パラメータを取得する。
     */
    public int integer(String key, int fallback) {
        return (int) Math.round(number(key, fallback));
    }

    /**
     * 真偽値パラメータを取得する。
     */
    public boolean bool(String key, boolean fallback) {
        Object value = params.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }
}
