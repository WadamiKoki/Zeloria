package com.zeloria.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import com.zeloria.Zeloria;
import com.zeloria.state.GameRuleConfigurator;

/**
 * 新規ワールド読込時にゲームルールを適用する。
 */
public final class WorldLoadListener implements Listener {

    private final GameRuleConfigurator gameRuleConfigurator;

    public WorldLoadListener(Zeloria plugin, GameRuleConfigurator gameRuleConfigurator) {
        this.gameRuleConfigurator = gameRuleConfigurator;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        this.gameRuleConfigurator.apply(event.getWorld());
    }
}
