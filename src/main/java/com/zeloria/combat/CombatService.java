package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.item.WeaponData;
import com.zeloria.item.WeaponType;
import com.zeloria.mob.MobRewardListener;
import com.zeloria.mob.MobStats;
import com.zeloria.mob.MobStatsService;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStats;
import com.zeloria.player.PlayerStatusDisplayTask;
import com.zeloria.world.CollisionManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 被ダメージ音のサウンド ID（レジストリキー形式）。
     * <p>
     * プレイヤー・モブの区別なく、すべての被ダメージで本サウンドを共通使用する。
     */
    private static final String HURT_SOUND = "entity.player.hurt";

    /** 被ダメージ音の再生音量。 */
    private static final float HURT_SOUND_VOLUME = 0.5f;

    /** 被ダメージ音の再生ピッチ。 */
    private static final float HURT_SOUND_PITCH = 1.0f;

    /** 被ダメージ時の赤色化アニメーションへ渡す向き（0 で正面扱い）。 */
    private static final float HURT_ANIMATION_YAW = 0.0f;

    /** 杖の最大射程（ブロック）。 */
    private static final double STAFF_MAX_RANGE = 10.0;

    /** 杖の投射物速度（ブロック / tick）。0.25 は 5 m/s。 */
    private static final double STAFF_SPEED = 0.25;

    /** 杖の着弾地点からダメージを与える半径（ブロック）。 */
    private static final double STAFF_AOE_RADIUS = 2.0;

    /**
     * 近接武器の軌跡パーティクル。
     * <p>
     * Bedrock（Geyser）でも表示される {@link Particle#DUST} を使用し、武器種ごとに
     * 色とサイズを変える。{@code SWEEP_ATTACK} は Bedrock で描画されないため使用しない。
     */
    private static final Particle.DustOptions SWORD_DUST =
            new Particle.DustOptions(Color.fromRGB(0x66D9FF), 1.8f);
    private static final Particle.DustOptions AXE_DUST =
            new Particle.DustOptions(Color.fromRGB(0xFF8A1E), 2.0f);
    private static final Particle.DustOptions DAGGER_DUST =
            new Particle.DustOptions(Color.fromRGB(0xFFE24A), 1.5f);
    private static final Particle.DustOptions STAFF_DUST =
            new Particle.DustOptions(Color.fromRGB(0xB05CFF), 1.6f);
    private static final Particle.DustOptions BOW_DUST =
            new Particle.DustOptions(Color.fromRGB(0x8CFF8C), 1.4f);

    private final Zeloria plugin;

    /** プラグインが発射した通常攻撃の投射物（投射物 UUID → 追跡情報）。 */
    private final Map<UUID, ProjectileAttack> customProjectiles = new HashMap<>();

    /** 投射物ごとの壁判定タスク（投射物 UUID → タスク）。 */
    private final Map<UUID, BukkitTask> projectileTasks = new HashMap<>();

    /**
     * モブ攻撃の最終受付時刻。
     * <p>
     * キーは「モブの UUID + ':' + プレイヤーの UUID」。モブごとに独立して
     * クールダウンを持つため、別々のモブからの攻撃は相互に制限されない。
     */
    private final Map<String, Long> mobAttackCooldowns = new HashMap<>();

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
                true,
                plugin.getCombatSettings().defense().curveConstant());

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

        // 同じモブから同一プレイヤーへの連続ヒットを設定された間隔で遮断する。
        // 2 回目以降もイベント自体は「処理済み」としてキャンセルさせるため、
        // 0 ダメージの結果を返す（フォールバックの環境ダメージへ流さない）。
        String cooldownKey = attacker.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMillis = plugin.getCombatSettings().mobAttackCooldownMillis();
        Long lastHitAt = mobAttackCooldowns.get(cooldownKey);
        if (lastHitAt != null && now - lastHitAt < cooldownMillis) {
            return DamageResult.NONE;
        }
        mobAttackCooldowns.put(cooldownKey, now);
        pruneMobAttackCooldowns(now);

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
                true,
                plugin.getCombatSettings().defense().curveConstant());

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
            case AXE -> strikeAxe(player, weapon, eye, direction);
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
     * 杖は着弾地点を中心とした範囲攻撃、弓は直撃した MOB のみへダメージを与える。
     * バニラの投射物ダメージは {@code CombatListener} 側でキャンセルされる。
     *
     * @param event 投射物の着弾イベント
     */
    public void handleProjectileHit(ProjectileHitEvent event) {
        if (event == null) {
            return;
        }

        Projectile projectile = event.getEntity();
        ProjectileAttack attack = customProjectiles.get(projectile.getUniqueId());
        if (attack == null || attack.handled()) {
            return;
        }

        Entity hitEntity = event.getHitEntity();
        LivingEntity directHit = hitEntity instanceof LivingEntity living ? living : null;
        Location impact = directHit == null
                ? projectile.getLocation()
                : directHit.getLocation().add(0.0, directHit.getHeight() * 0.5, 0.0);
        applyProjectileImpact(attack, projectile, directHit, impact);
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
                target.defense1(), target.defense2(), 0.0, false,
                plugin.getCombatSettings().defense().curveConstant());

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
        if (victim == null || damage <= 0.0 || victim.isDead()) {
            return;
        }

        if (attacker != null) {
            // 撃破経験値（MobRewardListener#getKiller）が正しく解決されるようにする。
            victim.setKiller(attacker);
        }

        if (victim instanceof Player player) {
            // プレイヤーの真の HP 減算・体力バー同期・赤色化は damagePlayer が一括して行う。
            damagePlayer(player, damage);
            showDamageIndicator(victim, damage, critical);
            return;
        }

        // モブの HP はバニラ体力が正のため直接減算する。entity.damage() は
        // 無敵時間やイベント再入（二重ヒット）を招くため使用しない。
        // 直接減算ではダメージイベントが発生しないため、経験値分配用の
        // 攻撃者記録を明示的に行ってから HP を減らす（HP 0 の場合は
        // setHealth の中で EntityDeathEvent が同期的に発火する）。
        MobRewardListener rewardListener = plugin.getMobRewardListener();
        if (rewardListener != null) {
            rewardListener.recordAttacker(victim, attacker);
        }
        double remainingHealth = Math.max(0.0, victim.getHealth() - damage);
        victim.setHealth(remainingHealth);
        // HP 0（死亡）の場合は死亡演出と競合するため Hurt アニメーションを再生しない。
        if (remainingHealth > 0.0) {
            victim.playHurtAnimation(HURT_ANIMATION_YAW);
        }
        playHurtSound(victim);
        showDamageIndicator(victim, damage, critical);
    }

    /**
     * 古いモブ攻撃クールダウンの記録を間引く。
     *
     * @param now 現在時刻（ミリ秒）
     */
    private void pruneMobAttackCooldowns(long now) {
        if (mobAttackCooldowns.size() < 1024) {
            return;
        }
        long cooldownMillis = plugin.getCombatSettings().mobAttackCooldownMillis();
        mobAttackCooldowns.entrySet().removeIf(
                entry -> now - entry.getValue() > cooldownMillis * 10L);
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
     * 被ダメージ音を再生する。
     * <p>
     * プレイヤー・モブの区別なく {@value #HURT_SOUND} を共通で使用する。
     *
     * @param victim 被弾したエンティティ
     */
    private void playHurtSound(LivingEntity victim) {
        if (victim == null || !victim.isValid() || victim.getWorld() == null) {
            return;
        }
        victim.getWorld().playSound(
                victim.getLocation(), HURT_SOUND, HURT_SOUND_VOLUME, HURT_SOUND_PITCH);
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

        // 真の HP を直接減算し、バニラ体力バーへ同期する。赤色化は syncPlayerHealth の
        // 体力減少と playHurtAnimation の両方で表現する。HP 0（死亡）の場合は
        // スペクテイター移行の演出と競合するため Hurt アニメーションを再生しない。
        double nextHealth = Math.max(0.0, stats.getCurrentHealth() - damage);
        stats.setCurrentHealth(nextHealth);
        syncPlayerHealth(player);
        if (nextHealth > 0.0) {
            player.playHurtAnimation(HURT_ANIMATION_YAW);
        }
        playHurtSound(player);
        // 頭上 HP ゲージを被弾と同時に更新する。
        PlayerStatusDisplayTask statusDisplayTask = plugin.getPlayerStatusDisplayTask();
        if (statusDisplayTask != null) {
            statusDisplayTask.refresh(player);
        }

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
        PlayerStatusDisplayTask statusDisplayTask = plugin.getPlayerStatusDisplayTask();
        if (statusDisplayTask != null) {
            statusDisplayTask.refresh(player);
        }
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
     * 斧の広範囲攻撃（AXE）。
     * <p>
     * 視線方向を中心に、左右へ広く・上方向へさらに広い扇状の判定を行う。
     * 従来のレイキャストでは視点の上下に追従できなかったため、視線の
     * ローカル座標（前・右・上）へ対象を射影して判定する。
     */
    private void strikeAxe(Player player, WeaponData weapon, Location eye, Vector direction) {
        double range = Math.max(0.1, weapon.attackRange());
        Vector forward = normalizeDirection(direction, eye);
        ViewBasis basis = viewBasis(forward, eye.getYaw());
        Vector origin = eye.toVector();

        double horizontalHalf = Math.toRadians(55.0);
        double upwardHalf = Math.toRadians(75.0);
        double downwardHalf = Math.toRadians(35.0);

        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || !isAttackableMob(player, living)) {
                continue;
            }

            Vector toTarget = aimPoint(living).subtract(origin);
            double distance = toTarget.length();
            if (distance <= 0.0 || distance > range) {
                continue;
            }

            double forwardDistance = toTarget.dot(basis.forward());
            if (forwardDistance <= 0.0) {
                continue;
            }

            double lateral = toTarget.dot(basis.right());
            double vertical = toTarget.dot(basis.up());
            double maxLateral = Math.max(0.8, forwardDistance * Math.tan(horizontalHalf));
            double maxUp = Math.max(1.2, forwardDistance * Math.tan(upwardHalf));
            double maxDown = Math.max(0.4, forwardDistance * Math.tan(downwardHalf));
            if (Math.abs(lateral) > maxLateral) {
                continue;
            }
            if (vertical > maxUp || vertical < -maxDown) {
                continue;
            }

            // 壁越しに斧が届かないよう、目の位置から対象の胴体中央までを確認する。
            Location targetCenter = aimPoint(living).toLocation(player.getWorld());
            if (isSolidBetween(eye, targetCenter)) {
                continue;
            }
            damageTarget(player, living);
        }

        playAttackEffects(weapon, eye, forward, range);
    }

    /* ------------------------------------------------------------------
     * 通常攻撃（遠距離）
     * ------------------------------------------------------------------ */

    /**
     * 武器種に応じた投射物を発射する（STAFF / BOW）。
     * <p>
     * 杖は 0.25 ブロック / tick（5 m/s）の固定速度で発射し、射程は 10 ブロック。
     * 速度を毎 tick 上書きすることで、バニラの空気抵抗による減速を打ち消す。
     */
    private void launchProjectile(Player player, WeaponData weapon, Location eye,
                                  Vector direction, boolean staff) {
        Vector normalized = normalizeDirection(direction, eye);
        if (staff) {
            Vector velocity = normalized.clone().multiply(STAFF_SPEED);
            SmallFireball fireball = player.launchProjectile(
                    SmallFireball.class, velocity.clone());
            fireball.setIsIncendiary(false);
            fireball.setYield(0.0f);
            fireball.setGravity(false);
            trackProjectile(player, weapon, fireball, velocity);
        } else {
            Arrow arrow = player.launchProjectile(
                    Arrow.class, normalized.clone().multiply(8.0));
            arrow.setDamage(0.0);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            trackProjectile(player, weapon, arrow, null);
        }

        playAttackEffects(weapon, eye, normalized, Math.min(weapon.attackRange(), 3.0));
    }

    /**
     * 投射物を追跡対象へ登録し、毎 tick の壁・敵判定を開始する。
     */
    private void trackProjectile(Player owner, WeaponData weapon, Projectile projectile,
                                 Vector velocity) {
        UUID projectileId = projectile.getUniqueId();
        Location origin = projectile.getLocation().clone();
        customProjectiles.put(projectileId,
                new ProjectileAttack(owner, weapon, projectile, origin, velocity));

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> checkProjectileCollision(projectileId), 1L, 1L);
        BukkitTask previous = projectileTasks.put(projectileId, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * 投射物の移動経路・射程・壁を毎 tick 確認する。
     * <p>
     * 前 tick からの移動経路を 0.1 ブロック間隔で走査し、MOB の
     * {@link BoundingBox} と交差した時点で命中させる。杖は 10 ブロックで
     * 強制的に着弾し、着弾地点から 2 ブロック以内へ範囲ダメージを与える。
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

        WeaponType type = WeaponType.primary(attack.weapon().weaponTypes());
        if (type == WeaponType.STAFF && attack.velocity() != null) {
            // 杖は常に一定速度を維持する（バニラの減速を打ち消す）。
            projectile.setVelocity(attack.velocity().clone());
        }

        Location current = projectile.getLocation();
        if (type == WeaponType.STAFF
                && attack.origin().getWorld() != null
                && current.getWorld() != null
                && attack.origin().getWorld().equals(current.getWorld())
                && attack.origin().distanceSquared(current) >= STAFF_MAX_RANGE * STAFF_MAX_RANGE) {
            applyProjectileImpact(attack, projectile, null, current);
            return;
        }

        LivingEntity directHit = findEntityOnPath(attack, attack.lastLocation(), current);
        if (directHit != null) {
            Location impact = aimPoint(directHit).toLocation(current.getWorld());
            applyProjectileImpact(attack, projectile, directHit, impact);
            return;
        }

        if (isSolidAt(current) || isSolidBetween(attack.lastLocation(), current)) {
            applyProjectileImpact(attack, projectile, null, current);
            return;
        }
        attack.setLastLocation(current);
    }

    /**
     * 前 tick から現在位置までの移動経路に重なっている MOB を探す。
     * <p>
     * 弾の中心点だけでなく、MOB の体全体の {@link BoundingBox} との交差で判定する。
     */
    private LivingEntity findEntityOnPath(ProjectileAttack attack, Location from, Location to) {
        if (to == null || to.getWorld() == null) {
            return null;
        }

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : to.getWorld().getNearbyEntities(to, 2.0, 2.0, 2.0)) {
            if (!(entity instanceof LivingEntity living)
                    || !isAttackableMob(attack.owner(), living)) {
                continue;
            }
            if (!intersectsPath(living.getBoundingBox(), from, to)) {
                continue;
            }

            double distance = living.getLocation().distanceSquared(to);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = living;
            }
        }
        return nearest;
    }

    /**
     * 移動経路が {@link BoundingBox} と交差するか判定する。
     */
    private static boolean intersectsPath(BoundingBox box, Location from, Location to) {
        if (box == null || from == null || to == null
                || from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return false;
        }

        // 高速移動時の取りこぼしを防ぐため、MOB の当たり判定を少し広げる。
        BoundingBox expanded = box.clone().expand(0.15);
        Vector start = from.toVector();
        Vector delta = to.toVector().subtract(start);
        double distance = delta.length();
        if (distance <= 0.0) {
            return expanded.contains(start);
        }

        int steps = Math.max(1, (int) Math.ceil(distance / 0.1));
        for (int i = 0; i <= steps; i++) {
            Vector point = start.clone().add(delta.clone().multiply((double) i / steps));
            if (expanded.contains(point)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 投射物の着弾を確定し、ダメージ・パーティクル・追跡解除をまとめて行う。
     */
    private void applyProjectileImpact(ProjectileAttack attack, Projectile projectile,
                                       LivingEntity directHit, Location impact) {
        if (attack == null || projectile == null || attack.handled()) {
            return;
        }
        attack.markHandled();

        UUID projectileId = projectile.getUniqueId();
        cancelProjectileTask(projectileId);

        Location center = directHit == null
                ? (impact == null ? projectile.getLocation() : impact.clone())
                : directHit.getLocation().add(0.0, directHit.getHeight() * 0.5, 0.0);
        playImpactEffects(attack.weapon(), center);

        WeaponType type = WeaponType.primary(attack.weapon().weaponTypes());
        World world = center.getWorld();
        if (type == WeaponType.STAFF && world != null) {
            List<LivingEntity> targets = new ArrayList<>();
            for (Entity entity : world.getNearbyEntities(
                    center, STAFF_AOE_RADIUS, STAFF_AOE_RADIUS, STAFF_AOE_RADIUS)) {
                if (!(entity instanceof LivingEntity living)
                        || !isAttackableMob(attack.owner(), living)) {
                    continue;
                }
                if (aimPoint(living).distanceSquared(center.toVector())
                        > STAFF_AOE_RADIUS * STAFF_AOE_RADIUS) {
                    continue;
                }
                targets.add(living);
            }
            for (LivingEntity target : targets) {
                damageTarget(attack.owner(), target);
            }
        } else if (directHit != null && isAttackableMob(attack.owner(), directHit)) {
            damageTarget(attack.owner(), directHit);
        }

        projectile.remove();
        // バニラの EntityDamageByEntityEvent が着弾後に発火する場合も二重ヒットを
        // 防げるよう、追跡解除は次 tick に行う。
        Bukkit.getScheduler().runTask(plugin, () -> customProjectiles.remove(projectileId));
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
     * 攻撃方向へ武器種ごとの軌跡を描き、武器の攻撃音を再生する。
     * <p>
     * Display Entity は Geyser 経由の Bedrock プレイヤーに表示されないため、
     * 武器エフェクトは {@link org.bukkit.World#spawnParticle} による
     * Bedrock 互換の {@link Particle#DUST} で統一する。軌跡は視線の
     * ローカル座標（前・右・上）へ展開するため、上下左右を向いても追従する。
     */
    private void playAttackEffects(WeaponData weapon, Location origin, Vector direction, double reach) {
        if (origin.getWorld() == null) {
            return;
        }
        World world = origin.getWorld();
        Vector forward = normalizeDirection(direction, origin);
        ViewBasis basis = viewBasis(forward, origin.getYaw());

        switch (WeaponType.primary(weapon.weaponTypes())) {
            case AXE -> drawAxeArc(world, origin, basis);
            case DAGGER -> drawDaggerArc(world, origin, basis);
            case STAFF -> drawMuzzle(world, origin, forward, STAFF_DUST, true);
            case BOW -> drawMuzzle(world, origin, forward, BOW_DUST, false);
            default -> drawSwordArc(world, origin, basis);
        }
        playWeaponSound(weapon, origin);
    }

    /**
     * 剣の横薙ぎ。水色の DUST で前方 3〜4 ブロックの弧を描く。
     */
    private static void drawSwordArc(World world, Location origin, ViewBasis basis) {
        int points = 15;
        for (int i = 0; i < points; i++) {
            double t = (double) i / (points - 1);
            double angle = Math.toRadians(-58.0 + 116.0 * t);
            double forward = 1.6 + 1.5 * Math.cos(angle);
            double lateral = 1.85 * Math.sin(angle);
            double sag = -0.25 * Math.sin(Math.PI * t);
            Location point = arcPoint(origin, basis, forward, lateral, sag);
            spawnDust(world, point, SWORD_DUST);
            if (i % 3 == 0) {
                spawnParticle(world, Particle.ENCHANTED_HIT, point, 1, 0.04);
            }
            if (i % 5 == 0) {
                spawnParticle(world, Particle.CRIT, point, 1, 0.04);
            }
        }
    }

    /**
     * 斧の振り上げ。オレンジの DUST で地面付近から前方上方向へ弧を描く。
     */
    private static void drawAxeArc(World world, Location origin, ViewBasis basis) {
        int points = 16;
        for (int i = 0; i < points; i++) {
            double t = (double) i / (points - 1);
            double forward = 0.9 + 2.9 * t;
            double lateral = 1.35 * Math.sin(Math.PI * t);
            double rise = -1.1 + 2.7 * t + 0.25 * Math.sin(Math.PI * t);
            Location point = arcPoint(origin, basis, forward, lateral, rise);
            spawnDust(world, point, AXE_DUST);
            if (i % 2 == 0) {
                spawnParticle(world, Particle.CRIT, point, 1, 0.05);
            }
        }
    }

    /**
     * 短剣の小さな弧。黄色の DUST で前方 1.5〜2 ブロックを描く。
     */
    private static void drawDaggerArc(World world, Location origin, ViewBasis basis) {
        int points = 10;
        for (int i = 0; i < points; i++) {
            double t = (double) i / (points - 1);
            double angle = Math.toRadians(-50.0 + 100.0 * t);
            double forward = 0.5 + 1.1 * Math.cos(angle);
            double lateral = 0.85 * Math.sin(angle);
            double sag = -0.08 * Math.sin(Math.PI * t);
            Location point = arcPoint(origin, basis, forward, lateral, sag);
            spawnDust(world, point, DAGGER_DUST);
            if (i % 2 == 0) {
                spawnParticle(world, Particle.CRIT, point, 1, 0.04);
            }
        }
    }

    /**
     * 杖・弓の発射口に短い軌跡を描く。
     */
    private static void drawMuzzle(World world, Location origin, Vector forward,
                                   Particle.DustOptions dust, boolean flame) {
        for (int i = 1; i <= 6; i++) {
            double distance = 0.35 + i * 0.16;
            Location point = origin.clone().add(forward.clone().multiply(distance));
            spawnDust(world, point, dust);
            if (flame && i % 2 == 0) {
                spawnParticle(world, Particle.FLAME, point, 1, 0.03);
            }
        }
    }

    /**
     * 着弾地点で武器種ごとのパーティクルを拡散させ、弓以外の武器の攻撃音を再生する。
     * <p>
     * 弓の矢はブロック・エンティティのどちらに当たっても着弾音を再生しない。
     * エンティティへ当たった際の被ダメージ音は {@link #applyDamage} 側で共通再生する。
     */
    private void playImpactEffects(WeaponData weapon, Location impact) {
        if (impact == null || impact.getWorld() == null) {
            return;
        }
        World world = impact.getWorld();
        WeaponType type = WeaponType.primary(weapon.weaponTypes());

        switch (type) {
            case AXE -> {
                burst(world, impact, AXE_DUST, 14, 0.3);
                spawnParticle(world, Particle.CRIT, impact, 8, 0.3);
            }
            case DAGGER -> {
                burst(world, impact, DAGGER_DUST, 8, 0.22);
                spawnParticle(world, Particle.CRIT, impact, 5, 0.22);
            }
            case STAFF -> {
                burst(world, impact, STAFF_DUST, 16, 0.35);
                spawnParticle(world, Particle.FLAME, impact, 12, 0.35);
            }
            case BOW -> spawnParticle(world, Particle.CRIT, impact, 6, 0.2);
            default -> {
                burst(world, impact, SWORD_DUST, 12, 0.28);
                spawnParticle(world, Particle.ENCHANTED_HIT, impact, 8, 0.28);
            }
        }

        if (type != WeaponType.BOW) {
            playWeaponSound(weapon, impact);
        }
    }

    /**
     * DUST を指定数拡散させる。
     */
    private static void burst(World world, Location location, Particle.DustOptions dust,
                              int count, double spread) {
        world.spawnParticle(Particle.DUST, location, count, spread, spread, spread, 0.01, dust);
    }

    /**
     * データを持たないパーティクルを指定数発生させる。
     */
    private static void spawnParticle(World world, Particle particle, Location location,
                                      int count, double spread) {
        world.spawnParticle(particle, location, count, spread, spread, spread, 0.0);
    }

    /**
     * 単発の DUST を発生させる。
     */
    private static void spawnDust(World world, Location location, Particle.DustOptions dust) {
        world.spawnParticle(Particle.DUST, location, 1, 0.02, 0.02, 0.02, 0.0, dust);
    }

    /**
     * 視線のローカル座標（前・右・上）へオフセットした座標を返す。
     */
    private static Location arcPoint(Location origin, ViewBasis basis,
                                     double forward, double lateral, double upward) {
        return origin.clone()
                .add(basis.forward().clone().multiply(forward))
                .add(basis.right().clone().multiply(lateral))
                .add(basis.up().clone().multiply(upward));
    }

    private void playWeaponSound(WeaponData weapon, Location location) {
        if (location.getWorld() == null) {
            return;
        }
        String sound = weapon.attackSound();
        if (sound.isBlank()) {
            sound = WeaponType.SWORD.attackSound();
        }
        location.getWorld().playSound(location, sound, 1.0f, 1.0f);
    }

    /**
     * 正規化した視線方向を返す。方向が不正な場合は目位置の向きを使用する。
     */
    private static Vector normalizeDirection(Vector direction, Location origin) {
        Vector result = direction == null ? null : direction.clone();
        if (result == null || result.lengthSquared() < 1.0E-6) {
            result = origin.getDirection();
        }
        if (result.lengthSquared() < 1.0E-6) {
            result = new Vector(0.0, 0.0, 1.0);
        }
        return result.normalize();
    }

    /**
     * 視線方向から「前・右・上」のローカル座標系を構築する。
     * <p>
     * 真上・真下を向いている場合は水平成分が消えるため、プレイヤーの yaw から
     * 右方向を再構築する。これにより上下左右どの向きでもパーティクルと
     * 攻撃判定が視線に追従する。
     */
    private static ViewBasis viewBasis(Vector forward, float yaw) {
        Vector right = forward.clone().crossProduct(new Vector(0.0, 1.0, 0.0));
        if (right.lengthSquared() < 1.0E-4) {
            double radians = Math.toRadians(yaw);
            right = new Vector(-Math.cos(radians), 0.0, -Math.sin(radians));
        }
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        return new ViewBasis(forward, right, up);
    }

    /**
     * 追跡中の通常攻撃投射物。
     */
    private static final class ProjectileAttack {

        private final Player owner;
        private final WeaponData weapon;
        private final Projectile projectile;
        private final Location origin;
        /** 杖の固定速度。弓はバニラの物理演算に任せるため {@code null}。 */
        private final Vector velocity;
        private Location lastLocation;
        private boolean handled;

        private ProjectileAttack(Player owner, WeaponData weapon,
                                 Projectile projectile, Location origin, Vector velocity) {
            this.owner = owner;
            this.weapon = weapon;
            this.projectile = projectile;
            this.origin = origin;
            this.velocity = velocity;
            this.lastLocation = origin.clone();
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

        private Location origin() {
            return origin;
        }

        private Vector velocity() {
            return velocity;
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
     * 視線方向のローカル座標系。
     */
    private record ViewBasis(Vector forward, Vector right, Vector up) {
    }

    /**
     * 防御側の計算用ステータス。
     */
    private record CombatTarget(double defense1, double defense2, double evasion) {
    }
}
