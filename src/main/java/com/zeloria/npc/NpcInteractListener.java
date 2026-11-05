package com.zeloria.npc;

import com.zeloria.core.Zeloria;
import com.zeloria.gui.MenuService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC の右クリック操作を処理するリスナー。
 * <p>
 * 統合版（Geyser）では 1 回のタップでメインハンド・オフハンドのイベントが
 * 連続して届くことがあるため、プレイヤーごとの最終クリック時刻を保持し、
 * {@link #CLICK_COOLDOWN_MILLIS} 以内の連続クリックを無視する。
 * 左クリック（{@code PlayerInteractEntityEvent} は右クリックのみ）や
 * ダメージ操作はこのリスナーの対象外。
 */
public class NpcInteractListener implements Listener {

    /**
     * 連続クリックを無視する時間（ミリ秒）。
     * <p>
     * Geyser / Bedrock のタップは 1 回の操作で複数のクリックイベントを送ることがあるため、
     * 約 2 ティック分の間隔を空けて GUI の多重オープンを防ぐ。
     */
    private static final long CLICK_COOLDOWN_MILLIS = 100L;

    private final Zeloria plugin;

    /** プレイヤー UUID → 最終クリック時刻（epoch millis）。 */
    private final Map<UUID, Long> lastClickAt = new ConcurrentHashMap<>();

    public NpcInteractListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * NPC の右クリックを処理し、設定された GUI を開く。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        EquipmentSlot hand = event.getHand();
        // 右クリック（メインハンド / オフハンド）のみ反応する。
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) {
            return;
        }

        NpcManager npcManager = plugin.getNpcManager();
        Entity clicked = event.getRightClicked();
        if (npcManager == null || !npcManager.isNpc(clicked)) {
            return;
        }

        // 村人取引などのバニラ操作を抑止する。
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (isDuplicateClick(player)) {
            return;
        }

        NpcData data = npcManager.findNpc(clicked).orElse(null);
        if (data == null || !data.hasInteractGui()) {
            return;
        }

        MenuService menuService = plugin.getMenuService();
        if (menuService == null) {
            return;
        }
        menuService.openGui(player, data.interactGui());
    }

    /**
     * プレイヤーの退出時にクリック時刻を破棄する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            lastClickAt.remove(event.getPlayer().getUniqueId());
        }
    }

    /**
     * 直前のクリックから {@link #CLICK_COOLDOWN_MILLIS} 未満かを判定する。
     * <p>
     * 判定と時刻更新は {@link ConcurrentHashMap#compute} でアトミックに行う。
     */
    private boolean isDuplicateClick(Player player) {
        long now = System.currentTimeMillis();
        boolean[] duplicate = {false};

        lastClickAt.compute(player.getUniqueId(), (uuid, last) -> {
            if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
                duplicate[0] = true;
                return last;
            }
            return now;
        });

        return duplicate[0];
    }
}
