package com.zeloria.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI で使用するマテリアルと、任意の {@code CustomModelData} の組。
 * <p>
 * YAML では次のように記述する。
 * <pre>{@code
 * materials:
 *   hotbar_tool:
 *     material: GRAY_STAINED_GLASS_PANE
 *     custom_model_data: 12345
 * }</pre>
 * {@code custom_model_data} は省略可能（0 の場合は設定しない）。
 */
public record GuiMaterial(Material material, int customModelData) {

    public static final GuiMaterial STONE = new GuiMaterial(Material.STONE, 0);
    public static final GuiMaterial GRAY_PANE =
            new GuiMaterial(Material.GRAY_STAINED_GLASS_PANE, 0);

    public GuiMaterial {
        material = material == null ? Material.STONE : material;
        customModelData = Math.max(0, customModelData);
    }

    /**
     * この設定から GUI 表示用の ItemStack を生成する。
     */
    public ItemStack createItem() {
        ItemStack item = new ItemStack(material);
        if (customModelData > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(customModelData);
                item.setItemMeta(meta);
            }
        }
        return item;
    }
}
