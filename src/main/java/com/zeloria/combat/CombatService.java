package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.item.WeaponData;
import com.zeloria.item.WeaponType;
import com.zeloria.mob.MobStats;
import com.zeloria.mob.MobStatsService;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStats;
import com.zeloria.world.CollisionManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * カスタムダメージの適用を担うサービス。
 * <p>
 * バニラのダメージ処理はリスナー側でキャンセルし、本サービスが HP の増減・
 * 死亡・プレイヤーのバニラ体力バー同期を一括して行う。
 * ノックバックはカスタム戦闘では付与せず、バニラ由来のノックバックも
 * {@code CombatListener} 側でキャンセルする。
 * カスタムモブ（{@link MobStats} を持つエンティティ）以外は対象外とし、
 * バニラモブや他プラグインのモブには干渉しない。
 * <p>
 * 計算結果が 0 以下のダメージは「無効（0 ダメージ）」として扱い、HP を減らさない
 * （防御力が攻撃力を上回る場合はダメージが通らない）。
 */
public final class CombatService {

    /** バニラの体力バーの最大値（ハート 10 個分）。 */
    private static final double VANILLA_MAX_HEALTH = 20.0;

    private final Zeloria plugin;

    /** プラグインが発射した通常攻撃の投射物（投射物 UUID → 追跡情報）。 */
    private final Map<UUID, ProjectileAttack> customProjectiles = new HashMap<>();

    /** 投射物ごとの壁判定タスク（投射物 UUID → タスク）。 */
    private final Map<UUID, BukkitTask> projectileTasks = new HashMap<>();

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
        if (!result.evaded() && result.damage() > 0.0) {
            applyDamage(victim, result.damage(), attacker, result.critical());
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
     * 武器種ごとの通常攻撃を実行する。
     * <p>
     * 戦闘モード中であること・クールダウン中でないことを確認したうえで、
     * {@link WeaponType} に応じて近接判定または投射物の発射を行う。
     * ダメージはすべて {@link DamageCalculator}（{@link #playerAttack}）を経由する。
     *
     * @param player 攻撃者
     * @param weapon メインハンドの武器定義
     * @return 攻撃を実行した場合は {@code true}（クールダウン中・戦闘モード外は {@code false}）
     */
    public boolean performNormalAttack(Player player, WeaponData weapon) {
        if (player == null || weapon == null || !player.isOnline()) {
            return false;
        }
        CombatModeService modeService = plugin.getCombatModeService();
        if (modeService == null || !modeService.isActive(player)) {
            return false;
        }

        Material cooldownMaterial = weapon.material();
        if (player.hasCooldown(cooldownMaterial)) {
            return false;
        }

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        WeaponType type = WeaponType.primary(weapon.weaponTypes());

        switch (type) {
            case SWORD -> strikeCone(player, weapon, eye, direction, false);
            case DAGGER -> strikeCone(player, weapon, eye, direction, true);
            case AXE -> strikeLine(player, weapon, eye, direction);
            case STAFF -> launchProjectile(player, weapon, eye, direction, true);
            case BOW -> launchProjectile(player, weapon, eye, direction, false);
            default -> strikeCone(player, weapon, eye, direction, false);
        }

        // 連打防止。攻撃が成立しなかった場合も次の攻撃まで待たせる。
        player.setCooldown(cooldownMaterial, weapon.attackSpeedTicks());
        return true;
    }

    /**
     * プラグインが発射した通常攻撃の投射物か判定する。
     *
     * @param projectile 判定対象
     * @return 通常攻撃の投射物の場合は {@code true}
     */
    public boolean isCustomProjectile(Projectile projectile) {
        return projectile != null && customProjectiles.containsKey(projectile.getUniqueId());
    }

    /**
     * 通常攻撃の投射物が着弾したときの処理。
     * <p>
     * 着弾地点でパーティクルとサウンドを再生し、カスタムMOBへ
     * {@link #playerAttack} 経由のダメージを適用する。バニラの投射物ダメージは
     * {@code CombatListener} 側でキャンセルされる。
     *
     * @param event 投射物の着弾イベント
     */
    public void handleProjectileHit(ProjectileHitEvent event) {
        if (event == null) {
            return;
        }

        Projectile projectile = event.getEntity();
        UUID projectileId = projectile.getUniqueId();
        ProjectileAttack attack = customProjectiles.get(projectileId);
        if (attack == null || attack.handled()) {
            return;
        }
        attack.markHandled();
        cancelProjectileTask(projectileId);

        Entity hitEntity = event.getHitEntity();
        Location impact = hitEntity == null
                ? projectile.getLocation()
                : hitEntity.getLocation().add(0.0, hitEntity.getHeight() * 0.5, 0.0);
        playImpactEffects(attack.weapon(), impact);

        if (hitEntity instanceof LivingEntity living
                && living != attack.owner()
                && !(living instanceof Player)
                && isCustomTarget(living)) {
            playerAttack(attack.owner(), living, 1.0, 0.0, 0.0);
        }

        projectile.remove();
        // バニラの EntityDamageByEntityEvent が着弾後に発火する場合も二重ヒットを
        // 防げるよう、追跡解除は次 tick に行う。
        Bukkit.getScheduler().runTask(plugin, () -> customProjectiles.remove(projectileId));
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
        // 0 以下のダメージは無効（HP を減らさない）。バニラ側のキャンセルのため結果は返す。
        if (result.damage() > 0.0) {
            applyDamage(victim, result.damage(), null, false);
        }
        return result;
    }

    /**
     * プレイヤーの {@link PlayerStats} の HP をバニラの体力バーへ反映する。
     * <p>
     * バニラの最大 HP は常に 20（ハート 10 個）に固定し、RPG 側の現在 HP / 最大 HP の
     * 割合をそのまま体力バーへ写す。装備補正などで最大 HP が変わった場合も本メソッドで追従する。
     *
     * @param player 対象プレイヤー
     */
    public void syncPlayerHealth(Player player) {
        PlayerStats stats = statsOf(player);
        if (stats == null || !player.isOnline()) {
            return;
        }

        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) {
            attribute.setBaseValue(VANILLA_MAX_HEALTH);
        }

        double maxHealth = Math.max(1.0, stats.getMaxHealth());
        double ratio = Math.max(0.0, Math.min(1.0, stats.getCurrentHealth() / maxHealth));
        // バニラの体力バーは表示専用。0 にするとバニラ死亡が発生するため 1.0 で下げ止める。
        double health = Math.max(1.0, ratio * VANILLA_MAX_HEALTH);
        if (Double.compare(player.getHealth(), health) != 0) {
            player.setHealth(Math.min(VANILLA_MAX_HEALTH, health));
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

    private void applyDamage(LivingEntity victim, double damage, Player attacker, boolean critical) {
        // 0 以下のダメージは無効。被弾音・HP 減少・キラー設定のいずれも行わない。
        if (victim == null || damage <= 0.0) {
            return;
        }

        double remaining = Math.max(0.0, victim.getHealth() - damage);

        if (attacker != null) {
            // 撃破経験値（LevelListener#getKiller）が正しく解決されるようにする。
            victim.setKiller(attacker);
        }

        if (victim instanceof Player player) {
            damagePlayer(player, damage);
            showDamageIndicator(victim, damage, critical);
            return;
        }

        // バニラと同様に被弾音を周囲へ再生する（カスタムモブは汎用の被弾音）。
        playHurtSound(victim);

        if (remaining <= 0.0) {
            victim.setHealth(0.0);
            showDamageIndicator(victim, damage, critical);
            return;
        }
        victim.setHealth(remaining);
        showDamageIndicator(victim, damage, critical);
    }

    /**
     * ダメージ確定後に被弾エンティティの頭上へダメージ数値を表示する。
     *
     * @param victim   被弾したエンティティ
     * @param damage   確定したダメージ
     * @param critical クリティカルかどうか
     */
    private void showDamageIndicator(LivingEntity victim, double damage, boolean critical) {
        DamageIndicatorService damageIndicatorService = plugin.getDamageIndicatorService();
        if (damageIndicatorService != null) {
            damageIndicatorService.showDamage(victim, damage, critical);
        }
    }

    /**
     * バニラと同等の被弾音を周囲へ再生する。
     * <p>
     * プレイヤーは {@link Sound#ENTITY_PLAYER_HURT}、それ以外（カスタムモブ）は
     * {@link Sound#ENTITY_GENERIC_HURT} を使用し、音量・ピッチはバニラ既定値（1.0）に揃える。
     *
     * @param victim 被弾したエンティティ
     */
    private static void playHurtSound(LivingEntity victim) {
        if (victim == null || !victim.isValid()) {
            return;
        }
        Sound sound = victim instanceof Player ? Sound.ENTITY_PLAYER_HURT : Sound.ENTITY_GENERIC_HURT;
        victim.getWorld().playSound(victim.getLocation(), sound, 1.0f, 1.0f);
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
        playHurtSound(player);
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

    /* ------------------------------------------------------------------
     * 通常攻撃（近接）
     * ------------------------------------------------------------------ */

    /**
     * 扇形範囲内の MOB へダメージを与える（SWORD / DAGGER）。
     *
     * @param player      攻撃者
     * @param weapon      武器定義
     * @param eye         プレイヤーの目位置
     * @param direction   攻撃方向
     * @param nearestOnly 最も近い 1 体のみを対象にする場合は {@code true}
     */
    private void strikeCone(Player player, WeaponData weapon, Location eye,
                            Vector direction, boolean nearestOnly) {
        double range = Math.max(0.1, weapon.attackRange());
        double halfAngle = Math.max(0.0, weapon.attackAngle()) * 0.5;
        Vector origin = eye.toVector();
        List<LivingEntity> targets = new ArrayList<>();

        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || !isAttackableMob(player, living)) {
                continue;
            }

            Vector toTarget = aimPoint(living).subtract(origin);
            double distance = toTarget.length();
            if (distance <= 0.0 || distance > range) {
                continue;
            }

            double angle = Math.toDegrees(Math.acos(
                    clamp(toTarget.dot(direction) / distance, -1.0, 1.0)));
            if (angle > halfAngle + 0.0001) {
                continue;
            }
            targets.add(living);
        }

        targets.sort(Comparator.comparingDouble(
                living -> aimPoint(living).distanceSquared(origin)));
        if (nearestOnly) {
            if (!targets.isEmpty()) {
                damageTarget(player, targets.get(0));
            }
        } else {
            for (LivingEntity target : targets) {
                damageTarget(player, target);
            }
        }

        playAttackEffects(weapon, eye, direction, range);
    }

    /**
     * レイキャストによる直線攻撃（AXE）。
     * <p>
     * 最初に当たった地点を中心に半径 1.5 ブロック以内の MOB 全てへダメージを与える。
     */
    private void strikeLine(Player player, WeaponData weapon, Location eye, Vector direction) {
        double range = Math.max(0.1, weapon.attackRange());
        RayTraceResult result = player.getWorld().rayTraceEntities(
                eye,
                direction,
                range,
                0.5,
                entity -> entity instanceof LivingEntity living
                        && !(living instanceof Player)
                        && isCustomTarget(living));

        if (result == null || result.getHitPosition() == null) {
            playAttackEffects(weapon, eye, direction, range);
            return;
        }

        Vector hitPosition = result.getHitPosition();
        Location impact = hitPosition.toLocation(player.getWorld());
        double radius = 1.5;
        for (Entity entity : player.getWorld().getNearbyEntities(impact, radius, radius, radius)) {
            if (entity instanceof LivingEntity living && isAttackableMob(player, living)) {
                damageTarget(player, living);
            }
        }

        double hitDistance = Math.min(range, eye.toVector().distance(hitPosition));
        playAttackEffects(weapon, eye, direction, hitDistance);
    }

    /* ------------------------------------------------------------------
     * 通常攻撃（遠距離）
     * ------------------------------------------------------------------ */

    /**
     * 武器種に応じた投射物を発射する（STAFF / BOW）。
     */
    private void launchProjectile(Player player, WeaponData weapon, Location eye,
                                  Vector direction, boolean staff) {
        if (staff) {
            SmallFireball fireball = player.launchProjectile(
                    SmallFireball.class, direction.clone().multiply(6.0));
            fireball.setIsIncendiary(false);
            fireball.setYield(0.0f);
            trackProjectile(player, weapon, fireball);
        } else {
            Arrow arrow = player.launchProjectile(
                    Arrow.class, direction.clone().multiply(8.0));
            arrow.setDamage(0.0);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            trackProjectile(player, weapon, arrow);
        }

        playAttackEffects(weapon, eye, direction, Math.min(weapon.attackRange(), 3.0));
    }

    /**
     * 投射物を追跡対象へ登録し、毎 tick の壁判定を開始する。
     */
    private void trackProjectile(Player owner, WeaponData weapon, Projectile projectile) {
        UUID projectileId = projectile.getUniqueId();
        customProjectiles.put(projectileId,
                new ProjectileAttack(owner, weapon, projectile, projectile.getLocation()));

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> checkProjectileCollision(projectileId), 1L, 1L);
        BukkitTask previous = projectileTasks.put(projectileId, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * 投射物が壁ブロックへ入っていないか毎 tick 確認する。
     * <p>
     * 前 tick からの移動経路も分割して判定し、高速移動時の壁抜けを防ぐ。
     */
    private void checkProjectileCollision(UUID projectileId) {
        ProjectileAttack attack = customProjectiles.get(projectileId);
        if (attack == null) {
            cancelProjectileTask(projectileId);
            return;
        }

        Projectile projectile = attack.projectile();
        if (!projectile.isValid() || projectile.isDead()) {
            forgetProjectile(projectileId);
            return;
        }

        Location current = projectile.getLocation();
        if (isSolidAt(current) || isSolidBetween(attack.lastLocation(), current)) {
            forgetProjectile(projectileId);
            projectile.remove();
            return;
        }
        attack.setLastLocation(current);
    }

    private void forgetProjectile(UUID projectileId) {
        customProjectiles.remove(projectileId);
        cancelProjectileTask(projectileId);
    }

    private void cancelProjectileTask(UUID projectileId) {
        BukkitTask task = projectileTasks.remove(projectileId);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 2 点間の移動経路に壁ブロックが含まれるか判定する。
     */
    private boolean isSolidBetween(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return false;
        }

        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        if (distance <= 0.0) {
            return isSolidAt(to);
        }

        int steps = Math.max(1, (int) Math.ceil(distance / 0.25));
        for (int i = 1; i <= steps; i++) {
            Location point = from.clone().add(delta.clone().multiply((double) i / steps));
            if (isSolidAt(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定位置のブロックが {@link CollisionManager} の壁判定に含まれるか確認する。
     */
    private boolean isSolidAt(Location location) {
        if (location == null) {
            return false;
        }
        CollisionManager collisionManager = plugin.getCollisionManager();
        return collisionManager != null && collisionManager.isSolidBlock(location.getBlock().getType());
    }

    /* ------------------------------------------------------------------
     * 通常攻撃の共通処理
     * ------------------------------------------------------------------ */

    /**
     * 通常攻撃の対象として有効な MOB か判定する（プレイヤーは対象外）。
     */
    private boolean isAttackableMob(Player attacker, LivingEntity target) {
        return target != null
                && target != attacker
                && !(target instanceof Player)
                && isCustomTarget(target);
    }

    /**
     * 対象の胴体中央を狙う座標を返す。
     */
    private static Vector aimPoint(LivingEntity entity) {
        return entity.getLocation().add(0.0, entity.getHeight() * 0.5, 0.0).toVector();
    }

    /**
     * カスタムダメージを適用する（通常攻撃は等倍・防御貫通なし）。
     */
    private void damageTarget(Player attacker, LivingEntity target) {
        playerAttack(attacker, target, 1.0, 0.0, 0.0);
    }

    /**
     * 攻撃方向へパーティクルを発生させ、武器の攻撃音を再生する。
     * <p>
     * Display Entity は Geyser 経由の Bedrock プレイヤーに表示されないため、
     * 武器エフェクトは {@link org.bukkit.World#spawnParticle} によるパーティクルで統一する。
     */
    private void playAttackEffects(WeaponData weapon, Location origin, Vector direction, double reach) {
        Particle particle = parseParticle(weapon.attackParticle());
        if (particle != null && origin.getWorld() != null) {
            double length = Math.max(1.0, Math.min(reach, 6.0));
            int points = Math.max(3, Math.min(12, (int) Math.ceil(length)));
            for (int i = 1; i <= points; i++) {
                Location point = origin.clone()
                        .add(direction.clone().multiply(length * i / points));
                origin.getWorld().spawnParticle(
                        particle, point, 1, 0.1, 0.1, 0.1, 0.0);
            }
        }
        playWeaponSound(weapon, origin);
    }

    /**
     * 着弾地点でパーティクルを拡散させ、武器の攻撃音を再生する。
     */
    private void playImpactEffects(WeaponData weapon, Location impact) {
        Particle particle = parseParticle(weapon.attackParticle());
        if (particle != null && impact.getWorld() != null) {
            impact.getWorld().spawnParticle(
                    particle, impact, 8, 0.25, 0.25, 0.25, 0.02);
        }
        playWeaponSound(weapon, impact);
    }

    private void playWeaponSound(WeaponData weapon, Location location) {
        if (location.getWorld() == null) {
            return;
        }
        Sound sound = parseSound(weapon.attackSound());
        if (sound == null) {
            sound = parseSound(WeaponType.SWORD.attackSound());
        }
        if (sound != null) {
            location.getWorld().playSound(location, sound, 1.0f, 1.0f);
        }
    }

    /**
     * パーティクル名を解決する。未指定・不正な場合は {@code null}。
     */
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

    /**
     * サウンド名を {@link Registry#SOUNDS} から解決する。
     * <p>
     * {@code ENTITY_PLAYER_ATTACK_SWEEP} のような旧 Enum 名、
     * {@code entity.player.attack.sweep} のようなレジストリキーの双方を受け付ける。
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

        String normalized = normalizeSoundName(path);
        for (Sound candidate : Registry.SOUNDS) {
            NamespacedKey key = Registry.SOUNDS.getKey(candidate);
            if (key != null
                    && key.getNamespace().equals(namespace)
                    && normalizeSoundName(key.getKey()).equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalizeSoundName(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }

    /**
     * 追跡中の通常攻撃投射物。
     */
    private static final class ProjectileAttack {

        private final Player owner;
        private final WeaponData weapon;
        private final Projectile projectile;
        private Location lastLocation;
        private boolean handled;

        private ProjectileAttack(Player owner, WeaponData weapon,
                                 Projectile projectile, Location lastLocation) {
            this.owner = owner;
            this.weapon = weapon;
            this.projectile = projectile;
            this.lastLocation = lastLocation;
        }

        private Player owner() {
            return owner;
        }

        private WeaponData weapon() {
            return weapon;
        }

        private Projectile projectile() {
            return projectile;
        }

        private Location lastLocation() {
            return lastLocation;
        }

        private void setLastLocation(Location lastLocation) {
            this.lastLocation = lastLocation;
        }

        private boolean handled() {
            return handled;
        }

        private void markHandled() {
            this.handled = true;
        }
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
