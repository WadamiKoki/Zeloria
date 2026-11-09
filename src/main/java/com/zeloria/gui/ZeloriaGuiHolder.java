package com.zeloria.gui;

import org.bukkit.inventory.InventoryHolder;

/**
 * Zeloria が独自に生成する GUI のマーカー。
 * <p>
 * マインクラフトのクリエイティブモードでは、GUI を閉じた際にカーソル上のアイテムが
 * プレイヤーのインベントリへ返却される場合がある。Zeloria の GUI は表示専用の
 * 仮想アイテムを多数含むため、{@link GuiCursorListener} がこのマーカーを使って
 * 返却対象を判別し、カーソルを確実に消去する。
 * <p>
 * {@link InventoryHolder#getInventory()} は各 GUI 実装側で提供する。
 */
public interface ZeloriaGuiHolder extends InventoryHolder {
}
