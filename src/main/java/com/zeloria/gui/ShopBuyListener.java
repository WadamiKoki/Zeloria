package com.zeloria.gui;

import com.zeloria.item.VirtualInventoryService;
import com.zeloria.item.VirtualItem;
import com.zeloria.player.VirtualInventoryTab;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * {@link ShopBuyGui} のクリック・ドラッグ・クローズを処理するリスナー。
 * <p>
 * 売却枠は仮想表示専用のため、すべてのクリックをキャンセルする。売却枠をクリックすると
 * 予約を解除する。プレイヤーの仮想ストレージ（メインインベントリ 9〜35）のアイテムを
 * クリックすると、物理移動せずに売却予約へ追加する。GUI を閉じた場合は予約を破棄するだけで、
 * 仮想ストレージの実体には触れない。
 */
public class ShopBuyListener implements Listener {

    public ShopBuyListener() {
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        ShopBuyGui gui = resolve(view.getTopInventory());
        if (gui == null) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = view.getTopInventory().getSize();

        // 売却枠・装飾枠・ボタンのいずれも物理配置は許可しない。
        // シフトクリックやドラッグによる移動もここで拒否する。
        GuiClickSupport.deny(event);

        if (rawSlot >= 0 && rawSlot < topSize) {
            // 売却枠は仮想表示のみ。ボタン・売却枠ともバニラ移動は許可しない。
            gui.handleClick(rawSlot);
            return;
        }

        if (rawSlot < 0) {
            return;
        }

        // 仮想ストレージ（メインインベントリ 9〜35）の表示アイテムだけを予約対象にする。
        // VirtualInventoryListener が LOWEST でキャンセル済みでも処理できるよう、
        // このリスナーは ignoreCancelled を付けずに受け取る。
        int playerSlot = view.convertSlot(rawSlot);
        if (!VirtualInventoryService.isBagSlot(playerSlot)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (!VirtualItem.isVirtual(clicked)) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        String key = VirtualInventoryService.bagEntryKey(clicked);
        VirtualInventoryTab tab = VirtualInventoryService.bagTabOf(clicked);
        if (key == null || tab == null) {
            return;
        }

        gui.reserveFromInventory(clicked);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ShopBuyGui gui = resolve(event.getView().getTopInventory());
        if (gui == null) {
            return;
        }
        // 装飾枠・ボタン・売却枠へのアイテム移動をすべて拒否する。
        GuiClickSupport.deny(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        ShopBuyGui gui = resolve(event.getView().getTopInventory());
        if (gui != null) {
            // 予約状態をクリアするだけ。仮想ストレージからの返還・ドロップは行わない。
            gui.handleClose();
        }
    }

    private static ShopBuyGui resolve(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        return inventory.getHolder() instanceof ShopBuyGui gui ? gui : null;
    }
}

