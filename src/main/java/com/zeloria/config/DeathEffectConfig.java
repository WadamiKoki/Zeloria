package com.zeloria.config;

import java.io.File;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;

import com.zeloria.Zeloria;
import com.zeloria.util.NamespacedKeys;

/**
 * death_effects.yml を読み込む。
 * 死亡演出（タイトル/カウントダウン/サウンド/復活時間）を解決して保持する。
 */
public final class DeathEffectConfig {

    private final String title;
    private final String subtitle;
    private final int titleFadeInTicks;
    private final int titleStayTicks;
    private final int titleFadeOutTicks;
    private final int respawnWaitSeconds;
    private final int invulnerableSeconds;
    private final Sound deathSound;
    private final Sound countdownSound;
    private final Sound respawnSound;

    public DeathEffectConfig(Zeloria plugin) {
        File file = new File(plugin.getDataFolder(), "death_effects.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.title = yaml.getString("death.title", "死亡してしまった！");
        this.subtitle = yaml.getString("death.subtitle", "復活まで %seconds% 秒");
        this.titleFadeInTicks = yaml.getInt("death.title_fade_in_ticks", 10);
        this.titleStayTicks = yaml.getInt("death.title_stay_ticks", 40);
        this.titleFadeOutTicks = yaml.getInt("death.title_fade_out_ticks", 10);
        this.respawnWaitSeconds = Math.max(1, yaml.getInt("death.respawn_wait_seconds", 5));
        this.invulnerableSeconds = Math.max(0, yaml.getInt("death.invulnerable_seconds", 3));
        this.deathSound = resolveSound(yaml.getString("death.sounds.death", "minecraft:entity.player.death"));
        this.countdownSound = resolveSound(yaml.getString("death.sounds.countdown", "minecraft:block.note_block.pling"));
        this.respawnSound = resolveSound(yaml.getString("death.sounds.respawn", "minecraft:entity.player.levelup"));
    }

    private Sound resolveSound(String value) {
        NamespacedKey key = NamespacedKeys.parse(value);
        return key == null ? null : Registry.SOUND_EVENT.get(key);
    }

    public String getTitle() { return this.title; }
    public String getSubtitleTemplate() { return this.subtitle; }
    public int getTitleFadeInTicks() { return this.titleFadeInTicks; }
    public int getTitleStayTicks() { return this.titleStayTicks; }
    public int getTitleFadeOutTicks() { return this.titleFadeOutTicks; }
    public int getRespawnWaitSeconds() { return this.respawnWaitSeconds; }
    public int getInvulnerableSeconds() { return this.invulnerableSeconds; }
    public Sound getDeathSound() { return this.deathSound; }
    public Sound getCountdownSound() { return this.countdownSound; }
    public Sound getRespawnSound() { return this.respawnSound; }
}
