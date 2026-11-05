package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

/**
 * {@link SimpleGui} のクリック・ドラッグを処理するリスナー。
 * <p>
 * アイテムの持ち出しを防ぐため、{@link SimpleGui} が開かれているインベントリへの
 * 操作はすべてキャンセルする。
 */
public class SimpleGuiListener implements Listener {

    private static final String SOUND_CLICK = "click";

    private final Zeloria plugin;

    public SimpleGuiListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        SimpleGui gui = resolve(event.getView().getTopInventory());
        if (gui == null) {
            return;
        }

        event.setCancelled(true);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof SimpleGui)) {
            return;
        }

        if (gui.isCloseSlot(event.getSlot()) && event.getWhoClicked() instanceof Player player) {
            GuiConfigService guiConfigService = plugin.getGuiConfigService();
            if (guiConfigService != null) {
                guiConfigService.playSound(player, SOUND_CLICK);
            }
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (resolve(event.getView().getTopInventory()) == null) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            // rawSlot が GUI 側（上半分）に含まれる場合のみキャンセルする。
            // プレイヤーインベントリ内のドラッグは妨げない。
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static SimpleGui resolve(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SimpleGui gui ? gui : null;
    }
}
