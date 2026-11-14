package com.zeloria.npc;

import com.zeloria.core.Zeloria;
import com.zeloria.gui.MenuService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;

/**
 * NPC の右クリック操作を処理するリスナー。
 * <p>
 * {@code shop} / {@code interact} の排他的定義に従い、早期リターンで最小限の分岐のみ行う。
 * 左クリックやダメージ操作はこのリスナーの対象外。
 */
public class NpcInteractListener implements Listener {

    private final Zeloria plugin;

    public NpcInteractListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * NPC の右クリックを処理する。
     * <p>
     * メインハンド以外・NPC 以外は即座に処理を抜ける。
     * {@code shop} が有効なら店舗 GUI、{@code interact} が有効なら汎用アクションを実行し、
     * どちらもなければ何もしない。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        // メインハンドの右クリック以外は無視する（オフハンドの重複イベントも除外）。
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        NpcManager npcManager = plugin.getNpcManager();
        if (npcManager == null) {
            return;
        }

        Entity clicked = event.getRightClicked();
        NpcData data = npcManager.getNpcData(clicked);
        if (data == null) {
            return;
        }

        // 村人取引などのバニラ操作を抑止する。
        event.setCancelled(true);

        if (!data.hasShop() && !data.hasInteract()) {
            return;
        }

        MenuService menuService = plugin.getMenuService();
        if (menuService == null) {
            return;
        }

        Player player = event.getPlayer();
        if (data.hasShop()) {
            menuService.openNpcShop(player, data);
            return;
        }
        handleInteract(player, data, menuService);
    }

    /**
     * {@code interact} セクションの設定に応じて汎用アクションを実行する。
     * <p>
     * {@code gui} は GUI を開き、{@code command} はコンソールから実行する
     * （{@code {player}} はプレイヤー名に置換）。{@code none} は何もしない。
     */
    private void handleInteract(Player player, NpcData data, MenuService menuService) {
        switch (data.interactType()) {
            case NpcData.INTERACT_TYPE_GUI -> {
                if (!menuService.openGui(player, data.interactGuiId())) {
                    plugin.getLogger().warning("interact.gui-id の GUI を開けません: "
                            + data.interactGuiId() + " (npc=" + data.id() + ")");
                }
            }
            case NpcData.INTERACT_TYPE_COMMAND -> {
                for (String command : data.interactCommands()) {
                    String resolved = command.replace(NpcData.PLAYER_PLACEHOLDER, player.getName());
                    if (resolved.startsWith("/")) {
                        resolved = resolved.substring(1);
                    }
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) {
                        plugin.getLogger().warning("interact.commands の実行に失敗しました: "
                                + resolved + " (npc=" + data.id() + ")");
                    }
                }
            }
            default -> {
                // none: 何もしない
            }
        }
    }
}
