package com.zeloria.world;

import com.zeloria.core.Zeloria;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.Objects;

/**
 * プレイヤーがレイヤーワールドへ移動したことを検知し、入場演出を再生するリスナー。
 * <p>
 * {@link PlayerChangedWorldEvent} は通常テレポート・ポータルワープ・ログイン後の
 * Hub 移動など、ワールドをまたぐ移動全般で発火する。
 */
public class LayerEntryListener implements Listener {

    private final Zeloria plugin;

    public LayerEntryListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        LayerEffectService effectService = plugin.getLayerEffectService();
        if (effectService == null) {
            return;
        }
        effectService.playEntry(event.getPlayer(), event.getPlayer().getWorld());
    }
}
