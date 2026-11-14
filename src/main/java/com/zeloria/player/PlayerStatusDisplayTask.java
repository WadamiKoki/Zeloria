package com.zeloria.player;

import com.zeloria.combat.CombatService;
import com.zeloria.combat.CombatSettings;
import com.zeloria.core.Zeloria;
import com.zeloria.world.BuildModeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
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
 * 最新値を読み直し、表示内容を更新する。
 * <p>
 * バニラの体力バー・満腹度（MP バー）への同期は定期実行せず、
 * {@code PlayerStats#setCurrentHealth(double)} /
 * {@code PlayerStats#setCurrentMana(double)} が呼ばれたタイミングで
 * {@link #refreshFromVitals(Player)} を経由して即時に行う。
 * <p>
 * 死亡中・独自死亡の待機中・データ未読み込みのプレイヤー、
 * クリエイティブ／スペクテイターのプレイヤー、ビルドモード中のプレイヤーは
 * 表示を消去し、状態が戻ったときに再生成する。退出時は
 * {@link PlayerQuitEvent} で表示を破棄する。
 * エンティティ操作を伴うため、必ずメインスレッドで実行する。
 */
public class PlayerStatusDisplayTask extends BukkitRunnable implements Listener {

    /**
     * タスクの実行周期（ティック）。20 ティック = 1 秒。
     * <p>
     * 頭上表示の再装着・死亡やワールド変更の検出に使用する。
     * バニラの体力バー・満腹度の同期はこの周期では行わない。
     */
    public static final long PERIOD_TICKS = 20L;

    /**
     * {@link #suspend(Player)} が解除されない場合に自動復帰するまでの tick（10 秒）。
     * <p>
     * テレポートの完了通知が何らかの理由で届かなくても、頭上表示が消えたままに
     * ならないようにするための安全策。
     */
    private static final long SUSPEND_TIMEOUT_TICKS = 200L;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** ゲージを描画するバニラ標準フォントの開始タグ。 */
    private static final String FONT_OPEN = "<font:minecraft:default>";

    /** 標準フォントの終了タグ。 */
    private static final String FONT_CLOSE = "</font>";

    /**
     * ゲージ 1 マス分のブロック文字（U+2588 FULL BLOCK）。
     * <p>
     * バニラ標準フォント（{@code minecraft:default}）に含まれる四角いブロックで、
     * 満タン部分・空き部分の両方に使用する。色だけを変えて塗り分ける。
     */
    private static final char GAUGE_BLOCK = '█';

    /** ゲージの既定マス数（設定が不正な場合のフォールバック）。 */
    private static final int DEFAULT_GAUGE_LENGTH = 20;

    /** HP の満タン部分が緑になる割合の下限。 */
    private static final double HP_GREEN_THRESHOLD = 0.75;

    /** HP の満タン部分が黄になる割合の下限。 */
    private static final double HP_YELLOW_THRESHOLD = 0.50;

    /** HP の満タン部分が橙になる割合の下限（これ未満は赤）。 */
    private static final double HP_ORANGE_THRESHOLD = 0.25;

    /**
     * HP の橙のカラーコード。
     * <p>
     * MiniMessage の名前付き色には橙が無いため 16 進カラーを使用する。
     */
    private static final String HP_ORANGE_COLOR = "#FFA500";

    /** MP の満タン部分の色（青）。 */
    private static final String MP_COLOR = "#00BFFF";

    /** ゲージの空き部分の色（灰色）。 */
    private static final String GAUGE_EMPTY_COLOR = "dark_gray";

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
     * <p>
     * HP / MP の変更通知や被弾時など、表示を即座に反映したい場合に呼び出す。
     *
     * @param player 対象プレイヤー
     */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()
                || suspended.contains(player.getUniqueId())) {
            return;
        }
        update(player);
    }

    /**
     * HP / MP の変更をバニラの体力バー・満腹度（MP バー）へ即時同期し、
     * 頭上表示も更新する（メインスレッド専用）。
     * <p>
     * {@link PlayerStats#setCurrentHealth(double)} /
     * {@link PlayerStats#setCurrentMana(double)} からの変更通知で呼び出される。
     * 非同期スレッドから呼ばれた場合はメインスレッドへ委譲する。
     *
     * @param player 対象プレイヤー
     */
    public void refreshFromVitals(Player player) {
        if (player == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshFromVitals(player));
            return;
        }

        if (!player.isOnline()
                || suspended.contains(player.getUniqueId())) {
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null || data.getStats() == null
                || player.isDead() || isAwaitingRespawn(player)) {
            return;
        }

        syncVanillaStatus(player, data);
        update(player);
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
                || data.getStats() == null
                || player.isDead()
                || isAwaitingRespawn(player)
                || !shouldDisplay(player)) {
            remove(player.getUniqueId());
            return;
        }

        PlayerStats stats = data.getStats();

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
     * 定期実行はせず、HP / MP の変更通知（{@link #refreshFromVitals(Player)}）や
     * ログイン・リスポーンなど、値が変化したタイミングでのみ呼び出す。
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
        CombatSettings.StatusDisplay settings = plugin.getCombatSettings().statusDisplay();
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, settings.yOffset(), 0.0f),
                new Quaternionf(),
                new Vector3f(settings.scale(), settings.scale(), settings.scale()),
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
     * バニラ標準フォント（{@code minecraft:default}）と四角いブロック文字
     * （{@code █}）を隙間なく並べて描画する。満タン部分と空き部分は
     * スペースを挟まず、色の切り替わりだけで表現する。どちらの行も常に
     * 同じマス数（満タン＋空き＝20 マス）のため、HP / MP の増減で表示幅は変化しない。
     */
    private Component format(PlayerStats stats) {
        double hpRatio = ratio(stats.getCurrentHealth(), stats.getMaxHealth());
        double mpRatio = ratio(stats.getCurrentMana(), stats.getMaxMana());

        String hpLine = FONT_OPEN
                + gauge(hpRatio, hpColor(hpRatio))
                + FONT_CLOSE;
        String mpLine = FONT_OPEN
                + gauge(mpRatio, MP_COLOR)
                + FONT_CLOSE;

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
     * 割合を {@code status_display.gauge_length} マス（既定 20 マス）のゲージへ変換する。
     * <p>
     * 満タン部分と空き部分の両方を {@code █} で隙間なく描画し、色だけで塗り分ける。
     * 満タン部分と空き部分の境界にもスペースは入れない。マス数は HP / MP の値に
     * 関係なく常に一定のため、増減しても表示幅は変化しない。
     *
     * @param ratio       0.0〜1.0 の割合
     * @param filledColor 満タン部分のカラー（MiniMessage 形式。例: {@code green},
     *                    {@code #00BFFF}）
     * @return MiniMessage 形式のゲージ文字列
     */
    private String gauge(double ratio, String filledColor) {
        int length = plugin.getCombatSettings().statusDisplay().gaugeLength();
        if (length <= 0) {
            length = DEFAULT_GAUGE_LENGTH;
        }
        int filled = (int) Math.round(ratio * length);
        filled = Math.max(0, Math.min(length, filled));

        return blockRun(filledColor, filled) + blockRun(GAUGE_EMPTY_COLOR, length - filled);
    }

    /**
     * 指定した色で {@code █} を指定マス数分、隙間なく並べた MiniMessage の断片を返す。
     *
     * @param color MiniMessage 形式のカラー（例: {@code green}, {@code #00BFFF}）
     * @param count マス数（0 以下の場合は空文字）
     * @return 色付けされたゲージ断片
     */
    private static String blockRun(String color, int count) {
        if (count <= 0) {
            return "";
        }
        return "<color:" + color + ">" + String.valueOf(GAUGE_BLOCK).repeat(count) + "</color>";
    }

    /**
     * HP 残量に応じた満タン部分の色を返す（緑 → 黄 → 橙 → 赤）。
     *
     * @param ratio 0.0〜1.0 の HP 割合
     * @return MiniMessage 形式のカラー
     */
    private static String hpColor(double ratio) {
        if (ratio >= HP_GREEN_THRESHOLD) {
            return "green";
        }
        if (ratio >= HP_YELLOW_THRESHOLD) {
            return "yellow";
        }
        if (ratio >= HP_ORANGE_THRESHOLD) {
            return HP_ORANGE_COLOR;
        }
        return "red";
    }

    private boolean isAwaitingRespawn(Player player) {
        return plugin.getDeathListener() != null
                && plugin.getDeathListener().isAwaiting(player);
    }

    /**
     * 頭上表示を表示すべき状態か判定する。
     * <p>
     * クリエイティブ・スペクテイター、およびビルドモード中は表示しない。
     *
     * @param player 対象プレイヤー
     * @return 表示する場合は {@code true}
     */
    private boolean shouldDisplay(Player player) {
        GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) {
            return false;
        }
        BuildModeService buildModeService = plugin.getBuildModeService();
        return buildModeService == null || !buildModeService.isActive(player);
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
     * ゲームモード変更時に頭上表示の表示／非表示を切り替える。
     * <p>
     * クリエイティブ・スペクテイターへ変更された場合は即時に破棄し、
     * サバイバル等へ戻された場合は次の tick で再生成する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newMode = event.getNewGameMode();
        if (newMode == GameMode.CREATIVE || newMode == GameMode.SPECTATOR) {
            remove(player.getUniqueId());
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refreshFromVitals(player);
            }
        });
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
