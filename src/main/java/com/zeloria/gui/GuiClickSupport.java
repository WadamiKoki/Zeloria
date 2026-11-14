package com.zeloria.gui;

import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * GUI 操作用の共通ヘルパー。
 */
public final class GuiClickSupport {

    private GuiClickSupport() {
    }

    /**
     * クリックイベントをキャンセルし、結果も {@link Event.Result#DENY} に固定する。
     * <p>
     * キャンセルだけではクライアント側の予測処理により表示用アイテムがカーソルへ
     * 残ることがあるため、結果を明示的に拒否してカーソル同期のずれを防ぐ。
     *
     * @param event 対象のクリックイベント
     */
    public static void deny(InventoryClickEvent event) {
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
    }

    /**
     * ドラッグイベントをキャンセルし、結果も {@link Event.Result#DENY} に固定する。
     *
     * @param event 対象のドラッグイベント
     */
    public static void deny(InventoryDragEvent event) {
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
    }
}
