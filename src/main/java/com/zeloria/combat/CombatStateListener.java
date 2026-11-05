package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Objects;

/**
 * ダメージイベントを監視し、プレイヤーの最終戦闘時刻を記録するリスナー。
 * <p>
 * {@link CombatStateService} は自然回復の待機判定にのみ使われ、ダメージ量の計算や
 * キャンセル判定には関与しない。そのため本リスナーは {@code MONITOR} で動作し、
 * 他のプラグイン／リスナーがダメージをキャンセルした場合は記録しない
 * （{@code ignoreCancelled = true}）。
 * <p>
 * プレイヤーがダメージを受けた場合に加え、プレイヤーが近接攻撃・投射物で
 * ダメージを与えた場合も「戦闘中」とみなす。
 */
public final class CombatStateListener implements Listener {

    private final Zeloria plugin;

    public CombatStateListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * プレイヤーがダメージを受けた場合に戦闘中として記録する。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            markInCombat(player);
        }
    }

    /**
     * プレイヤーがダメージを与えた場合に戦闘中として記録する。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker != null) {
            markInCombat(attacker);
        }
        if (event.getEntity() instanceof Player victim) {
            markInCombat(victim);
        }
    }

    private void markInCombat(Player player) {
        CombatStateService combatStateService = plugin.getCombatStateService();
        if (combatStateService != null) {
            combatStateService.markInCombat(player.getUniqueId());
        }
    }

    /**
     * ダメージ源からプレイヤーを解決する（投射物は発射者を辿る）。
     *
     * @param damager ダメージ源
     * @return プレイヤー。該当しない場合は {@code null}
     */
    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
