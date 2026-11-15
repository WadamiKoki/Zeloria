package com.zeloria.weapon;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.zeloria.Zeloria;

/**
 * 武器定義の登録簿。
 * PDC の weapon_id を優先し、無ければ Material 照合で解決する。
 */
public final class WeaponRegistry {

    private final Zeloria plugin;
    private final NamespacedKey weaponIdKey;
    private final WeaponYamlLoader yamlLoader;
    private final Map<String, WeaponDefinition> byId = new LinkedHashMap<>();
    private final Map<Material, WeaponDefinition> byMaterial = new LinkedHashMap<>();

    public WeaponRegistry(Zeloria plugin) {
        this.plugin = plugin;
        this.weaponIdKey = new NamespacedKey(plugin, WeaponDefinition.PDC_WEAPON_ID_KEY);
        this.yamlLoader = new WeaponYamlLoader();
    }

    public void loadWeapon(File file) {
        WeaponDefinition definition = this.yamlLoader.load(file);
        register(definition);
        this.plugin.getLogger().info("武器をロードしました: " + definition.getId() + " (" + file.getName() + ")");
    }

    public void register(WeaponDefinition definition) {
        this.byId.put(definition.getId(), definition);
        this.byMaterial.put(definition.getMaterial(), definition);
    }

    /**
     * PDC 優先で武器を解決する。PDC が無い場合は Material で後方互換する。
     */
    public WeaponDefinition resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        WeaponDefinition byPdc = resolveByPdc(item);
        if (byPdc != null) {
            return byPdc;
        }
        return this.byMaterial.get(item.getType());
    }

    private WeaponDefinition resolveByPdc(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(this.weaponIdKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        return this.byId.get(id);
    }

    public WeaponDefinition get(String id) {
        return this.byId.get(id);
    }

    public Collection<WeaponDefinition> getAll() {
        return Collections.unmodifiableCollection(this.byId.values());
    }
}
