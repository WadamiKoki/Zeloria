package com.zeloria.player;

import com.zeloria.core.Zeloria;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

/**
 * 戦闘外の HP / MP 自然回復を 1 秒ごとに処理するタスク。
 * <p>
 * 戦闘終了（最後にダメージを与えた／受けた時刻）から
 * {@link PlayerStatsService#REGEN_COMBAT_DELAY_MILLIS} が経過するまで回復しない。
 * 回復量は {@code flat * (1.0 + percent)} で計算し、
 * {@code percent} は比率（{@code 0.5} で 50% 増し）として扱う。
 * <p>
 * マイナス再生（毒・デバフ）を許可しているため、HP が 0 以下になる場合は
 * 1.0 で下げ止め、死亡処理と衝突しないようにする。MP は 0 で下げ止まる
 * （{@link PlayerStats#setCurrentMana(double)} のクランプに従う）。
 * <p>
 * プレイヤー状態を変更するため、{@code runTaskTimer} によってメインスレッドで実行する。
 */
public class PlayerRegenTask extends BukkitRunnable {

    /** タスクの実行周期（ティック）。20 ティック = 1 秒。 */
    public static final long PERIOD_TICKS = 20L;

    private final Zeloria plugin;

    public PlayerRegenTask(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void run() {
        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            // 独自死亡処理の待機中（スペクテイター）は回復させない。
            if (plugin.getDeathListener() != null
                    && plugin.getDeathListener().isAwaiting(player)) {
                continue;
            }
            if (!statsService.canRegenerate(player.getUniqueId(), now)) {
                continue;
            }

            PlayerData data = PlayerData.getCached(player.getUniqueId());
            if (data == null) {
                continue;
            }
            if (regenerate(data.getStats())) {
                // 満腹度（MP バー）・レベル／経験値表示を最新値へ追従させる。
                statsService.syncVanillaStats(player, data.getStats());
            }
        }
    }

    /**
     * 1 秒分の自然回復を適用する。
     *
     * @param stats 対象ステータス
     * @return HP または MP が変化した場合は {@code true}
     */
    static boolean regenerate(PlayerStats stats) {
        if (stats == null) {
            return false;
        }

        boolean changed = false;
        double healthGain = regenAmount(
                stats.getHealthRegenFlat(), stats.getHealthRegenPercent());
        if (healthGain != 0.0) {
            double nextHealth = stats.getCurrentHealth() + healthGain;
            if (healthGain < 0.0) {
                // マイナス再生でも HP 0（死亡扱い）にはしない。
                stats.setCurrentHealth(Math.max(1.0, nextHealth));
            } else {
                // 最大 HP を超えないよう setCurrentHealth 側でクランプされる。
                stats.setCurrentHealth(nextHealth);
            }
            changed = true;
        }

        double manaGain = regenAmount(stats.getManaRegenFlat(), stats.getManaRegenPercent());
        if (manaGain != 0.0) {
            // 0〜最大 MP へのクランプは setCurrentMana に委譲する。
            stats.setCurrentMana(stats.getCurrentMana() + manaGain);
            changed = true;
        }
        return changed;
    }

    /**
     * 固定値と割合から 1 秒あたりの回復量を計算する。
     *
     * @param flat    固定回復量（マイナス可）
     * @param percent 割合（比率。マイナス可）
     * @return 1 秒あたりの回復量
     */
    public static double regenAmount(double flat, double percent) {
        return flat * (1.0 + percent);
    }
}
