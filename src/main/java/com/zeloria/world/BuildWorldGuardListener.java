package com.zeloria.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 建築ワールドへの一般プレイヤーの入場を防ぐリスナー。
 * <p>
 * 建築ワールドは管理者専用のため、建築権限（{@link BuildManager#BUILD_PERMISSION}）
 * を持たないプレイヤーのテレポートをキャンセルする。
 * {@link org.bukkit.event.player.PlayerPortalEvent} は
 * {@link PlayerTeleportEvent} を継承しているため、ポータル経由の入場もここで防止できる。
 */
public class BuildWorldGuardListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Component DENIED = MINI.deserialize(
            "<green>[Zeloria]</green> <red>建築ワールドは管理者専用のため入場できません。</red>");

    private final BuildManager buildManager;

    /**
     * リスナーを生成する。
     *
     * @param buildManager 建築モード管理
     */
    public BuildWorldGuardListener(BuildManager buildManager) {
        this.buildManager = buildManager;
    }

    /**
     * 建築ワールドへのテレポートを検証する。
     * <p>
     * 建築権限（{@link BuildManager#BUILD_PERMISSION}）を持たないプレイヤーでも、
     * OP 権限（{@link Player#isOp()}）を持つ場合は許可する。サーバー管理者が
     * 権限ノード未付与のまま転送できなくなる事態を避けるため。
     *
     * @param event テレポートイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo();

        // テレポートがキャンセルされている原因を切り分けるための調査用ログ。
        Bukkit.getLogger().info("[BuildGuardDebug] player=" + player.getName()
                + ", to=" + (destination != null && destination.getWorld() != null
                        ? destination.getWorld().getName() : "null")
                + ", isActiveBuildWorld=" + buildManager.isActiveBuildWorld(
                        destination != null ? destination.getWorld() : null)
                + ", cancelled=" + event.isCancelled());

        if (destination == null || destination.getWorld() == null) {
            return;
        }

        // キャンセル対象は「現在開いている建築ワールドへの入場」のみ。
        // minecraft 名前空間を共有するレイヤーインスタンスやバニラワールド、
        // 建築ワールドからの退出はキャンセルしない。
        if (!buildManager.isActiveBuildWorld(destination.getWorld())) {
            return;
        }

        if (!player.hasPermission(BuildManager.BUILD_PERMISSION) && !player.isOp()) {
            event.setCancelled(true);
            player.sendMessage(DENIED);
        }

        logDebug(player, destination, event.isCancelled());
    }

    /**
     * 建築ワールド向けテレポートの判定結果をログに出力する。
     * <p>
     * 転送失敗の原因（イベントキャンセルか、それ以外か）を切り分けるための調査用ログ。
     *
     * @param player      対象プレイヤー
     * @param destination 転送先
     * @param cancelled   判定後のキャンセル状態
     */
    private void logDebug(Player player, Location destination, boolean cancelled) {
        String worldName = destination != null && destination.getWorld() != null
                ? destination.getWorld().getName()
                : "null";
        Bukkit.getLogger().info("[BuildGuardDebug] teleport event: player=" + player.getName()
                + ", to=" + worldName
                + ", cancelled=" + cancelled);
    }

    /**
     * 建築ワールドから外へ出たプレイヤーのゲームモードを元に戻す。
     * <p>
     * 保存時の退避や {@code /tp} など、テレポートによる退出を検知する。
     *
     * @param event テレポートイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportOut(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || from.getWorld() == null
                || !BuildManager.isBuildWorld(from.getWorld())) {
            return;
        }
        if (to == null || !BuildManager.isBuildWorld(to.getWorld())) {
            buildManager.restoreGameMode(event.getPlayer());
        }
    }

    /**
     * 建築ワールド内で切断したプレイヤーのゲームモードを元に戻す。
     * <p>
     * 退出処理が走らないまま切断されても、次回ログイン時に
     * クリエイティブモードが保存されないようにする。
     *
     * @param event 退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        buildManager.restoreGameMode(event.getPlayer());
    }
}
