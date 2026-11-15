package com.zeloria.config;

import java.io.File;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;

import com.zeloria.Zeloria;
import com.zeloria.util.NamespacedKeys;

/**
 * hit_effects.yml を読み込む。
 * ブロック/エンティティ命中時のパーティクルとサウンドを解決して保持する。
 */
public final class HitEffectConfig {

    private final Particle blockHitParticle;
    private final int blockHitCount;
    private final Sound blockHitSound;
    private final Particle entityHitParticle;
    private final int entityHitCount;
    private final Sound entityHitSound;

    public HitEffectConfig(Zeloria plugin) {
        File file = new File(plugin.getDataFolder(), "hit_effects.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.blockHitParticle = resolveParticle(yaml.getString("block_hit.particle", "minecraft:block"));
        this.blockHitCount = yaml.getInt("block_hit.count", 10);
        this.blockHitSound = resolveSound(yaml.getString("block_hit.sound", "minecraft:block.stone.hit"));
        this.entityHitParticle = resolveParticle(yaml.getString("entity_hit.particle", "minecraft:crit"));
        this.entityHitCount = yaml.getInt("entity_hit.count", 5);
        this.entityHitSound = resolveSound(yaml.getString("entity_hit.sound", "minecraft:entity.player.attack.crit"));
    }

    private Particle resolveParticle(String value) {
        NamespacedKey key = NamespacedKeys.parse(value);
        return key == null ? null : Registry.PARTICLE_TYPE.get(key);
    }

    private Sound resolveSound(String value) {
        NamespacedKey key = NamespacedKeys.parse(value);
        return key == null ? null : Registry.SOUND_EVENT.get(key);
    }

    public Particle getBlockHitParticle() { return this.blockHitParticle; }
    public int getBlockHitCount() { return this.blockHitCount; }
    public Sound getBlockHitSound() { return this.blockHitSound; }
    public Particle getEntityHitParticle() { return this.entityHitParticle; }
    public int getEntityHitCount() { return this.entityHitCount; }
    public Sound getEntityHitSound() { return this.entityHitSound; }
}
