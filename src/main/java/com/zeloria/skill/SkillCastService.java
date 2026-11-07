package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.combat.CombatService;
import com.zeloria.combat.DamageResult;
import com.zeloria.player.PlayerStats;
import com.zeloria.player.PlayerStatsService;
import com.zeloria.skill.Skill;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Objects;

/**
 * スキル発動の検証・コスト消費・効果適用を一手に担うサービス。
 * <p>
 * 判定順は「クールダウン → マナ → HP → 対象」で、失敗した場合は
 * {@link SkillCastResult} を返すだけで状態を変更しない。成功時のみ
 * コスト消費・クールダウン開始・パーティクル／サウンド・ダメージ適用を行う。
 * <p>
 * ダメージは {@link CombatService} 経由で計算するため、通常攻撃と同一の
 * 命中・会心・2 段階防御・防御貫通・吸血の処理系に乗る。
 */
public final class SkillCastService {

    /** スキルが正面判定に使う最小の内積（約 70 度）。 */
    private static final double MIN_FACING_DOT = 0.35;

    private final CombatService combatService;
    private final PlayerStatsService playerStatsService;
    private final CooldownService cooldownService;

    public SkillCastService(
            Zeloria plugin,
            CombatService combatService,
            PlayerStatsService playerStatsService,
            CooldownService cooldownService) {
        Objects.requireNonNull(plugin, "plugin");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.playerStatsService = Objects.requireNonNull(playerStatsService, "playerStatsService");
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
    }

    /**
     * スキルを発動する。
     *
     * @param player 発動者
     * @param skill  スキル定義
     * @return 発動結果
     */
    public SkillCastResult cast(Player player, Skill skill) {
        PlayerStats stats = playerStatsService.getStats(player);
        if (stats == null) {
            return new SkillCastResult.UnknownSkill(skill == null ? "" : skill.id());
        }

        long cooldownMillis = effectiveCooldownMillis(skill, stats);
        long remaining = cooldownService.remainingMillis(player.getUniqueId(), skill.id());
        if (remaining > 0L) {
            return new SkillCastResult.OnCooldown(skill, remaining);
        }

        double manaCost = requiredMana(skill, stats);
        if (stats.getCurrentMana() < manaCost) {
            return new SkillCastResult.InsufficientMana(
                    skill, manaCost, stats.getCurrentMana());
        }

        double healthCost = requiredHealth(skill, stats);
        if (stats.getCurrentHealth() <= healthCost) {
            return new SkillCastResult.InsufficientHealth(
                    skill, healthCost, stats.getCurrentHealth());
        }

        LivingEntity target = null;
        if (skill.baseDamageMultiplier() > 0.0) {
            target = findTarget(player, skill.range());
            if (target == null) {
                return new SkillCastResult.NoTarget(skill);
            }
        }

        if (manaCost > 0.0) {
            stats.setCurrentMana(stats.getCurrentMana() - manaCost);
        }
        if (healthCost > 0.0) {
            stats.setCurrentHealth(stats.getCurrentHealth() - healthCost);
            combatService.syncPlayerHealth(player);
        }
        if (plugin.getPlayerStatsService() != null) {
            // 満腹度ゲージ（MP バー）とレベル／経験値表示を即時更新する。
            plugin.getPlayerStatsService().syncVanillaStats(player, stats);
        }
        cooldownService.set(player.getUniqueId(), skill.id(), cooldownMillis);

        playEffects(player, skill, target);

        DamageResult damage = target == null
                ? null
                : combatService.playerAttack(
                        player,
                        target,
                        skill.baseDamageMultiplier(),
                        skill.armorPenetration(),
                        skill.lifesteal());

        return new SkillCastResult.Success(
                skill,
                cooldownMillis,
                damage,
                target == null ? "" : target.getName());
    }

    /**
     * クールダウン短縮を適用した実効クールダウンを返す。
     */
    public long effectiveCooldownMillis(Skill skill, PlayerStats stats) {
        double reduction = stats == null ? 0.0 : clamp(stats.getCooldownReduction(), 0.0, 1.0);
        return Math.max(0L, Math.round(skill.cooldownMillis() * (1.0 - reduction)));
    }

    /**
     * 固定・割合を合算した必要マナを返す。
     */
    public static double requiredMana(Skill skill, PlayerStats stats) {
        double percent = skill.manaPercentCost();
        return Math.max(0.0, skill.manaCost()
                + (stats == null ? 0.0 : stats.getMaxMana() * percent));
    }

    /**
     * 固定・割合を合算した必要 HP を返す。
     */
    public static double requiredHealth(Skill skill, PlayerStats stats) {
        double percent = skill.healthPercentCost();
        return Math.max(0.0, skill.healthCost()
                + (stats == null ? 0.0 : stats.getMaxHealth() * percent));
    }

    private void playEffects(Player player, Skill skill, LivingEntity target) {
        Location location = target == null
                ? player.getLocation()
                : target.getLocation();
        Particle particle = parseParticle(skill.particle());
        if (particle != null) {
            player.getWorld().spawnParticle(
                    particle,
                    location.clone().add(0.0, 1.0, 0.0),
                    skill.particleCount(),
                    skill.particleSpread(),
                    skill.particleSpread(),
                    skill.particleSpread(),
                    0.0);
        }
        if (!skill.sound().isBlank()) {
            player.getWorld().playSound(
                    player.getLocation(),
                    skill.sound(),
                    (float) skill.soundVolume(),
                    (float) skill.soundPitch());
        }
    }

    private LivingEntity findTarget(Player player, double range) {
        double effectiveRange = range > 0.0 ? range : 5.0;

        Entity direct = player.getTargetEntity((int) Math.ceil(effectiveRange));
        if (direct instanceof LivingEntity living && isValidTarget(player, living)) {
            return living;
        }

        Vector origin = player.getEyeLocation().toVector();
        Vector direction = player.getEyeLocation().getDirection().normalize();
        double maxDistanceSquared = effectiveRange * effectiveRange;

        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(
                effectiveRange, effectiveRange, effectiveRange)) {
            if (!(entity instanceof LivingEntity candidate)
                    || !isValidTarget(player, candidate)
                    || !player.hasLineOfSight(candidate)) {
                continue;
            }

            Vector toTarget = candidate.getLocation()
                    .add(0.0, candidate.getHeight() * 0.5, 0.0)
                    .toVector()
                    .subtract(origin);
            double distance = toTarget.lengthSquared();
            if (distance <= 0.0 || distance > maxDistanceSquared) {
                continue;
            }
            if (toTarget.normalize().dot(direction) < MIN_FACING_DOT) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isValidTarget(Player player, LivingEntity candidate) {
        return candidate != player
                && !candidate.isDead()
                && combatService.isCustomTarget(candidate);
    }

    private static Particle parseParticle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

}
