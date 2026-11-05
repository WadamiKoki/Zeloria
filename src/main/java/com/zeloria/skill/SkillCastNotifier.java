package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.combat.DamageResult;
import com.zeloria.core.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;

/**
 * {@link SkillCastResult} をプレイヤーへ通知する。
 * <p>
 * ホットバー経由（{@code SkillSlotListener}）とスキルアイテム経由
 * （{@code CombatListener}）の双方から利用できるよう、通知処理を一箇所に集約する。
 * {@code sealed interface} の網羅的な {@code switch} により、結果の追加時に
 * 通知漏れをコンパイルエラーとして検出できる。
 */
public final class SkillCastNotifier {

    /** メッセージ定義ファイル（{@code MessageData/skill.yml}）。 */
    private static final String MESSAGE_FILE = "skill";

    private final Zeloria plugin;

    public SkillCastNotifier(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 発動結果をアクションバーへ通知する。
     *
     * @param player 対象プレイヤー
     * @param result 発動結果
     */
    public void notify(Player player, SkillCastResult result) {
        if (player == null || result == null) {
            return;
        }

        switch (result) {
            case SkillCastResult.Success success -> notifySuccess(player, success);
            case SkillCastResult.OnCooldown cooldown -> actionBar(
                    player, "skill.cooldown",
                    Map.of("skill_name", plain(cooldown.skill().displayName()),
                            "time", Long.toString(remainingSeconds(cooldown.remainingMillis()))));
            case SkillCastResult.InsufficientMana mana -> actionBar(
                    player, "skill.not_enough_mp",
                    Map.of("skill_name", plain(mana.skill().displayName()),
                            "cost", formatNumber(mana.required()),
                            "current", formatNumber(mana.current())));
            case SkillCastResult.InsufficientHealth health -> actionBar(
                    player, "skill.not_enough_hp",
                    Map.of("skill_name", plain(health.skill().displayName()),
                            "cost", formatNumber(health.required()),
                            "current", formatNumber(health.current())));
            case SkillCastResult.NoTarget noTarget -> actionBar(
                    player, "skill.no_target",
                    Map.of("skill_name", plain(noTarget.skill().displayName())));
            case SkillCastResult.NotOwned notOwned -> actionBar(
                    player, "skill.not_owned",
                    Map.of("skill_name", plain(notOwned.skill().displayName())));
            case SkillCastResult.UnknownSkill unknown -> actionBar(
                    player, "skill.not_found",
                    Map.of("skill_name", unknown.skillId()));
        }
    }

    private void notifySuccess(Player player, SkillCastResult.Success success) {
        DamageResult damage = success.damage();
        if (damage == null) {
            actionBar(player, "skill.cast",
                    Map.of("skill_name", plain(success.skill().displayName())));
            return;
        }
        if (damage.evaded()) {
            actionBar(player, "skill.evaded",
                    Map.of("skill_name", plain(success.skill().displayName()),
                            "target", success.targetName()));
            return;
        }

        Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("skill_name", plain(success.skill().displayName()));
        placeholders.put("target", success.targetName());
        placeholders.put("damage", formatNumber(damage.damage()));
        placeholders.put("critical", Boolean.toString(damage.critical()));
        actionBar(player, damage.critical() ? "skill.critical" : "skill.damage", placeholders);
    }

    private void actionBar(Player player, String key, Map<String, String> placeholders) {
        MessageService messages = plugin.getMessageService();
        if (messages != null) {
            messages.actionBar(player, MESSAGE_FILE, key, placeholders);
        }
    }

    private static long remainingSeconds(long remainingMillis) {
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
