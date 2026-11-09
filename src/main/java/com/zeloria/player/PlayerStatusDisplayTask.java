package com.zeloria.player;

import com.zeloria.combat.CombatActionBarTask;
import com.zeloria.combat.CombatService;
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
 * プレイヤーが移動・テレポートしても表示が追従する。{@link PlayerStats} の
 * 最新値を読み直し、表示内容を更新する。更新周期は非戦闘時が 3 秒、
 * 戦闘モード中は 1 秒で、戦闘判定は
 * {@link CombatActionBarTask#isInCombatMode(Zeloria, Player)} と共通である。
 * 同じ周期でバニラの体力バー・満腹度（MP バー）・レベル・経験値バーも強制的に同期する。
 * <p>
 * 死亡中・独自死亡の待機中・データ未読み込みのプレイヤーは表示を消去し、
 * リスポーン後に自動で再生成する。退出時は {@link PlayerQuitEvent} で表示を破棄する。
 * エンティティ操作を伴うため、必ずメインスレッドで実行する。
 */
public class PlayerStatusDisplayTask extends BukkitRunnable implements Listener {

    /**
     * タスクの実行周期（ティック）。20 ティック = 1 秒。
     * <p>
     * 非戦闘時はこの周期で経過時間を確認し、3 秒ごとに実際の更新を行う。
     * 戦闘モード中は毎周期（1 秒）更新する。
     */
    public static final long PERIOD_TICKS = 20L;

    /** 非戦闘時の表示・バニラ同期の間隔（ミリ秒）。 */
    private static final long OUT_OF_COMBAT_INTERVAL_MILLIS = 3_000L;

    /** 戦闘モード中の表示・バニラ同期の間隔（ミリ秒）。 */
    private static final long COMBAT_INTERVAL_MILLIS = 1_000L;

    /**
     * {@link #suspend(Player)} が解除されない場合に自動復帰するまでの tick（10 秒）。
     * <p>
     * テレポートの完了通知が何らかの理由で届かなくても、頭上表示が消えたままに
     * ならないようにするための安全策。
     */
    private static final long SUSPEND_TIMEOUT_TICKS = 200L;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 頭上表示の縦位置（旧 0.15 から 0.2 ブロック上げる）。 */
    private static final float DISPLAY_Y_OFFSET = 0.35f;

    /** 頭上表示の拡大率（旧 0.5 の 1.5 倍）。 */
    private static final float DISPLAY_SCALE = 0.75f;

    /** ゲージを描画する等幅フォントの開始タグ。 */
    private static final String FONT_OPEN = "<font:minecraft:uniform>";

    /** 等幅フォントの終了タグ。 */
    private static final String FONT_CLOSE = "</font>";

    /** ゲージのマス数（HP・MP で共通）。 */
    private static final int GAUGE_LENGTH = 20;

    /** ゲージの塗りつぶし文字（等幅フォントで描画する ASCII 文字）。 */
    private static final char GAUGE_FILLED = '#';

    /** ゲージの残り文字（等幅フォントで描画する ASCII 文字）。 */
    private static final char GAUGE_EMPTY = '-';

    private final Zeloria plugin;

    /** プレイヤー UUID → 頭上表示エンティティ。 */
    private final Map<UUID, TextDisplay> displays = new HashMap<>();

    /** プレイヤー UUID → 最後に表示・バニラ同期を完了した時刻（ミリ秒）。 */
    private final Map<UUID, Long> lastSyncAt = new HashMap<>();

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
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // テレポート完了待ちの間は passenger を再装着しない。
            if (suspended.contains(player.getUniqueId())) {
                continue;
            }

            // 戦闘モード中は毎秒、非戦闘時は 3 秒ごとに更新する。
            long interval = CombatActionBarTask.isInCombatMode(plugin, player)
                    ? COMBAT_INTERVAL_MILLIS
                    : OUT_OF_COMBAT_INTERVAL_MILLIS;
            Long last = lastSyncAt.get(player.getUniqueId());
            if (last != null && now - last < interval) {
                continue;
            }

            update(player);
            lastSyncAt.put(player.getUniqueId(), now);
        }
    }

    /**
     * 指定プレイヤーの頭上表示とバニラ status 表示を即時同期する（メインスレッド専用）。
     * <p>
     * 被弾時など、次回の定期同期を待たずに HP 表示を反映したい場合に呼び出す。
     *
     * @param player 対象プレイヤー
     */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()
                || suspended.contains(player.getUniqueId())) {
            return;
        }
        update(player);
        lastSyncAt.put(player.getUniqueId(), System.currentTimeMillis());
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

        // 空腹・自然減少・バニラのレベル操作などでバニラ側の値がずれるため、
        // 戦闘中は毎秒、非戦闘時は 3 秒ごとに強制的に同期する。
        syncVanillaStatus(player, data);

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
     * RPG ステータスをバニラの体力バー・満腹度（MP バー）・レベル・経験値バーへ反映する。
     * <p>
     * バニラの空腹による体力減少や自然回復、レベル操作などでバニラ側の値がずれるため、
     * ステータス変更の有無に関わらず常に上書きする。呼び出し周期は
     * {@link #run()} が決める（戦闘中は 1 秒、非戦闘時は 3 秒）。
     *
     * @param player 対象プレイヤー
     * @param data   反映するプレイヤーデータ
     */
    private void syncVanillaStatus(Player player, PlayerData data) {
        CombatService combatService = plugin.getCombatService();
        if (combatService != null) {
            // 体力バーは「現在 HP / 最大 HP」の割合をバニラ最大 20 へ写す（下限 1.0 で死亡回避）。
            combatService.syncPlayerHealth(player);
        }

        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            // 満腹度 = MP 割合、レベル・経験値バー = RPG のレベルと次レベルまでの進捗。
            statsService.syncVanillaStats(player, data);
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
                new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
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
     * 等幅フォント（{@code minecraft:uniform}）を使用し、どちらの行も
     * 同じ文字数の ASCII ゲージで構成することで、HP バーと MP バーの
     * 表示幅を完全に揃える。
     */
    private Component format(PlayerStats stats) {
        String hpGauge = gauge(ratio(stats.getCurrentHealth(), stats.getMaxHealth()));
        String mpGauge = gauge(ratio(stats.getCurrentMana(), stats.getMaxMana()));

        String hpLine = FONT_OPEN + "<red>HP " + hpGauge + "</red>" + FONT_CLOSE;
        String mpLine = FONT_OPEN + "<aqua>MP " + mpGauge + "</aqua>" + FONT_CLOSE;

        return MINI.deserialize(hpLine + "\n" + mpLine);
    }

    /** 現在値 / 最大値を 0.0〜1.0 に丸めた割合で返す。 */
    private static double ratio(double current, double max) {
        if (max <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, current / max));
    }

    /**
     * 割合を 20 個のブロックで表現したゲージ文字列を返す。
     * <p>
     * 等幅フォントで同じ幅になるよう、ASCII の {@code #} と {@code -} のみを使用する。
     */
    private static String gauge(double ratio) {
        int filled = (int) Math.round(ratio * GAUGE_LENGTH);
        filled = Math.max(0, Math.min(GAUGE_LENGTH, filled));
        return String.valueOf(GAUGE_FILLED).repeat(filled)
                + String.valueOf(GAUGE_EMPTY).repeat(GAUGE_LENGTH - filled);
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
        lastSyncAt.remove(playerId);
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
        lastSyncAt.clear();
        suspended.clear();
        cancel();
    }
}
