package com.zeloria.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;

import com.zeloria.Zeloria;
import com.zeloria.util.NamespacedKeys;

/**
 * block_filters.yml を読み込む。
 * 貫通可能ブロックの Material 集合を保持する。
 */
public final class BlockFilterConfig {

    private final Set<Material> penetrableBlocks = new LinkedHashSet<>();

    public BlockFilterConfig(Zeloria plugin) {
        File file = new File(plugin.getDataFolder(), "block_filters.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> list = yaml.getStringList("penetrable_blocks");
        for (String raw : list) {
            NamespacedKey key = NamespacedKeys.parse(raw);
            if (key == null) {
                continue;
            }
            Material material = Registry.MATERIAL.get(key);
            if (material != null) {
                this.penetrableBlocks.add(material);
            }
        }
    }

    public Set<Material> getPenetrableBlocks() {
        return Collections.unmodifiableSet(this.penetrableBlocks);
    }

    public boolean isPenetrable(Material material) {
        return this.penetrableBlocks.contains(material);
    }
}
