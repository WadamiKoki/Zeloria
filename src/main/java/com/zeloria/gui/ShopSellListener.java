package com.zeloria.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * {@link ShopSellGui} のクリック・ドラッグ・クローズを処理するリスナー。
 * <p>
 * 販売屋 GUI の表示中は、商品枠・装飾枠・ボタン枠へのクリック、シフトクリック・ドラッグ、
 * プレイヤーインベントリ側の物理アイテム操作をすべて拒否する。
 * 商品枠をクリックした場合は、選択中の数量で即時購入する。
 */
public class ShopSellListener implements Listener {

    public ShopSellListener() {
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        ShopSellGui gui = resolve(view.getTopInventory());
        if (gui == null) {
            return;
        }

        // GUI 内・プレイヤーインベントリ側とも物理移動は許可しない。
        GuiClickSupport.deny(event);

        int rawSlot = event.getRawSlot();
        int topSize = view.getTopInventory().getSize();
        if (rawSlot >= 0 && rawSlot < topSize) {
            gui.handleClick(rawSlot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ShopSellGui gui = resolve(event.getView().getTopInventory());
        if (gui == null) {
            return;
        }
        GuiClickSupport.deny(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        ShopSellGui gui = resolve(event.getView().getTopInventory());
        if (gui != null) {
            gui.handleClose();
        }
    }

    private static ShopSellGui resolve(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        return inventory.getHolder() instanceof ShopSellGui gui ? gui : null;
    }
}
