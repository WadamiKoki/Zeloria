package com.zeloria.skill;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * クラス／スキル定義 YAML の共通パーサ。
 * <p>
 * MiniMessage の解決、Material 名の検証、ID の正規化など、
 * データ駆動型定義の読み込みで共通する処理をまとめる。
 */
final class DefinitionParser {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private DefinitionParser() {
    }

    /**
     * MiniMessage 文字列を {@link Component} へ解決する。
     *
     * @param raw      YAML の値
     * @param fallback 値が空の場合に使用する MiniMessage 文字列
     */
    static Component component(String raw, String fallback) {
        return MINI.deserialize(text(raw, fallback));
    }

    /**
     * {@code description} を MiniMessage 解決済みの行リストへ変換する。
     * <p>
     * 文字列（1 行）とリスト（複数行）の両方を受け付ける。
     *
     * @param raw YAML の値
     */
    static List<Component> description(Object raw) {
        return switch (raw) {
            case String text when !text.isBlank() -> List.of(MINI.deserialize(text));
            case List<?> list -> {
                List<Component> lines = new ArrayList<>();
                for (Object element : list) {
                    if (element instanceof String line && !line.isBlank()) {
                        lines.add(MINI.deserialize(line));
                    }
                }
                yield List.copyOf(lines);
            }
            case null, default -> List.of();
        };
    }

    /**
     * Material 名を解決する。不正な場合は警告を出して {@code fallback} を返す。
     *
     * @param raw      YAML の値
     * @param fallback 解決できなかった場合の Material
     * @param context  警告表示用のコンテキスト（例: {@code class archer}）
     * @param logger   警告の出力先
     */
    static Material material(String raw, Material fallback, String context, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            logger.warning("Material 名が不正です: '" + raw + "' (" + context + ")"
                    + " - " + fallback.name() + " を使用します。");
            return fallback;
        }
        return material;
    }

    /**
     * 空文字・{@code null} をフォールバックへ置き換える。
     */
    static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * ID を小文字・前後空白なしへ正規化する。
     */
    static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 相対パスから拡張子なしのファイル名を取得する（ID のフォールバック用）。
     */
    static String fileName(String key) {
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
