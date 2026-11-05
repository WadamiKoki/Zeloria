package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.mob.MobStats;
import com.zeloria.mob.MobStatsService;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStats;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * カスタムダメージの適用を担うサービス。
 * <p>
 * バニラのダメージ処理はリスナー側でキャンセルし、本サービスが HP の増減・
 * 死亡・ノックバック・プレイヤーのバニラ体力バー同期を一括して行う。
 * カスタムモブ（{@link MobStats} を持つエンティティ）以外は対象外とし、
 * バニラモブや他プラグインのモブには干渉しない。
 */
public final class CombatService {

    /** 最小ダメージ。 */
    public static final double MIN_DAMAGE = 1.0;

    /** 近接攻撃のノックバック強度。 */
    private static final double KNOCKBACK_STRENGTH = 0.35;

    /** ノックバックの上方向成分。 */
    private static final double KNOCKBACK_Y = 0.3;

    private final Zeloria plugin;

    public CombatService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * プレイヤーからカスタムモブ・プレイヤーへの攻撃を処理する。
     *
     * @param attacker         攻撃者
     * @param victim           対象
     * @param multiplier       スキル倍率（通常攻撃は 1.0）
     * @param armorPenetration 防御貫通率（0.0〜1.0）
     * @param lifesteal        与ダメージに対する吸血率（0.0〜1.0）
     * @return 計算結果。対象がカスタム戦闘対象でない場合は {@code null}
     */
    public DamageResult playerAttack(
            Player attacker,
            LivingEntity victim,
            double multiplier,
            double armorPenetration,
            double lifesteal) {
        PlayerStats attackerStats = statsOf(attacker);
        if (attackerStats == null || victim == null || victim == attacker || victim.isDead()) {
            return null;
        }
        if (victim instanceof Player playerVictim && isPendingDeath(playerVictim)) {
            return null;
        }

        CombatTarget target = resolveTarget(victim);
        if (target == null) {
            return null;
        }

        // 装備補正の防御貫通率・吸血率をスキル側の値へ加算する。
        double totalArmorPenetration = clamp(armorPenetration + attackerStats.getArmorPenetration(), 0.0, 1.0);
        double totalLifesteal = clamp(lifesteal + attackerStats.getLifesteal(), 0.0, 1.0);

        DamageResult result = DamageCalculator.calculate(
                attackerStats.getAttackPower(),
                multiplier,
                attackerStats.getCriticalRate(),
                attackerStats.getCriticalDamage(),
                attackerStats.getAccuracy(),
                target.evasion(),
                target.defense1(),
                target.defense2(),
                totalArmorPenetration,
                true);

        markCombat(attacker);
        if (!result.evaded()) {
            applyDamage(victim, result.damage(), attacker);
            applyKnockback(attacker, victim);
            heal(attacker, attackerStats, result.damage() * totalLifesteal);
        }
        return result;
    }

    /**
     * カスタムモブからプレイヤーへの攻撃を処理する。
     *
     * @param attacker カスタムモブ
     * @param victim   プレイヤー
     * @return 計算結果。対象がカスタムモブでない場合は {@code null}
     */
    public DamageResult mobAttack(LivingEntity attacker, Player victim) {
        MobStatsService mobStatsService = plugin.getMobStatsService();
        PlayerStats victimStats = statsOf(victim);
        if (mobStatsService == null || victimStats == null) {
            return null;
        }

        MobStats attackerStats = mobStatsService.get(attacker);
        if (attackerStats == null || victim.isDead() || isPendingDeath(victim)) {
            return null;
        }

        DamageResult result = DamageCalculator.calculate(
                attackerStats.attackPower(),
                1.0,
                attackerStats.criticalRate(),
                attackerStats.criticalDamage(),
                attackerStats.accuracy(),
                victimStats.getEvasion(),
                victimStats.getDefense1(),
                victimStats.getDefense2(),
                0.0,
                true);

        markCombat(victim);
        if (!result.evaded()) {
            damagePlayer(victim, result.damage());
            applyKnockback(attacker, victim);
        }
        return result;
    }

    /**
     * 指定エンティティがカスタムダメージの対象か判定する。
     *
     * @param entity 対象エンティティ
     * @return プレイヤー、またはカスタムモブであれば {@code true}
     */
    public boolean isCustomTarget(LivingEntity entity) {
        return entity != null && !entity.isDead() && resolveTarget(entity) != null;
    }

    /**
     * 環境ダメージ（落下・炎・毒など）をカスタム HP へ適用する。
     * <p>
     * プレイヤーは 2 段階防御で軽減し、カスタムモブは {@link MobStats} の防御力で
     * 軽減する。カスタムモブ以外のエンティティは対象外。
     *
     * @param victim 対象
     * @param raw    バニラのダメージ量
     * @return 実際に適用したダメージ。対象外の場合は {@code null}
     */
    public DamageResult environmentalDamage(LivingEntity victim, double raw) {
        if (victim == null || victim.isDead() || raw <= 0.0) {
            return null;
        }
        if (victim instanceof Player playerVictim && isPendingDeath(playerVictim)) {
            return null;
        }

        CombatTarget target = resolveTarget(victim);
        if (target == null) {
            return null;
        }

        DamageResult result = DamageCalculator.calculate(
                raw, 1.0, 0.0, 0.0, 100.0, 0.0,
                target.defense1(), target.defense2(), 0.0, false);

        if (victim instanceof Player player) {
            markCombat(player);
        }
        applyDamage(victim, result.damage(), null);
        return result;
    }

    /**
     * プレイヤーの {@link PlayerStats} の HP をバニラの体力バーへ反映する。
     * <p>
     * 最大 HP は {@link Attribute#MAX_HEALTH} の基礎値として設定する。
     * 装備補正などで最大 HP が変わった場合もこのメソッドで追従する。
     *
     * @param player 対象プレイヤー
     */
    public void syncPlayerHealth(Player player) {
        PlayerStats stats = statsOf(player);
        if (stats == null || !player.isOnline()) {
            return;
        }

        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = Math.max(1.0, stats.getMaxHealth());
        if (attribute != null) {
            attribute.setBaseValue(maxHealth);
            maxHealth = attribute.getValue();
        }

        // バニラの体力バーは表示専用。0 にするとバニラ死亡が発生するため 1.0 で下げ止める。
        double health = Math.max(1.0, Math.min(maxHealth, stats.getCurrentHealth()));
        if (Double.compare(player.getHealth(), health) != 0) {
            player.setHealth(health);
        }
    }

    /**
     * ダメージ源からプレイヤーを解決する（投射物は発射者を辿る）。
     *
     * @param damager ダメージ源
     * @return プレイヤー。該当しない場合は {@code null}
     */
    public static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * ダメージ源から近接攻撃者を解決する（投射物は発射者を辿る）。
     *
     * @param damager ダメージ源
     * @return 発射者・攻撃者。該当しない場合は {@code null}
     */
    public static LivingEntity resolveAttacker(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        return null;
    }

    private void applyDamage(LivingEntity victim, double damage, Player attacker) {
        double remaining = Math.max(0.0, victim.getHealth() - damage);

        if (attacker != null) {
            // 撃破経験値（LevelListener#getKiller）が正しく解決されるようにする。
            victim.setKiller(attacker);
        }

        if (victim instanceof Player player) {
            damagePlayer(player, damage);
            return;
        }

        if (remaining <= 0.0) {
            victim.setHealth(0.0);
            return;
        }
        victim.setHealth(remaining);
    }

    /**
     * プレイヤーの真の HP（{@link PlayerStats#getCurrentHealth()}）からダメージを減算する。
     * <p>
     * 減算結果が 0 以下になった瞬間に {@link DeathListener#handleCustomDeath(Player)} を
     * 呼び出す。バニラの {@code PlayerDeathEvent} には依存しない。
     */
    private void damagePlayer(Player player, double damage) {
        if (damage <= 0.0 || isPendingDeath(player)) {
            return;
        }

        PlayerStats stats = statsOf(player);
        if (stats == null) {
            return;
        }

        double nextHealth = stats.getCurrentHealth() - damage;
        stats.setCurrentHealth(Math.max(0.0, nextHealth));
        syncPlayerHealth(player);

        if (nextHealth <= 0.0) {
            DeathListener deathListener = plugin.getDeathListener();
            if (deathListener != null) {
                deathListener.handleCustomDeath(player);
            }
        }
    }

    private boolean isPendingDeath(Player player) {
        DeathListener deathListener = plugin.getDeathListener();
        return deathListener != null && deathListener.isAwaiting(player);
    }

    private void heal(Player player, PlayerStats stats, double amount) {
        if (amount <= 0.0) {
            return;
        }
        stats.setCurrentHealth(stats.getCurrentHealth() + amount);
        syncPlayerHealth(player);
    }

    private void applyKnockback(Entity attacker, LivingEntity victim) {
        if (attacker == null || victim == null) {
            return;
        }
        if (victim instanceof Player player && isPendingDeath(player)) {
            return;
        }
        Vector direction = victim.getLocation().toVector()
                .subtract(attacker.getLocation().toVector());
        if (direction.lengthSquared() <= 0.0) {
            return;
        }
        direction.normalize().multiply(KNOCKBACK_STRENGTH).setY(KNOCKBACK_Y);
        victim.setVelocity(victim.getVelocity().add(direction));
    }

    private CombatTarget resolveTarget(LivingEntity entity) {
        if (entity instanceof Player player) {
            PlayerStats stats = statsOf(player);
            return stats == null
                    ? null
                    : new CombatTarget(
                            stats.getDefense1(), stats.getDefense2(), stats.getEvasion());
        }

        MobStatsService mobStatsService = plugin.getMobStatsService();
        MobStats mobStats = mobStatsService == null ? null : mobStatsService.get(entity);
        return mobStats == null
                ? null
                : new CombatTarget(
                        mobStats.defense1(), mobStats.defense2(), mobStats.evasion());
    }

    private PlayerStats statsOf(Player player) {
        if (player == null) {
            return null;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        return data == null ? null : data.getStats();
    }

    private void markCombat(Player player) {
        CombatStateService combatStateService = plugin.getCombatStateService();
        if (combatStateService != null && player != null) {
            combatStateService.markInCombat(player.getUniqueId());
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 防御側の計算用ステータス。
     */
    private record CombatTarget(double defense1, double defense2, double evasion) {
    }
}
