package com.zeloria.item;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.zeloria.core.Zeloria;
import com.zeloria.gui.MenuGui;
import com.zeloria.item.VirtualItem;
import com.zeloria.core.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;

/**
 * 装備まわりのイベントを監視するリスナー。
 * <ul>
 *     <li>バニラの防具装着・オフハンド交換を拒否し、装備は {@code /menu} に一本化する</li>
 *     <li>メニューを閉じたタイミングで {@link EquipmentService#recompute(Player)} を実行し、
 *         着脱結果をステータスへ反映する</li>
 * </ul>
 * 装着可否（必要レベル）の判定は {@link EquipmentService} に委譲する。
 */
public class EquipmentListener implements Listener {

    /** メッセージ定義ファイル（{@code MessageData/equipment.yml}）。 */
    private static final String MESSAGE_FILE = "equipment";

    private final Zeloria plugin;

    public EquipmentListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * バニラの防具装着（防具スロットのクリック）を拒否する。
     * <p>
     * {@link PlayerArmorChangeEvent} はキャンセルできないため、
     * 装着の入り口となる {@link InventoryClickEvent} の防具スロットをここで塞ぐ。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorSlotClick(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 防具スロットへの装着は /menu 経由に限定する。
        event.setCancelled(true);
        message(player, "armor_blocked", Map.of());
    }

    /**
     * 手に持った防具を右クリックして装備する行為を拒否する。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!isArmor(player.getInventory().getItemInMainHand())
                && !isArmor(player.getInventory().getItemInOffHand())) {
            return;
        }
        // 手に持った防具からの装着も /menu 経由に限定する。
        event.setCancelled(true);
        message(player, "armor_blocked", Map.of());
    }

    /**
     * 防具が変更された後の処理。
     * <p>
     * {@link PlayerArmorChangeEvent} はキャンセルできないため、
     * 装着そのものは {@link #onArmorSlotClick(InventoryClickEvent)} と
     * {@link #onArmorInteract(PlayerInteractEvent)} で拒否する。
     * ここでは取りこぼし（プラグインによる直接装着など）に備えて補正値を再計算する。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        ItemStack newItem = event.getNewItem();
        if (newItem == null || newItem.getType().isAir()) {
            return;
        }
        // 仮想装備表示の配置による変更は、表示更新側で完結しているため再計算しない。
        if (VirtualItem.isVirtual(newItem)) {
            return;
        }
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (equipmentService != null) {
            equipmentService.recompute(event.getPlayer());
        }
    }

    /**
     * バニラのオフハンド交換を拒否する。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        message(event.getPlayer(), "offhand_blocked", Map.of());
    }

    /**
     * メニューを閉じたタイミングで装備補正を再計算する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof MenuGui)) {
            return;
        }
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (equipmentService != null) {
            equipmentService.recompute(player);
        }
    }

    /**
     * バニラの防具（ヘルメット・チェストプレート・レギンス・ブーツ）か判定する。
     *
     * @param stack 判定対象
     * @return 防具の場合は {@code true}
     */
    private static boolean isArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String material = stack.getType().name();
        return material.endsWith("_HELMET")
                || material.endsWith("_CHESTPLATE")
                || material.endsWith("_LEGGINGS")
                || material.endsWith("_BOOTS");
    }

    private void message(Player player, String key, Map<String, String> placeholders) {
        MessageService messages = plugin.getMessageService();
        if (messages == null) {
            return;
        }
        messages.actionBar(player, MESSAGE_FILE, key, placeholders);
    }
}
