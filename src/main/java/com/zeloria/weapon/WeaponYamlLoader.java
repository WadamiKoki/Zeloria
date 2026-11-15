package com.zeloria.weapon;

import java.io.File;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;

import com.zeloria.util.NamespacedKeys;

/**
 * 武器YMLを読み込み WeaponDefinition を構築する。
 * material / damage / headshot_multiplier / sounds を解決する。
 */
public final class WeaponYamlLoader {

    public WeaponDefinition load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("武器YMLに id がありません: " + file.getName());
        }

        String displayName = yaml.getString("name", id);
        Material material = resolveMaterial(yaml.getString("material", "minecraft:crossbow"), file);
        double damage = yaml.getDouble("damage", 25.0);
        double headshotMultiplier = yaml.getDouble("headshot_multiplier", 2.0);
        int magazineSize = Math.max(1, yaml.getInt("magazine_size", 12));
        List<String> lore = yaml.getStringList("lore");
        Integer customModelData = yaml.contains("custom_model_data")
                ? Integer.valueOf(yaml.getInt("custom_model_data"))
                : null;

        WeaponDefinition.Sounds sounds = new WeaponDefinition.Sounds(
                yaml.getString("sounds.fire", null),
                yaml.getString("sounds.reload", null));

        return new WeaponDefinition(id, displayName, material, damage, headshotMultiplier,
                magazineSize, lore, customModelData, sounds);
    }

    private Material resolveMaterial(String value, File file) {
        NamespacedKey key = NamespacedKeys.parse(value);
        Material material = key == null ? null : Registry.MATERIAL.get(key);
        if (material == null) {
            throw new IllegalArgumentException(
                    "不明なマテリアル '" + value + "' (ファイル: " + file.getName() + ")");
        }
        return material;
    }
}
