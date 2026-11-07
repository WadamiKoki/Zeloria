package com.zeloria.item;

import com.zeloria.core.Zeloria;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * カスタムアイテムを材料にしたクラフトを禁止するリスナー。
 * <p>
 * 武器・防具・素材などのカスタムアイテムは {@link ItemService} が PDC の
 * {@code zeloria:item_id} で識別する。バニラの素材へ戻したり、他のレシピの材料に
 * 使ったりできないよう、クラフト結果が確定する前にキャンセルする。
 */
public final class CustomItemCraftListener implements Listener {

    private final Zeloria plugin;

    public CustomItemCraftListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * クラフト材料にカスタムアイテムが含まれる場合、クラフトをキャンセルする。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            return;
        }

        CraftingInventory inventory = (CraftingInventory) event.getInventory();
        for (ItemStack ingredient : inventory.getMatrix()) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            if (itemService.getItemId(ingredient).isPresent()) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
