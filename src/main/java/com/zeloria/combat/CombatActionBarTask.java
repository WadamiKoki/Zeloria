package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.player.PlayerStats;
import com.zeloria.skill.CooldownService;
import com.zeloria.skill.Skill;
import com.zeloria.skill.SkillSlotService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * 戦闘モード中のプレイヤーへ、1 秒ごとにアクションバーを表示するタスク。
 * <p>
 * 表示内容は HP / MP、戦闘モード状態、ホットバー slot 1〜5（数字キー 2〜6）に
 * 割り当てられたスキルと残りクールダウン。値は {@link PlayerStats} と
 * {@link CooldownService} から読み取るだけで、計算や更新は行わない。
 * <p>
 * 仮想武器を失った・別スロットへ持ち替えた場合は、このタスクが
 * {@link CombatModeService#isActive(Player)} と
 * {@link SkillSlotService#isCombatLoadout(Player)} を確認して自動的に解除する。
 */
public final class CombatActionBarTask extends BukkitRunnable {

    /** 更新間隔（ティック）。1 秒 = 20 ティック。 */
    public static final long PERIOD_TICKS = 20L;

    private final Zeloria plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public CombatActionBarTask(Zeloria plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        CombatModeService modeService = plugin.getCombatModeService();
        SkillSlotService slotService = plugin.getSkillSlotService();
        if (modeService == null || slotService == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!modeService.isActive(player)) {
                continue;
            }
            if (player.isDead()) {
                modeService.deactivate(player);
                continue;
            }
            if (!slotService.isCombatLoadout(player)) {
                modeService.deactivate(player);
                continue;
            }

            // クラス変更や表示の欠落があっても 1 秒以内に追従させる。
            slotService.show(player);
            player.sendActionBar(build(player, slotService.assignedSkills(player)));
        }
    }

    private Component build(Player player, List<Skill> skills) {
        PlayerStats stats = plugin.getPlayerStatsService() == null
                ? null
                : plugin.getPlayerStatsService().getStats(player);

        double health = stats == null ? 0.0 : stats.getCurrentHealth();
        double maxHealth = stats == null ? 0.0 : stats.getMaxHealth();
        double mana = stats == null ? 0.0 : stats.getCurrentMana();
        double maxMana = stats == null ? 0.0 : stats.getMaxMana();

        Component bar = miniMessage.deserialize("<red>HP</red> <white>"
                        + number(health) + "/" + number(maxHealth) + "</white>")
                .append(miniMessage.deserialize(" <dark_gray>|</dark_gray> <aqua>MP</aqua> <white>"
                        + number(mana) + "/" + number(maxMana) + "</white>"))
                .append(miniMessage.deserialize(
                        " <dark_gray>|</dark_gray> <gold>戦闘モード: ON</gold>"));

        for (int index = 0; index < SkillSlotService.SKILL_SLOT_COUNT; index++) {
            bar = bar.append(buildSkillSlot(player, skills, index));
        }
        return bar;
    }

    private Component buildSkillSlot(Player player, List<Skill> skills, int index) {
        int hotbarSlot = SkillSlotService.FIRST_SKILL_SLOT + index + 1;
        Component prefix = miniMessage.deserialize(" <dark_gray>|</dark_gray> <yellow>"
                + hotbarSlot + "</yellow><gray>:</gray> ");
        if (index >= skills.size()) {
            return prefix.append(miniMessage.deserialize("<dark_gray>-</dark_gray>"));
        }

        Skill skill = skills.get(index);
        Component component = prefix.append(skill.displayName());
        CooldownService cooldownService = plugin.getCooldownService();
        if (cooldownService != null) {
            long remaining = cooldownService.remainingMillis(player.getUniqueId(), skill.id());
            if (remaining > 0L) {
                long seconds = Math.max(1L, (remaining + 999L) / 1000L);
                component = component.append(miniMessage.deserialize(
                        " <red>" + seconds + "s</red>"));
            }
        }
        return component;
    }

    private static String number(double value) {
        return Long.toString(Math.max(0L, Math.round(value)));
    }
}
