package com.zeloria.config;

import java.io.File;

import org.bukkit.configuration.file.YamlConfiguration;

import com.zeloria.Zeloria;

/**
 * config.yml を読み込む。
 * 内部HP最大値（combat.internal-max-hp）をキャッシュする。
 */
public final class ZeloriaConfig {

    private final double internalMaxHp;

    public ZeloriaConfig(Zeloria plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        this.internalMaxHp = yaml.getDouble("combat.internal-max-hp", 100.0);
    }

    public double getInternalMaxHp() {
        return this.internalMaxHp;
    }
}
