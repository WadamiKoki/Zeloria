package com.zeloria.mob;

import com.zeloria.combat.CombatService;
import com.zeloria.core.MessageService;
import com.zeloria.core.Zeloria;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.party.Party;
import com.zeloria.party.PartyManager;
import com.zeloria.player.PlayerData;
import com.zeloria.skill.LevelService;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * モブ撃破時の経験値分配とドロップ付与を担うリスナー。
 * <p>
 * 経験値の対象者は次のとおり（すべてオンラインかつモブと同一レイヤー）。
 * <ul>
 *     <li>LA 取得者</li>
 *     <li>モブへ 1 度でもダメージを与えたプレイヤー</li>
 *     <li>LA 取得者がパーティに所属している場合の、そのパーティメンバー</li>
 * </ul>
 * 分配は「LA: 75% + (25% ÷ N)、その他: 25% ÷ N」とし、切り捨てた端数は LA へ寄せる。
 * ドロップは分配せず、LA 取得者の仮想インベントリへ 100% 付与する。
 * <p>
 * バニラの EXP・アイテムドロップは使用しない。レイヤーワールドでは
 * {@code doMobLoot=false} によりドロップ抽選を抑止し、{@link EntityDeathEvent} でも
 * EXP とドロップを空にして二重に保証する。
 */
public class MobRewardListener implements Listener {

    /** メッセージ定義ファイル（{@code MessageData/level.yml}）。 */
    private static final String MESSAGE_FILE = "level";

    /** モブを識別する PDC キー（{@code AdminCommand} と同一）。 */
    private static final String MOB_ID_KEY = "mob_id";

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;

    /** モブのエンティティ ID → ダメージを与えたプレイヤーの UUID。 */
    private final Int2ObjectMap<Set<UUID>> attackers = new Int2ObjectOpenHashMap<>();

    public MobRewardListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
    }

    /**
     * モブへダメージを与えたプレイヤーを記録する。
     * <p>
     * カスタム戦闘ではバニラのダメージイベントがキャンセルされるため、
     * {@code ignoreCancelled} を付けずに MONITOR で記録する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob)) {
            return;
        }
        recordAttacker(mob, CombatService.resolvePlayer(event.getDamager()));
    }

    /**
     * モブへダメージを与えたプレイヤーを記録する。
     * <p>
     * カスタムダメージは HP を直接減算するためバニラのダメージイベントが発生しない。
     * その場合は {@code CombatService} から本メソッドを呼び出し、
     * 経験値分配の対象者を記録する。
     *
     * @param mob      ダメージを受けたモブ
     * @param attacker ダメージを与えたプレイヤー（{@code null} は無視）
     */
    public void recordAttacker(LivingEntity mob, Player attacker) {
        if (mob == null || attacker == null) {
            return;
        }
        String mobId = mob.getPersistentDataContainer().get(mobIdKey, PersistentDataType.STRING);
        if (mobId == null || mobId.isBlank()) {
            return;
        }

        int entityId = mob.getEntityId();
        Set<UUID> ids = attackers.get(entityId);
        if (ids == null) {
            ids = new LinkedHashSet<>();
            attackers.put(entityId, ids);
        }
        ids.add(attacker.getUniqueId());
    }

    /**
     * モブ撃破時に経験値を分配し、LA 取得者へドロップを付与する。
     * <p>
     * バニラの EXP・ドロップはプラグイン側（{@link LevelService} /
     * {@link VirtualInventoryService}）が完全に管理するため、ここで必ず空にする。
     * キラー未確定（環境ダメージ等）の死亡にも適用するため、早期 return より前に
     * 実行する。ドロップ抽選自体はワールドの {@code doMobLoot=false} で抑止済みで、
     * 本処理は他プラグインによる追加分への保険となる。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        Set<UUID> recorded = attackers.remove(mob.getEntityId());

        // バニラの経験値オーブとアイテムドロップを排除する。
        event.setDroppedExp(0);
        event.getDrops().clear();

        Player killer = mob.getKiller();
        if (killer == null) {
            return;
        }

        String mobId = mob.getPersistentDataContainer().get(mobIdKey, PersistentDataType.STRING);
        if (mobId == null || mobId.isBlank()) {
            return;
        }

        MobConfigService mobConfig = plugin.getMobConfigService();
        Optional<MobData> found = mobConfig == null
                ? Optional.empty()
                : mobConfig.find(mobId);
        if (found.isEmpty()) {
            return;
        }

        MobData data = found.get();
        List<Player> recipients = resolveRecipients(killer, mob.getWorld(), recorded);
        grantExp(data.expReward(), killer, recipients);
        grantDrops(data, killer);
    }

    /**
     * チャンクアンロードでデスポーンするモブの攻撃者記録を破棄する。
     * <p>
     * モブは死亡時に {@link #onEntityDeath(EntityDeathEvent)} で記録を削除するが、
     * 死亡せずにアンロードされた場合はエンティティ ID キーが残り続けるため、
     * アンロード対象のエンティティから明示的に削除する。
     *
     * @param event エンティティアンロードイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        if (attackers.isEmpty()) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            attackers.remove(entity.getEntityId());
        }
    }

    /**
     * 経験値の分配対象者を解決する。
     * <p>
     * 重複は除外し、LA 取得者を必ず含める。ダメージ記録者のパーティメンバーは
     * 対象外とし、LA 取得者のパーティメンバーだけを追加する。
     */
    private List<Player> resolveRecipients(Player killer, World world, Set<UUID> recorded) {
        Set<Player> recipients = new LinkedHashSet<>();
        addIfEligible(recipients, killer, world);

        if (recorded != null) {
            for (UUID playerId : recorded) {
                addIfEligible(recipients, Bukkit.getPlayer(playerId), world);
            }
        }

        PartyManager partyManager = plugin.getPartyManager();
        if (partyManager != null) {
            Party party = partyManager.partyOf(killer.getUniqueId()).orElse(null);
            if (party != null) {
                for (UUID memberId : party.members()) {
                    addIfEligible(recipients, Bukkit.getPlayer(memberId), world);
                }
            }
        }

        return new ArrayList<>(recipients);
    }

    /**
     * オンラインかつモブと同一レイヤーにいる場合だけ対象者へ追加する。
     */
    private static void addIfEligible(Set<Player> recipients, Player player, World world) {
        if (player == null || !player.isOnline() || world == null) {
            return;
        }
        if (!world.equals(player.getWorld())) {
            return;
        }
        recipients.add(player);
    }

    /**
     * 経験値を分配する。
     * <p>
     * 1 人あたりの取り分は切り捨て、配りきれなかった端数は LA 取得者へ加算する。
     */
    private void grantExp(int totalExp, Player killer, List<Player> recipients) {
        if (totalExp <= 0 || recipients.isEmpty()) {
            return;
        }

        LevelService levelService = plugin.getLevelService();
        if (levelService == null) {
            return;
        }

        int count = recipients.size();
        long otherShare = (long) Math.floor(totalExp * 0.25D / count);
        long killerShare = totalExp - otherShare * (count - 1L);

        for (Player recipient : recipients) {
            long amount = recipient.getUniqueId().equals(killer.getUniqueId())
                    ? killerShare
                    : otherShare;
            if (amount <= 0L) {
                continue;
            }

            LevelService.LevelResult result = levelService.grant(recipient, amount);
            if (result == LevelService.LevelResult.NONE) {
                continue;
            }

            notifyGain(recipient, amount, result);
        }
    }


    /**
     * 経験値獲得・レベルアップを通知し、レベルアップ時は装備補正を再計算する。
     */
    private void notifyGain(Player player, long amount, LevelService.LevelResult result) {
        MessageService messages = plugin.getMessageService();
        if (messages == null) {
            return;
        }

        messages.actionBar(player, MESSAGE_FILE, "gain",
                Map.of(
                        "exp", String.valueOf(amount),
                        "level", String.valueOf(result.newLevel()),
                        "current", String.valueOf(result.currentExp()),
                        "required", String.valueOf(result.requiredExp())));

        if (!result.leveledUp()) {
            return;
        }

        messages.send(player, MESSAGE_FILE, "level_up",
                Map.of(
                        "old_level", String.valueOf(result.oldLevel()),
                        "new_level", String.valueOf(result.newLevel()),
                        "levels", String.valueOf(result.levelsGained())));

        if (plugin.getEquipmentService() != null) {
            plugin.getEquipmentService().recompute(player);
        }
    }

    /**
     * ドロップ抽選を行い、LA 取得者の仮想インベントリへ直接追加する。
     */
    private void grantDrops(MobData data, Player killer) {
        if (data.drops().isEmpty()) {
            return;
        }

        VirtualInventoryService service = plugin.getVirtualInventoryService();
        if (service == null || PlayerData.getCached(killer.getUniqueId()) == null) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (MobDrop drop : data.drops()) {
            if (drop.chance() <= 0.0D || random.nextDouble() >= drop.chance()) {
                continue;
            }
            service.addItem(killer, drop.itemId(), drop.amount());
        }
    }
}

