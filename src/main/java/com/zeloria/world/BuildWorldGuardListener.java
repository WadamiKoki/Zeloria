package com.zeloria.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
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
                    "<green>[Zeloria]</green> <red>建築ワールドは管理者専用のため入場できません。</red>")
            .decoration(TextDecoration.ITALIC, false);

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
        Location destination = event.getTo();
        if (destination == null || destination.getWorld() == null) {
            return;
        }

        World destinationWorld = destination.getWorld();
        // 建築ワールドになり得るキーだけを対象にし、通常ワールドへの転送では
        // 管理マップの走査を行わない。
        if (!canBeBuildWorld(destinationWorld)
                || !buildManager.isActiveBuildWorld(destinationWorld)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission(BuildManager.BUILD_PERMISSION) && !player.isOp()) {
            event.setCancelled(true);
            player.sendMessage(DENIED);
        }
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
        World fromWorld = from == null ? null : from.getWorld();
        if (!canBeBuildWorld(fromWorld) || !buildManager.isActiveBuildWorld(fromWorld)) {
            return;
        }

        World toWorld = to == null ? null : to.getWorld();
        if (toWorld == null || !buildManager.isActiveBuildWorld(toWorld)) {
            buildManager.restoreGameMode(event.getPlayer());
        }
    }

    /**
     * 建築ワールドのキー形式（{@code minecraft:<template>_build}）かを軽量に判定する。
     * <p>
     * ファイルシステムやレイヤー ID の解析は行わず、通常ワールドへのテレポートを
     * 早期に除外するためだけに使用する。
     */
    private static boolean canBeBuildWorld(World world) {
        return world != null
                && BuildManager.BUILD_NAMESPACE.equals(world.getKey().getNamespace())
                && world.getKey().getKey().endsWith(BuildManager.BUILD_WORLD_SUFFIX);
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
