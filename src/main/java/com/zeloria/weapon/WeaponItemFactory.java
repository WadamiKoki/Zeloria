package com.zeloria.weapon;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.zeloria.Zeloria;

/**
 * WeaponDefinition から実体 ItemStack を生成する。
 * PDC に weapon_id を埋め込み、残弾を耐久値（初期値 = magazine_size）で表す。
 */
public final class WeaponItemFactory {

    private final Zeloria plugin;
    private final NamespacedKey weaponIdKey;

    public WeaponItemFactory(Zeloria plugin) {
        this.plugin = plugin;
        this.weaponIdKey = new NamespacedKey(plugin, WeaponDefinition.PDC_WEAPON_ID_KEY);
    }

    public ItemStack create(WeaponDefinition definition) {
        ItemStack item = new ItemStack(definition.getMaterial(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(Component.text(definition.getDisplayName()));

        List<String> rawLore = definition.getLore();
        if (!rawLore.isEmpty()) {
            List<Component> lore = new ArrayList<>(rawLore.size());
            for (String line : rawLore) {
                lore.add(Component.text(line));
            }
            meta.lore(lore);
        }

        if (definition.getCustomModelData() != null) {
            meta.setCustomModelData(definition.getCustomModelData());
        }

        meta.getPersistentDataContainer().set(this.weaponIdKey, PersistentDataType.STRING, definition.getId());

        short maxDurability = definition.getMaterial().getMaxDurability();
        if (maxDurability > 0 && meta instanceof Damageable damageable) {
            damageable.setDamage(Math.max(0, maxDurability - definition.getMagazineSize()));
        }

        item.setItemMeta(meta);
        return item;
    }
}
