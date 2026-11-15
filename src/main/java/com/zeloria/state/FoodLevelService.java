package com.zeloria.state;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.zeloria.Zeloria;

/**
 * プレイヤーの食料ゲージを常時最大に保つ。
 * 参加時は即時適用、以降は20tick毎に最大値でなければ再適用する。
 */
public final class FoodLevelService {

    private static final long TICK_INTERVAL = 20L;

    private final Zeloria plugin;
    private BukkitTask task;

    public FoodLevelService(Zeloria plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (this.task != null) {
            return;
        }
        this.task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            for (Player player : this.plugin.getServer().getOnlinePlayers()) {
                tick(player);
            }
        }, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    public void applyFull(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }

    private void tick(Player player) {
        if (player.getFoodLevel() >= 20 && player.getSaturation() >= 20f) {
            return;
        }
        applyFull(player);
    }
}
