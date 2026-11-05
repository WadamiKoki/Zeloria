package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.skill.SkillSlotService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * 戦闘モードの切り替えと、状態を維持できなくなった際の自動解除を担うリスナー。
 * <ul>
 *     <li>仮想武器を手に持ち、スニーク + 下向き（pitch &gt; 45）+ 右クリックで ON/OFF</li>
 *     <li>仮想武器以外へ持ち替えたら OFF</li>
 *     <li>退出時はスキルスロットを元へ戻し、状態を破棄</li>
 *     <li>死亡時はスキル表示を解除し、退避していた実アイテムをドロップまたは保持</li>
 * </ul>
 * スキル発動そのものは {@link SkillSlotListener} が担当する。
 */
public final class CombatModeListener implements Listener {

    /** 戦闘モード切り替えに必要な下向き角度（度）。 */
    private static final float TOGGLE_PITCH = 45.0F;

    private final Zeloria plugin;

    public CombatModeListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * スニーク + 下向き + 右クリックで戦闘モードを切り替える。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isSneaking() || player.getLocation().getPitch() <= TOGGLE_PITCH) {
            return;
        }

        CombatModeService modeService = plugin.getCombatModeService();
        if (modeService == null) {
            return;
        }
        if (!modeService.isActive(player) && !modeService.canToggle(player)) {
            return;
        }

        // ブロック設置・チェスト開閉など、右クリックの副作用を止める。
        event.setCancelled(true);
        modeService.toggle(player);
    }

    /**
     * 仮想武器から別のアイテムへ持ち替えた場合に戦闘モードを解除する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        CombatModeService modeService = plugin.getCombatModeService();
        if (modeService == null || !modeService.isActive(event.getPlayer())) {
            return;
        }

        Player player = event.getPlayer();
        int newSlot = event.getNewSlot();
        if (newSlot >= SkillSlotService.FIRST_SKILL_SLOT
                && newSlot <= SkillSlotService.LAST_SKILL_SLOT) {
            if (!modeService.hasVirtualWeaponInSlotZero(player)) {
                modeService.deactivate(player);
            }
            return;
        }

        if (newSlot == VirtualInventoryService.WEAPON_HOTBAR_SLOT
                && CombatModeService.isVirtualWeapon(player.getInventory().getItem(newSlot))) {
            return;
        }
        modeService.deactivate(player);
    }

    /**
     * 退出時にスキルスロットを元へ戻し、状態を破棄する。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        CombatModeService modeService = plugin.getCombatModeService();
        if (modeService != null) {
            modeService.handleQuit(event.getPlayer());
        }
    }

    /**
     * 死亡時に戦闘モードを解除し、退避していた実アイテムを失わないようにする。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        CombatModeService modeService = plugin.getCombatModeService();
        SkillSlotService slotService = plugin.getSkillSlotService();
        if (modeService == null || slotService == null) {
            return;
        }

        Player player = event.getEntity();
        modeService.clearState(player.getUniqueId());
        player.sendActionBar(Component.empty());

        List<ItemStack> saved = slotService.detach(player);
        if (saved.isEmpty()) {
            return;
        }
        if (event.getKeepInventory()) {
            slotService.restoreItems(player, saved);
        } else {
            event.getDrops().addAll(saved);
        }
    }

    /**
     * 再ログイン時に前回セッションの状態が残らないようにする。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        CombatModeService modeService = plugin.getCombatModeService();
        if (modeService != null) {
            modeService.clearState(event.getPlayer().getUniqueId());
        }
    }
}
