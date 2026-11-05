package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.skill.SkillCastNotifier;
import com.zeloria.skill.SkillCastService;
import com.zeloria.combat.CombatModeService;
import com.zeloria.skill.SkillSlotService;
import com.zeloria.skill.Skill;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;
import java.util.Optional;

/**
 * 戦闘モード中のスキルスロット右クリックを処理するリスナー。
 * <p>
 * ホットバー slot 1〜5（数字キー 2〜6）を選択して右クリックすると、
 * そのスロットに割り当てられたスキルを発動する。発動判定・コスト消費・
 * ダメージ計算・通知は {@link SkillCastService} と {@link SkillCastNotifier} に委譲する。
 * <p>
 * 同じアイテムの連続右クリックで発動判定が重複しないよう、バニラの
 * アイテムクールダウン（2 ティック）を併用する。スニーク右クリックは
 * 戦闘モード切り替えと衝突するため対象外とする。
 */
public final class SkillSlotListener implements Listener {

    /** 連打防止用のバニラアイテムクールダウン（ティック）。 */
    private static final int ANTI_SPAM_TICKS = 2;

    private final Zeloria plugin;

    public SkillSlotListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * スキルスロット選択中の右クリックでスキルを発動する。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return;
        }

        CombatModeService modeService = plugin.getCombatModeService();
        SkillSlotService slotService = plugin.getSkillSlotService();
        if (modeService == null || slotService == null || !modeService.isActive(player)) {
            return;
        }

        int heldSlot = player.getInventory().getHeldItemSlot();
        if (heldSlot < SkillSlotService.FIRST_SKILL_SLOT
                || heldSlot > SkillSlotService.LAST_SKILL_SLOT) {
            return;
        }

        Optional<Skill> selected = slotService.skillAt(player, heldSlot);
        if (selected.isEmpty()) {
            return;
        }

        // スキル操作と判定できたものだけキャンセルし、ブロック操作などを止める。
        event.setCancelled(true);

        SkillCastService castService = plugin.getSkillCastService();
        SkillCastNotifier notifier = plugin.getSkillCastNotifier();
        if (castService == null || notifier == null) {
            return;
        }

        Skill skill = selected.get();
        Material icon = skill.icon();
        if (player.getCooldown(icon) > 0) {
            return;
        }
        player.setCooldown(icon, ANTI_SPAM_TICKS);

        notifier.notify(player, castService.cast(player, skill));
    }
}
