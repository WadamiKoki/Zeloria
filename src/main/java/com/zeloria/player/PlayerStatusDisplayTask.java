package com.zeloria.player;

import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤーの頭上へ HP / MP を常時表示するタスク。
 * <p>
 * {@link TextDisplay} をプレイヤーの passenger（乗り物扱い）として装着するため、
 * プレイヤーが移動・テレポートしても表示が追従する。1 秒ごとに
 * {@link PlayerStats} の最新値を読み直し、表示内容を更新する。
 * <p>
 * 死亡中・独自死亡の待機中・データ未読み込みのプレイヤーは表示を消去し、
 * リスポーン後に自動で再生成する。退出時は {@link PlayerQuitEvent} で表示を破棄する。
 * エンティティ操作を伴うため、必ずメインスレッドで実行する。
 */
public class PlayerStatusDisplayTask extends BukkitRunnable implements Listener {

    /** タスクの実行周期（ティック）。20 ティック = 1 秒。 */
    public static final long PERIOD_TICKS = 20L;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Zeloria plugin;

    /** プレイヤー UUID → 頭上表示エンティティ。 */
    private final Map<UUID, TextDisplay> displays = new HashMap<>();

    public PlayerStatusDisplayTask(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = PlayerData.getCached(player.getUniqueId());
            if (!player.isOnline()
                    || data == null
                    || player.isDead()
                    || isAwaitingRespawn(player)) {
                remove(player.getUniqueId());
                continue;
            }

            PlayerStats stats = data.getStats();
            if (stats == null) {
                remove(player.getUniqueId());
                continue;
            }

            TextDisplay display = displays.get(player.getUniqueId());
            if (display == null
                    || !display.isValid()
                    || !display.getWorld().equals(player.getWorld())) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
                display = spawn(player);
                if (display == null) {
                    displays.remove(player.getUniqueId());
                    continue;
                }
                displays.put(player.getUniqueId(), display);
            }

            display.text(format(stats));
            if (!player.getPassengers().contains(display)) {
                player.addPassenger(display);
            }
        }
    }

    /**
     * プレイヤーの passenger として {@link TextDisplay} を生成する。
     *
     * @return 生成した表示。生成できなかった場合は {@code null}
     */
    private TextDisplay spawn(Player player) {
        TextDisplay display;
        try {
            display = player.getWorld().spawn(player.getLocation(), TextDisplay.class);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "頭上ステータス表示の生成に失敗しました: " + player.getName()
                            + " (" + e.getMessage() + ")");
            return null;
        }

        display.text(Component.empty());
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
        display.setLineWidth(200);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);

        if (!player.addPassenger(display)) {
            display.remove();
            return null;
        }
        return display;
    }

    /**
     * ステータスから表示用の MiniMessage を組み立てる。
     */
    private Component format(PlayerStats stats) {
        long health = Math.round(stats.getCurrentHealth());
        long maxHealth = Math.round(stats.getMaxHealth());
        long mana = Math.round(stats.getCurrentMana());
        long maxMana = Math.round(stats.getMaxMana());
        return MINI.deserialize(
                "<red>❤ HP</red> <white>" + health + "/" + maxHealth + "</white>"
                        + "  <aqua>✦ MP</aqua> <white>" + mana + "/" + maxMana + "</white>");
    }

    private boolean isAwaitingRespawn(Player player) {
        return plugin.getDeathListener() != null
                && plugin.getDeathListener().isAwaiting(player);
    }

    /**
     * 指定プレイヤーの頭上表示を破棄する。
     */
    public void remove(UUID playerId) {
        TextDisplay display = displays.remove(playerId);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    /**
     * 退出時に頭上表示を破棄する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    /**
     * プラグイン無効化時に全プレイヤーの表示を破棄してタスクを停止する。
     */
    public void shutdown() {
        for (TextDisplay display : displays.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
        cancel();
    }
}
