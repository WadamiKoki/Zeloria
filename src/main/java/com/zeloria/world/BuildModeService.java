package com.zeloria.world;

import com.zeloria.combat.CombatModeService;
import com.zeloria.core.Zeloria;
import com.zeloria.item.VirtualInventoryService;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーごとの手動ビルドモード ON / OFF をメモリ上で管理するサービス。
 * <p>
 * ビルドモードは {@code /admin build mode} でのみ切り替える。建築ワールドへの入退場では
 * 自動的に切り替えない。状態はサーバーのメモリ上にのみ保持するため、サーバーが停止・
 * クラッシュした場合は次回起動時に引き継がれない。
 * <p>
 * ビルドモード ON 時は戦闘モードを解除して仮想ホットバーを除去し、OFF 時は
 * ホットバーの物理アイテムを削除して仮想ホットバーを再適用する。
 */
public final class BuildModeService {

    private final Zeloria plugin;

    /**
     * ビルドモード中のプレイヤー。値は常に {@link Boolean#TRUE}。
     * プレイヤー単位で ON / OFF を持つため Map で管理する。
     */
    private final Map<UUID, Boolean> activeModes = new ConcurrentHashMap<>();

    public BuildModeService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 対象プレイヤーがビルドモード中か判定する。
     *
     * @param player 対象プレイヤー
     * @return ビルドモード中の場合は {@code true}
     */
    public boolean isActive(Player player) {
        return player != null && isActive(player.getUniqueId());
    }

    /**
     * 対象 UUID がビルドモード中か判定する。
     *
     * @param playerId 対象プレイヤー UUID
     * @return ビルドモード中の場合は {@code true}
     */
    public boolean isActive(UUID playerId) {
        return playerId != null && Boolean.TRUE.equals(activeModes.get(playerId));
    }

    /**
     * ビルドモードを切り替える。
     *
     * @param player 対象プレイヤー
     * @return 切り替え後にビルドモード中なら {@code true}
     */
    public boolean toggle(Player player) {
        if (isActive(player)) {
            deactivate(player);
            return false;
        }
        activate(player);
        return isActive(player);
    }

    /**
     * ビルドモードを ON にする。
     * <p>
     * 装備チェックはコマンド側で行う。ここでは戦闘モード解除と仮想ホットバー除去だけを行い、
     * 最後にビルドモード中フラグを立てる。
     *
     * @param player 対象プレイヤー
     */
    public void activate(Player player) {
        if (player == null || !player.isOnline() || isActive(player)) {
            return;
        }

        CombatModeService combatModeService = plugin.getCombatModeService();
        if (combatModeService != null) {
            combatModeService.deactivate(player);
        }

        VirtualInventoryService virtualInventoryService = plugin.getVirtualInventoryService();
        if (virtualInventoryService != null) {
            virtualInventoryService.enterBuildMode(player);
        }

        activeModes.put(player.getUniqueId(), Boolean.TRUE);
    }

    /**
     * ビルドモードを OFF にする。
     * <p>
     * 仮想ホットバーの再適用が完了してからビルドモード中フラグを解除する。
     * 既に OFF の場合は何もしない。
     *
     * @param player 対象プレイヤー
     */
    public void deactivate(Player player) {
        if (player == null || !isActive(player)) {
            return;
        }

        VirtualInventoryService virtualInventoryService = plugin.getVirtualInventoryService();
        if (virtualInventoryService != null) {
            virtualInventoryService.leaveBuildMode(player);
        }

        activeModes.remove(player.getUniqueId());
    }

    /**
     * プレイヤーのビルドモード状態を破棄する。
     * <p>
     * 退出時の後始末に使用する。メモリ保持のみのため、ログイン時に前回の状態は復元されない。
     *
     * @param playerId 対象プレイヤー UUID
     */
    public void clear(UUID playerId) {
        if (playerId != null) {
            activeModes.remove(playerId);
        }
    }
}
