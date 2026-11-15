package com.zeloria.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.zeloria.Zeloria;
import com.zeloria.state.FoodLevelService;
import com.zeloria.state.InternalHealthService;

/**
 * 参加時に内部HPを最大へリセットし、食料ゲージを最大にする。
 */
public final class PlayerJoinListener implements Listener {

    private final InternalHealthService internalHealthService;
    private final FoodLevelService foodLevelService;

    public PlayerJoinListener(Zeloria plugin, InternalHealthService internalHealthService,
                              FoodLevelService foodLevelService) {
        this.internalHealthService = internalHealthService;
        this.foodLevelService = foodLevelService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.internalHealthService.reset(player);
        this.foodLevelService.applyFull(player);
    }
}
