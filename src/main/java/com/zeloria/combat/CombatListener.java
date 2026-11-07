package com.zeloria.combat;

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import com.zeloria.core.Zeloria;
import com.zeloria.item.ItemService;
import com.zeloria.item.VirtualItem;
import com.zeloria.item.WeaponData;
import com.zeloria.mob.MobStatsService;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.SkillCastNotifier;
import com.zeloria.skill.SkillCastResult;
import com.zeloria.skill.SkillCastService;
import com.zeloria.skill.SkillManager;
import com.zeloria.player.PlayerClassData;
import com.zeloria.skill.Skill;
import com.zeloria.player.ZeloriaClass;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * スキル発動とカスタムダメージ処理を一元管理する戦闘リスナー。
 * <p>
 * スキルは PDC の {@code skill_id} を持つアイテムの右クリック、または
 * {@code SkillSlotListener} が扱うホットバースロットから発動する。発動判定・
 * コスト消費・効果適用は {@link SkillCastService} に委譲する。
 * <p>
 * ダメージはバニラ処理を {@link EventPriority#LOWEST} でキャンセルし、
 * {@link CombatService} の計算結果のみを適用する。対象はプレイヤーと
 * {@code MobStats} を持つカスタムモブに限定し、バニラモブや他プラグインの
 * モブには一切干渉しない。
 */
public final class CombatListener implements Listener {

    /**
     * プレイヤーに対して無効化するバニラ環境ダメージ。
     * <p>
     * 戦闘は {@link CombatService} のカスタム計算へ一本化するため、落下・炎・
     * 溶岩・溺れ・毒などはバニラ処理をキャンセルする。エンティティからの攻撃は
     * ここには含めず、従来どおりカスタム計算へ渡す。
     * <p>
     * マグマブロック・焚き火のダメージは {@link DamageCause#CONTACT} として通知されるため
     * 個別の定数は指定しない。ドラゴンブレスは {@link DamageCause#ENTITY_ATTACK} として
     * 通知され、エンティティ攻撃としてカスタム計算へ渡す。
     */
    private static final Set<DamageCause> DISABLED_ENVIRONMENT_DAMAGE = EnumSet.of(
            DamageCause.CONTACT,
            DamageCause.SUFFOCATION,
            DamageCause.FALL,
            DamageCause.FIRE,
            DamageCause.FIRE_TICK,
            DamageCause.MELTING,
            DamageCause.LAVA,
            DamageCause.DROWNING,
            DamageCause.LIGHTNING,
            DamageCause.STARVATION,
            DamageCause.POISON,
            DamageCause.MAGIC,
            DamageCause.WITHER,
            DamageCause.FALLING_BLOCK,
            DamageCause.FLY_INTO_WALL,
            DamageCause.CRAMMING,
            DamageCause.DRYOUT,
            DamageCause.FREEZE,
            DamageCause.WORLD_BORDER,
            DamageCause.SONIC_BOOM
    );

    /**
     * カスタムMOBに対してキャンセルする環境ダメージ。
     * <p>
     * ゾンビ系などのカスタムMOBが昼間の日光・溶岩・焚き火・マグマブロックで
     * 燃えて勝手に死ぬのを防ぐ。エンティティからの攻撃（{@code ENTITY_ATTACK} /
     * {@code PROJECTILE} など）は含めないため、プレイヤーや他MOBからの攻撃は
     * 従来どおり {@link CombatService} のカスタム計算へ渡る。
     * <p>
     * マグマブロック・焚き火・足元の熱は {@link DamageCause#CONTACT} として通知される。
     */
    private static final Set<DamageCause> CUSTOM_MOB_IMMUNE_DAMAGE = EnumSet.of(
            DamageCause.FIRE,
            DamageCause.FIRE_TICK,
            DamageCause.LAVA,
            DamageCause.CONTACT,
            DamageCause.MELTING,
            DamageCause.LIGHTNING
    );

    /**
     * バニラダメージイベント経由のスキルダメージを示す PDC キー。
     * <p>
     * カスタムMOBの環境ダメージ無効化と区別するためのワンショットフラグ。
     * スキルが {@link LivingEntity#damage(double)} を使う場合、呼び出し直前に
     * {@link #markSkillDamage(LivingEntity)} を実行すると、そのダメージは
     * 環境ダメージ判定を素通りする（判定時に自動で解除される）。
     * <p>
     * なお、現在のスキルは {@link CombatService} が HP を直接減算するため
     * このイベントを経由せず、フラグ無しでもダメージは通る。
     */
    public static final String SKILL_DAMAGE_KEY = "skill_damage";

    private final Zeloria plugin;
    private final NamespacedKey skillDamageKey;

    public CombatListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.skillDamageKey = new NamespacedKey(plugin, SKILL_DAMAGE_KEY);
    }

    /**
     * バニラダメージイベント経由で与えるスキルダメージを、環境ダメージ無効化の
     * 対象外にするためのフラグを立てる。
     * <p>
     * フラグは次のダメージ判定で 1 回だけ消費される。
     *
     * @param entity 対象エンティティ
     */
    public void markSkillDamage(LivingEntity entity) {
        if (entity != null) {
            entity.getPersistentDataContainer().set(skillDamageKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /**
     * スキルダメージ用フラグを解除する。
     *
     * @param entity 対象エンティティ
     */
    public void clearSkillDamage(LivingEntity entity) {
        if (entity != null) {
            entity.getPersistentDataContainer().remove(skillDamageKey);
        }
    }

    /**
     * 左クリック（腕振り）による通常攻撃を処理する。
     * <p>
     * 戦闘モード中かつメインハンドに武器（またはスキルパレット）を持っている場合のみ
     * 発動する。実際の攻撃判定・ダメージ計算・クールダウンは
     * {@link CombatService#performNormalAttack(Player, WeaponData)} に委譲する。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerArmSwing(PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        CombatModeService modeService = plugin.getCombatModeService();
        if (modeService == null || !modeService.isActive(player)) {
            return;
        }

        WeaponData weapon = resolveHeldWeapon(player);
        if (weapon == null) {
            return;
        }

        CombatService combatService = plugin.getCombatService();
        if (combatService != null) {
            combatService.performNormalAttack(player, weapon);
        }
    }

    /**
     * 右クリックによるスキル発動を処理する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 弓は左クリックのみで発射する。右クリックでの射撃・スキル発動を封印する。
        Player interactPlayer = event.getPlayer();
        ItemStack interacted = event.getItem();
        if (isBow(interactPlayer, interacted)) {
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            item = player.getInventory().getItemInMainHand();
        }
        if (item.getType().isAir()) {
            return;
        }
        // 戦闘モードのスキルアイコンは SkillSlotListener が処理する。
        // ここでも PDC を読むと 1 回の右クリックで二重発動する可能性があるため除外する。
        if (VirtualItem.isSkillSlot(item)) {
            return;
        }

        ClassManager classManager = plugin.getClassManager();
        SkillManager skillManager = plugin.getSkillManager();
        SkillCastService castService = plugin.getSkillCastService();
        SkillCastNotifier notifier = plugin.getSkillCastNotifier();
        if (classManager == null || skillManager == null
                || castService == null || notifier == null) {
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return;
        }

        PlayerClassData classData = data.getClassData();
        ZeloriaClass definition = classManager.find(classData.currentClassIdOrNull()).orElse(null);
        if (definition == null) {
            return;
        }

        Optional<String> storedSkillId = skillManager.readSkillId(item);
        if (storedSkillId.isEmpty()) {
            // 武器（Material）によるスキル判定は廃止したため、PDC が無ければ何もしない。
            return;
        }

        Skill skill = skillManager.find(storedSkillId.get()).orElse(null);
        if (skill == null) {
            notifier.notify(player, new SkillCastResult.UnknownSkill(storedSkillId.get()));
            return;
        }
        if (!definition.allows(skill.id())) {
            notifier.notify(player, new SkillCastResult.NotOwned(skill));
            return;
        }

        // スキルと判定できた操作のみキャンセルし、ブロック設置・着火などを防ぐ。
        event.setCancelled(true);
        notifier.notify(player, castService.cast(player, skill));
    }

    /**
     * 弓のバニラ射撃を禁止する（通常攻撃は左クリックで処理する）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (isBow(player, event.getBow())) {
            event.setCancelled(true);
        }
    }

    /**
     * 通常攻撃の投射物が着弾したときの処理を {@link CombatService} へ委譲する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onProjectileHit(ProjectileHitEvent event) {
        CombatService combatService = plugin.getCombatService();
        if (combatService != null) {
            combatService.handleProjectileHit(event);
        }
    }

    /**
     * すべてのダメージをカスタム計算へ置き換える。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        // 落下ダメージはプラグイン側 HP 管理にもバニラ死亡にも繋げない。
        // 最大 HP を超える落下ダメージで死亡しないよう、常に完全キャンセルする。
        if (event.getEntity() instanceof Player && event.getCause() == DamageCause.FALL) {
            event.setCancelled(true);
            return;
        }

        // 真の HP は PlayerStats#currentHealth のため、プレイヤーへのバニラダメージは
        // すべてキャンセルする。以降のカスタム計算のみが HP を減少させる。
        if (event.getEntity() instanceof Player playerVictim) {
            if (isPendingDeath(playerVictim)) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
        }

        // 通常攻撃の投射物は ProjectileHitEvent 側でカスタムダメージを適用するため、
        // バニラの投射物ダメージは常にキャンセルして二重ヒットを防ぐ。
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Projectile projectile) {
            CombatService combatService = plugin.getCombatService();
            if (combatService != null && combatService.isCustomProjectile(projectile)) {
                event.setCancelled(true);
                return;
            }
        }

        // プレイヤーのバニラ環境ダメージは無効化する（落下・炎・溶岩・溺れなど）。
        if (event.getEntity() instanceof Player
                && DISABLED_ENVIRONMENT_DAMAGE.contains(event.getCause())) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        // カスタムMOBは燃焼・溶岩・焚き火などの環境ダメージを受けない。
        // スキル／プレイヤー攻撃由来のダメージは対象外として通す。
        if (cancelCustomMobEnvironmentDamage(event, victim)) {
            return;
        }

        CombatService combatService = plugin.getCombatService();
        if (combatService == null) {
            return;
        }

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (handleEntityDamage(event, combatService, victim, byEntity.getDamager())) {
                return;
            }
        }

        // 落下・炎・毒などの環境ダメージ。
        if (combatService.environmentalDamage(victim, event.getFinalDamage()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * カスタムMOBの環境ダメージ（燃焼・溶岩・焚き火など）をキャンセルする。
     * <p>
     * 対象は {@link MobStatsService#get(Entity)} が {@code null} を返さない
     * カスタムMOBのみ。バニラMOBや他プラグインのMOBには干渉しない。
     * プレイヤーのスキル／攻撃由来のダメージは {@link #isSkillDamage} で除外する。
     *
     * @param event  ダメージイベント
     * @param victim ダメージを受けたエンティティ
     * @return キャンセルした場合は {@code true}
     */
    private boolean cancelCustomMobEnvironmentDamage(EntityDamageEvent event, LivingEntity victim) {
        if (victim instanceof Player || !CUSTOM_MOB_IMMUNE_DAMAGE.contains(event.getCause())) {
            return false;
        }

        MobStatsService mobStatsService = plugin.getMobStatsService();
        if (mobStatsService == null || mobStatsService.get(victim) == null) {
            return false;
        }

        // スキル／プレイヤー攻撃由来のダメージは通す。
        if (isSkillDamage(event, victim)) {
            return false;
        }

        event.setCancelled(true);

        // 着火状態が残ると環境ダメージ判定が毎ティック続くため、燃焼系は消火する。
        if (victim.getFireTicks() > 0) {
            victim.setFireTicks(0);
        }
        return true;
    }

    /**
     * 環境ダメージではなくスキル／プレイヤー攻撃由来のダメージか判定する。
     * <p>
     * {@link #SKILL_DAMAGE_KEY} の PDC フラグはワンショットで、判定時に解除する。
     * プレイヤー（またはプレイヤーの投射物）をダメージ源とするイベントも
     * スキル相当として扱う。
     */
    private boolean isSkillDamage(EntityDamageEvent event, LivingEntity victim) {
        if (victim.getPersistentDataContainer().has(skillDamageKey, PersistentDataType.BYTE)) {
            victim.getPersistentDataContainer().remove(skillDamageKey);
            return true;
        }

        return event instanceof EntityDamageByEntityEvent byEntity
                && CombatService.resolvePlayer(byEntity.getDamager()) != null;
    }

    /**
     * エンティティからのダメージを処理する。
     *
     * @return カスタム処理を行った場合は {@code true}
     */
    private boolean handleEntityDamage(
            EntityDamageEvent event,
            CombatService combatService,
            LivingEntity victim,
            Entity damager) {
        Player playerAttacker = CombatService.resolvePlayer(damager);
        if (playerAttacker != null) {
            // バニラの近接攻撃は常にキャンセルし、通常攻撃は PlayerArmSwingEvent に
            // よるプラグイン側のダメージ計算へ一本化する。
            if (event.getCause() == DamageCause.ENTITY_ATTACK) {
                event.setCancelled(true);
                return true;
            }

            DamageResult result = combatService.playerAttack(
                    playerAttacker, victim, 1.0, 0.0, 0.0);
            if (result != null) {
                event.setCancelled(true);
                return true;
            }
        }

        LivingEntity attacker = CombatService.resolveAttacker(damager);
        if (attacker != null && victim instanceof Player playerVictim) {
            DamageResult result = combatService.mobAttack(attacker, playerVictim);
            if (result != null) {
                event.setCancelled(true);
                return true;
            }
        }

        // バニラモブ・他プラグインの攻撃は相手がカスタム対象なら軽減して適用する。
        if (combatService.environmentalDamage(victim, event.getFinalDamage()) != null) {
            event.setCancelled(true);
            return true;
        }
        return false;
    }

    /**
     * バニラのノックバックを無効化する。
     * <p>
     * Zeloria のカスタム戦闘ではノックバックを付与しないため、プレイヤーおよび
     * カスタムモブが受けるバニラ由来のノックバックを打ち消す。
     * <p>
     * 本イベントはエンティティの攻撃によるノックバックのみを検知する。爆発やピストンなど
     * エンティティ以外が原因のノックバックは対象外となる。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityKnockbackByEntity(EntityKnockbackByEntityEvent event) {
        LivingEntity victim = event.getEntity();

        if (victim instanceof Player) {
            event.setCancelled(true);
            return;
        }

        if (plugin.getMobStatsService() != null
                && plugin.getMobStatsService().get(victim) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * メインハンドまたは装備中のメイン武器から武器定義を解決する。
     * <p>
     * 戦闘モード中はホットバー slot 1〜5 がスキルパレット表示になるため、
     * その場合は実際に装備しているメイン武器を参照する。
     *
     * @param player 対象プレイヤー
     * @return 武器定義。武器を持っていない場合は {@code null}
     */
    private WeaponData resolveHeldWeapon(Player player) {
        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            return null;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (VirtualItem.isSkillSlot(mainHand)) {
            PlayerData data = PlayerData.getCached(player.getUniqueId());
            if (data == null) {
                return null;
            }
            return asWeapon(itemService, data.getEquipment(EquipSlot.MAIN_WEAPON));
        }
        return asWeapon(itemService, mainHand);
    }

    /**
     * {@link ItemStack} を {@link WeaponData} へ解決する。
     */
    private static WeaponData asWeapon(ItemService itemService, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return itemService.findItem(stack)
                .filter(WeaponData.class::isInstance)
                .map(WeaponData.class::cast)
                .orElse(null);
    }

    /**
     * 弓（バニラの BOW、または武器種 BOW のカスタム武器）か判定する。
     */
    private boolean isBow(Player player, ItemStack item) {
        ItemStack stack = item == null || item.getType().isAir()
                ? player.getInventory().getItemInMainHand()
                : item;
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getType() == Material.BOW) {
            return true;
        }

        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            return false;
        }
        return itemService.findItem(stack)
                .filter(WeaponData.class::isInstance)
                .map(WeaponData.class::cast)
                .map(weapon -> weapon.weaponTypes().contains("BOW"))
                .orElse(false);
    }

    private boolean isPendingDeath(Player player) {
        return plugin.getDeathListener() != null
                && plugin.getDeathListener().isAwaiting(player);
    }
}
