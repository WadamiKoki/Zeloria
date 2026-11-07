package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;

/**
 * 独自メニュー GUI（{@code /menu}）のイベントを監視するリスナー。
 * <p>
 * 対象インベントリの判別は {@link MenuGui}（{@link InventoryHolder}）で行うため、
 * 他のプラグインやバニラのインベントリには一切干渉しない。
 * <ul>
 *     <li>{@link InventoryClickEvent}: 装備の着脱・メニュー機能呼び出しを
 *         {@link MenuService} へ委譲</li>
 *     <li>{@link InventoryDragEvent}: メニュー GUI へアイテムをドラッグできないよう拒否</li>
 *     <li>{@link InventoryCloseEvent}: メニューを閉じた際に RPG ステータスを再適用</li>
 * </ul>
 */
public class MenuListener implements Listener {

    private final Zeloria plugin;

    public MenuListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * メニュー GUI のクリックを処理する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        MenuGui menu = resolve(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }

        MenuService menuService = plugin.getMenuService();
        if (menuService == null) {
            // サービス未初期化時は安全側に倒してすべて拒否する。
            event.setCancelled(true);
            return;
        }
        menuService.handleClick(event, menu);
    }

    /**
     * メニュー GUI へのドラッグを拒否する。
     * <p>
     * プレイヤーインベントリ内で完結するドラッグは通常どおり許可する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        MenuGui menu = resolve(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * メニュー GUI を閉じたタイミングでステータスを再適用する。
     * <p>
     * メニュー内で装備を変更した場合、閉じた直後に移動速度・HP / MP 表示などが
     * 最新の装備補正へ追従するようにする。装備補正そのものの再計算は
     * {@code EquipmentListener} が担当する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof MenuGui)) {
            return;
        }
        if (plugin.getPlayerStatsService() != null) {
            plugin.getPlayerStatsService().apply(player);
        }
    }

    /**
     * インベントリのホルダーからメニュー GUI を解決する。
     *
     * @param inventory 対象インベントリ
     * @return メニュー GUI。対象外の場合は {@code null}
     */
    private static MenuGui resolve(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof MenuGui menu ? menu : null;
    }
}
