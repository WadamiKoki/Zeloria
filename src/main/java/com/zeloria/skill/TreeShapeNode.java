package com.zeloria.skill;

import org.bukkit.Material;

/**
 * クラスツリー共通形状の 1 ノード。
 * <p>
 * クラスごとの効果や解放条件は {@code config/ClassTree/<classId>.yml} が持ち、
 * このレコードは全クラスで共有する「位置」と「アイコン」だけを表す。
 *
 * @param id     ノード ID
 * @param row    ツリー上の行（0 始まり。画面外の行も指定できる）
 * @param column ツリー上の列（0〜8）
 * @param icon   未解放時の表示アイコン
 * @param customModelData リソースパック用の CustomModelData（0 以下は未設定）
 */
public record TreeShapeNode(
        String id,
        int row,
        int column,
        Material icon,
        int customModelData
) {

    public TreeShapeNode {
        id = DefinitionParser.normalizeId(id);
        row = Math.max(0, row);
        column = Math.max(0, Math.min(8, column));
        icon = icon == null ? Material.PAPER : icon;
        customModelData = Math.max(0, customModelData);
    }

    /**
     * スクロール位置を考慮した GUI スロット番号を取得する。
     *
     * @param scrollOffset 先頭から非表示にする行数
     * @return スロット番号
     */
    public int slot(int scrollOffset) {
        return (row - Math.max(0, scrollOffset)) * 9 + column;
    }

    /**
     * 指定スクロール位置で表示範囲内にあるか判定する。
     *
     * @param scrollOffset 先頭から非表示にする行数
     * @param visibleRows  表示する行数
     * @return 表示範囲内なら {@code true}
     */
    public boolean visible(int scrollOffset, int visibleRows) {
        int displayRow = row - Math.max(0, scrollOffset);
        return displayRow >= 0 && displayRow < Math.max(0, visibleRows);
    }
}
