package com.zeloria.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import com.zeloria.Zeloria;
import com.zeloria.util.NamespacedKeys;

/**
 * entity_filters.yml を読み込む。
 * 弾が命中可能な EntityType 集合を保持する。
 */
public final class EntityFilterConfig {

    private final Set<EntityType> hittableEntities = new LinkedHashSet<>();

    public EntityFilterConfig(Zeloria plugin) {
        File file = new File(plugin.getDataFolder(), "entity_filters.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> list = yaml.getStringList("hittable_entities");
        for (String raw : list) {
            NamespacedKey key = NamespacedKeys.parse(raw);
            if (key == null) {
                continue;
            }
            EntityType type = Registry.ENTITY_TYPE.get(key);
            if (type != null) {
                this.hittableEntities.add(type);
            }
        }
    }

    public Set<EntityType> getHittableEntities() {
        return Collections.unmodifiableSet(this.hittableEntities);
    }

    public boolean isHittable(EntityType type) {
        return this.hittableEntities.contains(type);
    }
}
