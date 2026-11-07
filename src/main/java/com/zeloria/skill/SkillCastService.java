package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.combat.CombatService;
import com.zeloria.combat.DamageResult;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStats;
import com.zeloria.player.PlayerStatsService;
import com.zeloria.skill.Skill;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
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

    private final Zeloria plugin;
    private final CombatService combatService;
    private final PlayerStatsService playerStatsService;
    private final CooldownService cooldownService;

    public SkillCastService(
            Zeloria plugin,
            CombatService combatService,
            PlayerStatsService playerStatsService,
            CooldownService cooldownService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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
        if (skill == null) {
            return new SkillCastResult.UnknownSkill("");
        }
        PlayerStats stats = playerStatsService.getStats(player);
        if (stats == null) {
            return new SkillCastResult.UnknownSkill(skill.id());
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        String classId = data == null
                ? null
                : data.getClassData().currentClassIdOrNull();
        ClassTreeService classTreeService = plugin.getClassTreeService();

        double skillDamageBonus = 0.0;
        if (classTreeService != null && data != null
                && classId != null && !classId.isBlank()) {
            Map<String, Double> skillEffects = classTreeService
                    .getAggregatedSkillEffects(data, classId);
            skillDamageBonus = skillEffects.getOrDefault(
                    "skillDamage_" + skill.id(), 0.0);
        }

        SkillVariantData variant = resolveVariant(
                data, classId, skill, classTreeService);
        boolean variantNeedsTarget = variant != null && variant.actions().stream()
                .anyMatch(action -> "DAMAGE".equals(action.type()));

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
        if (variant != null) {
            if (variantNeedsTarget) {
                target = findTarget(player, skill.range());
                if (target == null) {
                    return new SkillCastResult.NoTarget(skill);
                }
            }
        } else if (skill.baseDamageMultiplier() > 0.0) {
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

        DamageResult damage;
        if (variant != null) {
            // 派生スキルでは元スキルのパーティクル／サウンドではなく
            // SkillVariant の actions だけを順番に実行する。
            damage = executeVariant(player, skill, variant, target, skillDamageBonus);
        } else {
            playEffects(player, skill, target);
            damage = target == null
                    ? null
                    : combatService.playerAttack(
                            player,
                            target,
                            skill.baseDamageMultiplier() * (1.0 + skillDamageBonus),
                            skill.armorPenetration(),
                            skill.lifesteal());
        }

        return new SkillCastResult.Success(
                skill,
                cooldownMillis,
                damage,
                target == null ? "" : target.getName());
    }


    /**
     * 解放済み赤ノードから派生スキル定義を解決する。
     */
    private SkillVariantData resolveVariant(PlayerData data,
                                            String classId,
                                            Skill skill,
                                            ClassTreeService classTreeService) {
        if (data == null || classId == null || classId.isBlank()
                || classTreeService == null) {
            return null;
        }
        String replacement = classTreeService.getReplacementForSkill(
                data, classId, skill.id());
        if (replacement == null) {
            return null;
        }

        SkillVariantManager manager = plugin.getSkillVariantManager();
        if (manager == null) {
            return null;
        }
        SkillVariantData variant = manager.find(replacement).orElse(null);
        if (variant == null || !variant.baseSkill().equalsIgnoreCase(skill.id())) {
            return null;
        }
        return variant;
    }

    /**
     * 派生スキルのアクションを順番に実行する。
     */
    private DamageResult executeVariant(Player player,
                                        Skill skill,
                                        SkillVariantData variant,
                                        LivingEntity target,
                                        double skillDamageBonus) {
        DamageResult lastDamage = null;
        for (VariantAction action : variant.actions()) {
            if (action == null) {
                continue;
            }
            switch (action.type()) {
                case "SHOOT_PROJECTILE" -> shootProjectile(player, target, action);
                case "DAMAGE" -> {
                    if (target == null || target.isDead()) {
                        continue;
                    }
                    double multiplier = action.number(
                            "multiplier", skill.baseDamageMultiplier())
                            * (1.0 + skillDamageBonus);
                    double armorPenetration = action.number(
                            "armorPenetration", skill.armorPenetration());
                    double lifesteal = action.number(
                            "lifesteal", skill.lifesteal());
                    DamageResult result = combatService.playerAttack(
                            player,
                            target,
                            multiplier,
                            armorPenetration,
                            lifesteal);
                    if (result != null) {
                        lastDamage = result;
                    }
                }
                case "PARTICLE" -> spawnVariantParticle(player, target, action);
                case "SOUND" -> playVariantSound(player, action);
                case "TELEPORT" -> teleportVariant(player, target, action);
                case "HEAL" -> healVariant(player, action);
                case "APPLY_EFFECT" -> applyVariantEffect(player, action);
                default -> {
                    // SkillVariantManager 側で未対応種別は警告済み。
                }
            }
        }
        return lastDamage;
    }

    private void shootProjectile(Player player, LivingEntity target, VariantAction action) {
        int count = Math.max(1, action.integer("count", 1));
        double spread = Math.max(0.0, action.number("spread", 0.0));
        double speed = Math.max(0.1, action.number("speed", 2.0));

        Location eye = player.getEyeLocation();
        Vector baseDirection = target == null
                ? eye.getDirection().clone()
                : target.getEyeLocation().toVector().subtract(eye.toVector());
        if (baseDirection.lengthSquared() <= 0.0) {
            baseDirection = eye.getDirection().clone();
        }
        baseDirection.normalize();

        for (int i = 0; i < count; i++) {
            Vector direction = baseDirection.clone();
            if (spread > 0.0) {
                direction.add(new Vector(
                        (Math.random() - 0.5) * spread * 2.0,
                        (Math.random() - 0.5) * spread * 2.0,
                        (Math.random() - 0.5) * spread * 2.0)).normalize();
            }

            Arrow arrow = player.getWorld().spawn(
                    eye.clone().add(direction.clone().multiply(0.5)), Arrow.class);
            arrow.setShooter(player);
            arrow.setDamage(0.0);
            arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
            arrow.setVelocity(direction.multiply(speed));
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> {
                        if (arrow.isValid()) {
                            arrow.remove();
                        }
                    },
                    40L);
        }
    }

    private void spawnVariantParticle(Player player,
                                      LivingEntity target,
                                      VariantAction action) {
        Particle particle = parseParticle(action.string("particle", ""));
        if (particle == null) {
            return;
        }
        int count = Math.max(0, action.integer("count", 10));
        double spread = Math.max(0.0, action.number("spread", 0.5));
        double speed = Math.max(0.0, action.number("speed", 0.0));
        Location location = target == null
                ? player.getLocation()
                : target.getLocation();
        player.getWorld().spawnParticle(
                particle,
                location.clone().add(0.0, 1.0, 0.0),
                count,
                spread,
                spread,
                spread,
                speed);
    }

    private void playVariantSound(Player player, VariantAction action) {
        String name = action.string("sound", "");
        if (name.isBlank()) {
            return;
        }
        float volume = (float) Math.max(0.0, action.number("volume", 1.0));
        float pitch = (float) Math.max(0.1, action.number("pitch", 1.0));

        Sound sound = parseSound(name);
        if (sound != null) {
            player.getWorld().playSound(player.getLocation(), sound, volume, pitch);
            return;
        }

        // レジストリで解決できない場合のみ、サウンドキー文字列として再生する。
        String fallback = name.trim().toLowerCase(Locale.ROOT).replace('_', '.');
        try {
            player.getWorld().playSound(player.getLocation(), fallback, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // 無効なサウンド名は無視する。
        }
    }

    /**
     * サウンド名を {@link Registry#SOUNDS} から解決する。
     * <p>
     * {@code ENTITY_ARROW_HIT} のような旧 Enum 名、{@code entity.arrow.hit} の
     * ようなレジストリキー、{@code minecraft:entity.arrow.hit} の名前空間付きキーを
     * すべて受け付ける。
     */
    private static Sound parseSound(String name) {
        String trimmed = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }

        String namespace = "minecraft";
        String path = trimmed;
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            namespace = trimmed.substring(0, colon);
            path = trimmed.substring(colon + 1);
        }
        if (path.isEmpty()) {
            return null;
        }

        NamespacedKey directKey;
        try {
            directKey = namespace.equals("minecraft")
                    ? NamespacedKey.minecraft(path)
                    : NamespacedKey.fromString(namespace + ":" + path);
        } catch (IllegalArgumentException ignored) {
            directKey = null;
        }
        Sound direct = directKey == null ? null : Registry.SOUNDS.get(directKey);
        if (direct != null) {
            return direct;
        }

        // 旧 Enum 名は区切り文字の情報が失われているため、英数字のみへ正規化して照合する。
        String normalized = normalizeSoundName(path);
        for (Sound candidate : Registry.SOUNDS) {
            NamespacedKey key = candidate.getKey();
            if (key.getNamespace().equals(namespace)
                    && normalizeSoundName(key.getKey()).equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalizeSoundName(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private void teleportVariant(Player player,
                                 LivingEntity target,
                                 VariantAction action) {
        Location destination;
        if (action.bool("toTarget", false) && target != null) {
            Vector behind = target.getLocation().getDirection()
                    .normalize().multiply(-1.5);
            destination = target.getLocation().clone().add(behind);
        } else if (action.params().containsKey("x")
                || action.params().containsKey("y")
                || action.params().containsKey("z")) {
            Location current = player.getLocation();
            destination = new Location(
                    player.getWorld(),
                    action.number("x", current.getX()),
                    action.number("y", current.getY()),
                    action.number("z", current.getZ()));
        } else {
            double distance = action.number("distance", 5.0);
            Location current = player.getLocation();
            destination = current.clone().add(
                    current.getDirection().normalize().multiply(distance));
        }
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        player.teleport(destination);
    }

    private void healVariant(Player player, VariantAction action) {
        PlayerStats stats = playerStatsService.getStats(player);
        if (stats == null) {
            return;
        }
        double amount = action.number("amount", 0.0)
                + stats.getMaxHealth() * action.number("percent", 0.0);
        if (amount <= 0.0) {
            return;
        }
        stats.setCurrentHealth(stats.getCurrentHealth() + amount);
        combatService.syncPlayerHealth(player);
    }

    private void applyVariantEffect(Player player, VariantAction action) {
        String name = action.string("effect", "");
        if (name.isBlank()) {
            return;
        }
        PotionEffectType type = PotionEffectType.getByName(
                name.trim().toUpperCase(Locale.ROOT));
        if (type == null) {
            return;
        }
        double seconds = action.number(
                "duration", action.number("durationSeconds", 5.0));
        int ticks = (int) Math.max(1L, Math.round(seconds * 20.0));
        int amplifier = Math.max(0, action.integer("amplifier", 0));
        boolean ambient = action.bool("ambient", false);
        boolean particles = action.bool("particles", true);
        player.addPotionEffect(new PotionEffect(
                type, ticks, amplifier, ambient, particles, true));
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
