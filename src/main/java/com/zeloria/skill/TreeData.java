package com.zeloria.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全クラスで共有するクラスツリーの形状設定。
 * <p>
 * {@code config/TreeData/tree.yml} を読み込んで生成する。ノードの位置・アイコン・
 * 固定ボタンのスロットなど、クラスに依存しない情報だけを保持する。クラスごとの
 * 表示名・説明・コスト・解放条件・効果は {@link ClassTreeData} が持つ。
 *
 * @param title        GUI タイトル（MiniMessage）
 * @param rows         GUI の行数（1〜6）
 * @param visibleRows  スクロールせずに表示する行数（1〜rows）
 * @param fixedColumn  固定ボタン用に空ける列（この列にはノードを配置しない）
 * @param resetSlot    リセットボタンのスロット（未使用は {@code -1}）
 * @param downSlot     下スクロールボタンのスロット（未使用は {@code -1}）
 * @param upSlot       上スクロールボタンのスロット（未使用は {@code -1}）
 * @param closeSlot    閉じるボタンのスロット（未使用は {@code -1}）
 * @param nodes        ノード ID → 形状（YAML の読み込み順）
 */
public record TreeData(
        String title,
        int rows,
        int visibleRows,
        int fixedColumn,
        int resetSlot,
        int downSlot,
        int upSlot,
        int closeSlot,
        Map<String, TreeShapeNode> nodes
) {

    /** 既定のタイトル。 */
    public static final String DEFAULT_TITLE =
            "<gradient:#00c6ff:#0072ff><bold>クラスツリー</bold></gradient>";

    /** 既定の行数。 */
    public static final int DEFAULT_ROWS = 6;

    /** 既定の固定ボタン列。 */
    public static final int DEFAULT_FIXED_COLUMN = 8;

    public TreeData {
        title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        rows = clamp(rows, 1, 6);
        visibleRows = clamp(visibleRows, 1, rows);
        fixedColumn = clamp(fixedColumn, 0, 8);

        resetSlot = validSlot(resetSlot, rows, 8);
        downSlot = validSlot(downSlot, rows, 35);
        upSlot = validSlot(upSlot, rows, 26);
        closeSlot = validSlot(closeSlot, rows, rows * 9 - 1);

        Map<String, TreeShapeNode> copy = new LinkedHashMap<>();
        if (nodes != null) {
            nodes.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) {
                    copy.put(key, value);
                }
            });
        }
        nodes = Collections.unmodifiableMap(copy);
    }

    /**
     * ノード未定義の既定形状を返す。
     */
    public static TreeData defaults() {
        return new TreeData(DEFAULT_TITLE, DEFAULT_ROWS, DEFAULT_ROWS,
                DEFAULT_FIXED_COLUMN, 8, 35, 26, 53, Map.of());
    }

    /**
     * GUI の総スロット数を取得する。
     */
    public int totalSlots() {
        return rows * 9;
    }

    /**
     * 配置されているノードの最大行を取得する。
     */
    public int maxRow() {
        int max = 0;
        for (TreeShapeNode node : nodes.values()) {
            max = Math.max(max, node.row());
        }
        return max;
    }

    /**
     * 設定可能な最大スクロール量を取得する。
     * <p>
     * 一番下のノードが表示範囲の最下行に来る位置までスクロールできる。
     */
    public int maxScrollOffset() {
        return Math.max(0, maxRow() - visibleRows() + 1);
    }

    /**
     * ノード ID から共通形状を取得する。
     *
     * @param id ノード ID
     * @return 見つからない場合は {@code null}
     */
    public TreeShapeNode node(String id) {
        return nodes.get(DefinitionParser.normalizeId(id));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int validSlot(int slot, int rows, int fallback) {
        int total = rows * 9;
        if (slot >= 0 && slot < total) {
            return slot;
        }
        return fallback >= 0 && fallback < total ? fallback : -1;
    }
}
