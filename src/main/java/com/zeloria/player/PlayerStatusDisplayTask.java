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
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * {@link #suspend(Player)} が解除されない場合に自動復帰するまでの tick（10 秒）。
     * <p>
     * テレポートの完了通知が何らかの理由で届かなくても、頭上表示が消えたままに
     * ならないようにするための安全策。
     */
    private static final long SUSPEND_TIMEOUT_TICKS = 200L;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 頭上ステータス表示の Y オフセット（ブロック単位）。 */
    private static final float DISPLAY_Y_OFFSET = 0.65f;

    private final Zeloria plugin;

    /** プレイヤー UUID → 頭上表示エンティティ。 */
    private final Map<UUID, TextDisplay> displays = new HashMap<>();

    /**
     * テレポート中など、passenger を外しておくプレイヤーの UUID。
     * <p>
     * Paper は passenger を乗せたプレイヤーのクロスワールドテレポートを
     * {@code success=false} で拒否するため、転送処理は
     * {@link #suspend(Player)} / {@link #resume(Player)} で一時的に切り離す。
     */
    private final Set<UUID> suspended = ConcurrentHashMap.newKeySet();

    public PlayerStatusDisplayTask(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // テレポート完了待ちの間は passenger を再装着しない。
            if (suspended.contains(player.getUniqueId())) {
                continue;
            }
            update(player);
        }
    }

    /**
     * 指定プレイヤーの頭上表示を最新状態へ更新する（メインスレッド専用）。
     *
     * @param player 対象プレイヤー
     */
    private void update(Player player) {
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (!player.isOnline()
                || data == null
                || player.isDead()
                || isAwaitingRespawn(player)) {
            remove(player.getUniqueId());
            return;
        }

        PlayerStats stats = data.getStats();
        if (stats == null) {
            remove(player.getUniqueId());
            return;
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
                return;
            }
            displays.put(player.getUniqueId(), display);
        }

        display.text(format(stats));
        if (!player.getPassengers().contains(display)) {
            player.addPassenger(display);
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
        display.setLineWidth(400);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);

        // passenger として乗せた状態でも頭と重ならないよう、表示位置を頭上へ持ち上げる。
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, DISPLAY_Y_OFFSET, 0.0f),
                new Quaternionf(),
                new Vector3f(0.5f, 0.5f, 0.5f),
                new Quaternionf()));

        if (!player.addPassenger(display)) {
            display.remove();
            return null;
        }
        return display;
    }

    /**
     * 頭上表示をプレイヤーから切り離し、テレポート完了まで再装着を抑止する（メインスレッド専用）。
     * <p>
     * Paper の {@code Player#teleportAsync} / {@code Player#teleport} は、passenger を
     * 乗せたプレイヤーのクロスワールド転送を {@code false} で失敗させる。
     * そのため、ワールドをまたぐ転送の直前に必ず呼び出す。エンティティ自体は破棄しない。
     *
     * @param player 対象プレイヤー
     */
    public void suspend(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        suspended.add(playerId);

        TextDisplay display = displays.get(playerId);
        if (display != null && display.isValid() && player.getPassengers().contains(display)) {
            // passenger を外すだけで、エンティティはそのまま残す。
            player.removePassenger(display);
        }

        // テレポート完了通知が届かなかった場合でも表示が消えたままにならないようにする。
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (suspended.contains(playerId)) {
                resume(player);
            }
        }, SUSPEND_TIMEOUT_TICKS);
    }

    /**
     * {@link #suspend(Player)} を解除し、次の tick で頭上表示を再装着する。
     *
     * @param player 対象プレイヤー
     */
    public void resume(Player player) {
        if (player == null || !suspended.remove(player.getUniqueId())) {
            return;
        }
        // テレポート完了コールバックは非同期スレッドから呼ばれることがあるため、
        // 位置同期が完了した次の tick にメインスレッドで再装着する。
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !suspended.contains(player.getUniqueId())) {
                update(player);
            }
        });
    }

    /**
     * ステータスから表示用の MiniMessage を組み立てる。
     * <p>
     * 1 行目が HP、2 行目が MP。数値は表示せず、ゲージのみを表示する。
     * ゲージは {@code █} を 20 個並べ、現在値の割合だけ塗り、残りを {@code ░} で表現する。
     */
    private Component format(PlayerStats stats) {
        String hpGauge = gauge(ratio(stats.getCurrentHealth(), stats.getMaxHealth()));
        String mpGauge = gauge(ratio(stats.getCurrentMana(), stats.getMaxMana()));

        String hpLine = "<red>❤ " + hpGauge + "</red>";
        String mpLine = "<aqua>✦ " + mpGauge + "</aqua>";

        return MINI.deserialize(hpLine + "\n" + mpLine);
    }

    /** 現在値 / 最大値を 0.0〜1.0 に丸めた割合で返す。 */
    private static double ratio(double current, double max) {
        if (max <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, current / max));
    }

    /** 割合を 20 個のブロックで表現したゲージ文字列を返す。 */
    private static String gauge(double ratio) {
        int filled = (int) Math.round(ratio * 20.0);
        filled = Math.max(0, Math.min(20, filled));
        return "█".repeat(filled) + "░".repeat(20 - filled);
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
        UUID playerId = event.getPlayer().getUniqueId();
        suspended.remove(playerId);
        remove(playerId);
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
        suspended.clear();
        cancel();
    }
}
