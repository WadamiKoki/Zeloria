package com.zeloria.state;

import org.bukkit.GameRules;
import org.bukkit.World;

import com.zeloria.Zeloria;

/**
 * ワールドのゲームルールを適用する。
 * onEnable で全ワールド、WorldLoadEvent で新規ワールドに適用する。
 */
public final class GameRuleConfigurator {

    private final Zeloria plugin;

    public GameRuleConfigurator(Zeloria plugin) {
        this.plugin = plugin;
    }

    public void applyAll() {
        for (World world : this.plugin.getServer().getWorlds()) {
            apply(world);
        }
    }

    public void apply(World world) {
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
    }
}
