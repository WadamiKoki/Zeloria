package com.zeloria.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.zeloria.Zeloria;
import com.zeloria.config.DeathEffectConfig;
import com.zeloria.state.InternalHealthService;

/**
 * 内部HP0でスペクテイターへ移行し、カウントダウン後に復活させる。
 * タイトル・サウンド・無敵時間の演出もここで行う。
 */
public final class SpectatorRespawnListener implements Listener {

    private final Zeloria plugin;
    private final InternalHealthService internalHealthService;
    private final DeathEffectConfig config;
    private final Map<UUID, BukkitTask> respawnTasks = new HashMap<>();

    public SpectatorRespawnListener(Zeloria plugin, InternalHealthService internalHealthService,
                                    DeathEffectConfig config) {
        this.plugin = plugin;
        this.internalHealthService = internalHealthService;
        this.config = config;
    }

    /** InternalHealthService から呼ばれる死亡処理。 */
    public void handleDeath(Player player) {
        if (this.respawnTasks.containsKey(player.getUniqueId())) {
            return;
        }

        player.setGameMode(GameMode.SPECTATOR);
        player.sendTitle(this.config.getTitle(), formatSubtitle(this.config.getRespawnWaitSeconds()),
                this.config.getTitleFadeInTicks(), this.config.getTitleStayTicks(),
                this.config.getTitleFadeOutTicks());
        playDeathSound(player);

        startRespawnCountdown(player);
    }

    private void startRespawnCountdown(Player player) {
        UUID id = player.getUniqueId();
        int[] remaining = { this.config.getRespawnWaitSeconds() };

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player p = SpectatorRespawnListener.this.plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline()) {
                    SpectatorRespawnListener.this.respawnTasks.remove(id);
                    cancel();
                    return;
                }

                remaining[0]--;

                if (remaining[0] <= 0) {
                    respawn(p);
                    SpectatorRespawnListener.this.respawnTasks.remove(id);
                    cancel();
                    return;
                }

                p.sendTitle(SpectatorRespawnListener.this.config.getTitle(),
                        formatSubtitle(remaining[0]),
                        SpectatorRespawnListener.this.config.getTitleFadeInTicks(),
                        SpectatorRespawnListener.this.config.getTitleStayTicks(),
                        SpectatorRespawnListener.this.config.getTitleFadeOutTicks());
                playPersonalSound(p, SpectatorRespawnListener.this.config.getCountdownSound());
            }
        }.runTaskTimer(this.plugin, 20L, 20L);

        this.respawnTasks.put(id, task);
    }

    private void respawn(Player player) {
        Location respawn = player.getBedSpawnLocation();
        if (respawn == null) {
            respawn = player.getWorld().getSpawnLocation();
        }
        player.teleport(respawn);

        this.internalHealthService.reset(player);
        player.setGameMode(GameMode.SURVIVAL);
        playPersonalSound(player, this.config.getRespawnSound());

        this.internalHealthService.setInvulnerable(player, this.config.getInvulnerableSeconds());
    }

    private String formatSubtitle(int seconds) {
        return this.config.getSubtitleTemplate().replace("%seconds%", String.valueOf(seconds));
    }

    private void playDeathSound(Player player) {
        Sound sound = this.config.getDeathSound();
        if (sound != null) {
            player.getWorld().playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    private void playPersonalSound(Player player, Sound sound) {
        if (sound != null) {
            player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BukkitTask task = this.respawnTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }
}
