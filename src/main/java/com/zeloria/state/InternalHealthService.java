package com.zeloria.state;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.zeloria.Zeloria;
import com.zeloria.config.ZeloriaConfig;

/**
 * 武器に依存しない内部HPを管理する。
 * 死亡判定と復活後の無敵時間（invulnerableUntil）もここで管理する。
 */
public final class InternalHealthService {

    /** 内部HPが0になった際の死亡処理を外部（SpectatorRespawnListener）へ委譲する。 */
    public interface DeathHandler {
        void onDeath(Player player);
    }

    private final ZeloriaConfig config;
    private final Map<UUID, Double> health = new HashMap<>();
    private final Map<UUID, Long> invulnerableUntil = new HashMap<>();
    private DeathHandler deathHandler;

    public InternalHealthService(Zeloria plugin, ZeloriaConfig config) {
        this.config = config;
    }

    public void setDeathHandler(DeathHandler deathHandler) {
        this.deathHandler = deathHandler;
    }

    public double getMaxHp() {
        return this.config.getInternalMaxHp();
    }

    public double get(Player player) {
        return this.health.getOrDefault(player.getUniqueId(), getMaxHp());
    }

    public void set(Player player, double hp) {
        this.health.put(player.getUniqueId(), Math.max(0.0, hp));
    }

    public void reset(Player player) {
        this.health.put(player.getUniqueId(), getMaxHp());
        syncHealth(player);
    }

    public void damage(Player player, double amount) {
        UUID id = player.getUniqueId();
        if (isInvulnerable(id)) {
            return;
        }

        double newHp = get(player) - amount;
        if (newHp <= 0.0) {
            set(player, 0.0);
            if (this.deathHandler != null) {
                this.deathHandler.onDeath(player);
            }
        } else {
            set(player, newHp);
            syncHealth(player);
        }
    }

    /** バニラ体力バーを内部HP比率で同期する。死亡時（HP=0）には呼ばない。 */
    public void syncHealth(Player player) {
        double maxHp = getMaxHp();
        double ratio = maxHp > 0.0 ? get(player) / maxHp : 1.0;
        player.setHealth(ratio * player.getMaxHealth());
    }

    public boolean isInvulnerable(UUID id) {
        Long until = this.invulnerableUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    public void setInvulnerable(Player player, int seconds) {
        this.invulnerableUntil.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }
}
