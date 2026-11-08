package com.zeloria.player;

import com.zeloria.gathering.GatheringType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * ギャザリングのレベル・経験値マップを JSON 文字列へ変換するユーティリティ。
 * <p>
 * DB の {@code gathering_levels_json} / {@code gathering_exps_json} に
 * 1 カラムのテキストとして保存する。キーは {@link GatheringType#key()} を使用し、
 * 壊れた値は読み飛ばして既定値（レベル 1 / 経験値 0）で補完する。
 */
final class GatheringJson {

    private GatheringJson() {
    }

    /**
     * レベルマップを JSON 文字列へ変換する。
     *
     * @param levels ギャザリング種別ごとのレベル
     * @return JSON 文字列
     */
    static String encodeLevels(Map<GatheringType, Integer> levels) {
        Map<String, Integer> values = new HashMap<>();
        if (levels != null) {
            levels.forEach((type, level) -> {
                if (type != null && level != null) {
                    values.put(type.key(), Math.max(1, level));
                }
            });
        }
        return ClassProgressJson.encodeIntMap(values);
    }

    /**
     * 経験値マップを JSON 文字列へ変換する。
     *
     * @param exps ギャザリング種別ごとの経験値
     * @return JSON 文字列
     */
    static String encodeExps(Map<GatheringType, Long> exps) {
        Map<String, Long> values = new HashMap<>();
        if (exps != null) {
            exps.forEach((type, exp) -> {
                if (type != null && exp != null) {
                    values.put(type.key(), Math.max(0L, exp));
                }
            });
        }
        return ClassProgressJson.encodeLongMap(values);
    }

    /**
     * JSON 文字列からレベルマップを復元する。
     *
     * @param json JSON 文字列（{@code null} / 空可）
     * @return 復元されたマップ（未指定の種別は含まれない）
     */
    static Map<GatheringType, Integer> decodeLevels(String json) {
        Map<GatheringType, Integer> result = new EnumMap<>(GatheringType.class);
        for (Map.Entry<String, Integer> entry : ClassProgressJson.decodeIntMap(json).entrySet()) {
            GatheringType type = GatheringType.fromKey(entry.getKey());
            if (type != null) {
                result.put(type, Math.max(1, entry.getValue()));
            }
        }
        return result;
    }

    /**
     * JSON 文字列から経験値マップを復元する。
     *
     * @param json JSON 文字列（{@code null} / 空可）
     * @return 復元されたマップ（未指定の種別は含まれない）
     */
    static Map<GatheringType, Long> decodeExps(String json) {
        Map<GatheringType, Long> result = new EnumMap<>(GatheringType.class);
        for (Map.Entry<String, Long> entry : ClassProgressJson.decodeLongMap(json).entrySet()) {
            GatheringType type = GatheringType.fromKey(entry.getKey());
            if (type != null) {
                result.put(type, Math.max(0L, entry.getValue()));
            }
        }
        return result;
    }
}
