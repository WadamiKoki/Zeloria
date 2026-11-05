package com.zeloria.skill;

import com.zeloria.combat.CombatModeService;
import com.zeloria.core.Zeloria;
import com.zeloria.item.VirtualItem;
import com.zeloria.skill.CooldownService;
import com.zeloria.skill.Skill;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;

/**
 * 戦闘モード中のホットバーへ、スキルの残りクールダウンをスタック数として表示するタスク。
 * <p>
 * スキルアイコンの {@link ItemStack#getAmount()} を残り秒数へ書き換える。残りが
 * 無い場合は {@code 1} に戻す。Minecraft は個数 1 のとき数字を描画しないため、
 * クールダウン中のみ数字が見える。最大 64 個までしか表示できないため、
 * それ以上は {@code 64} で頭打ちにする。
 * <p>
 * 1 秒ごとに実行し、{@link CombatActionBarTask} の後に起動することで
 * {@link SkillSlotService#show(Player)} がアイコンを再生成した直後でも
 * 表示を上書きできる。
 */
public final class SkillCooldownHotbarTask extends BukkitRunnable {

    /** 更新間隔（ティック）。1 秒 = 20 ティック。 */
    public static final long PERIOD_TICKS = 20L;

    /** スタック数として表示できる最大値。 */
    private static final int MAX_STACK_COUNT = 64;

    private final Zeloria plugin;

    public SkillCooldownHotbarTask(Zeloria plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        CombatModeService modeService = plugin.getCombatModeService();
        SkillSlotService slotService = plugin.getSkillSlotService();
        CooldownService cooldownService = plugin.getCooldownService();
        if (modeService == null || slotService == null || cooldownService == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!modeService.isActive(player) || player.isDead()) {
                continue;
            }
            for (int slot = SkillSlotService.FIRST_SKILL_SLOT;
                    slot <= SkillSlotService.LAST_SKILL_SLOT;
                    slot++) {
                updateSlot(player, slotService, cooldownService, slot);
            }
        }
    }

    private void updateSlot(
            Player player,
            SkillSlotService slotService,
            CooldownService cooldownService,
            int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || !VirtualItem.isSkillSlot(item)) {
            return;
        }

        Optional<Skill> skill = slotService.skillAt(player, slot);
        if (skill.isEmpty()) {
            return;
        }

        long remaining = cooldownService.remainingMillis(
                player.getUniqueId(), skill.get().id());
        int amount = remaining <= 0L
                ? 1
                : (int) Math.min(MAX_STACK_COUNT, Math.max(1L, (remaining + 999L) / 1000L));

        if (item.getAmount() != amount) {
            item.setAmount(amount);
            // ItemStack はインベントリの実体参照のため setAmount のみで反映されるが、
            // 実装差異に備えて明示的に再設定する。
            player.getInventory().setItem(slot, item);
        }
    }
}
