package com.zeloria.npc;

import com.zeloria.core.Zeloria;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Objects;

/**
 * NPC へのダメージをすべてキャンセルするリスナー。
 * <p>
 * NPC は {@link com.zeloria.mob.MobStats} を持たないため、ダメージ計算の
 * 対象外であることに加え、このリスナーで無敵を保証する。
 */
public class NpcDamageListener implements Listener {

    private final Zeloria plugin;

    public NpcDamageListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * NPC へのダメージをキャンセルする。
     * <p>
     * 他プラグインの処理より前に無効化するため {@link EventPriority#LOWEST} で受け、
     * キャンセル済みのイベントも対象にする（{@code ignoreCancelled} は指定しない）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        NpcManager npcManager = plugin.getNpcManager();
        Entity entity = event.getEntity();
        if (npcManager != null && npcManager.isNpc(entity)) {
            event.setCancelled(true);
        }
    }
}
