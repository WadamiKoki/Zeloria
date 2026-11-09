package com.zeloria.gui;

import com.zeloria.item.VirtualItem;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * GUI を開閉した際のカーソル上のアイテムを整理するリスナー。
 * <p>
 * クリエイティブモードでは、GUI を閉じたときにカーソル上のアイテムが
 * プレイヤーのインベントリへ返却される。Zeloria の GUI は表示専用の
 * 仮想アイテムを含むため、返却されたアイテムが実体化・複製されるのを防ぐ。
 * <ul>
 *     <li>Zeloria の GUI を開いたタイミングでカーソルを消去する</li>
 *     <li>Zeloria の GUI を閉じたタイミングで、仮想アイテムまたは GUI 内に
 *         存在したアイテムがカーソルに残っている場合は消去する</li>
 *     <li>Zeloria の GUI 表示中は {@link InventoryCreativeEvent} を拒否する</li>
 * </ul>
 */
public final class GuiCursorListener implements Listener {

    public GuiCursorListener() {
    }

    /**
     * Zeloria の GUI を開いたときにカーソルを空にする。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (isZeloriaGui(event.getView().getTopInventory())) {
            player.setItemOnCursor(null);
        }
    }

    /**
     * Zeloria の GUI を閉じたときに、GUI 由来のアイテムがカーソルへ
     * 返却されていた場合は消去する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory closed = event.getView().getTopInventory();
        if (!isZeloriaGui(closed)) {
            return;
        }

        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        // 開く時点でカーソルを空にし、表示中のクリックも拒否しているため、
        // 閉じた時点でカーソルに残っているアイテムは GUI 由来の表示物とみなして破棄する。
        player.setItemOnCursor(null);
    }

    /**
     * Zeloria の GUI 表示中に発生するクリエイティブモードのアイテム操作を拒否する。
     * <p>
     * クリエイティブインベントリから GUI へアイテムをドロップすると、カーソルに
     * 表示用アイテムが残る場合があるため、GUI 表示中は一律で拒否する。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (isZeloriaGui(event.getView().getTopInventory())
                || VirtualItem.isVirtual(event.getCursor())) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    private static boolean isZeloriaGui(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof ZeloriaGuiHolder;
    }
}
