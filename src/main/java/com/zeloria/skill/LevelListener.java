package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.core.MessageService;
import com.zeloria.mob.MobConfigService;
import com.zeloria.mob.MobData;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * モブ撃破時に経験値を付与するリスナー。
 * <p>
 * 対象は {@code /admin mob spawn} が {@code zeloria:mob_id} を付与してスポーンした
 * {@link MobData} 由来のモブのみ。バニラモブや他プラグインのモブには干渉しない。
 */
public class LevelListener implements Listener {

    /** メッセージ定義ファイル（{@code MessageData/level.yml}）。 */
    private static final String MESSAGE_FILE = "level";

    /** モブを識別する PDC キー（{@code AdminCommand} と同一）。 */
    private static final String MOB_ID_KEY = "mob_id";

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;

    public LevelListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
    }

    /**
     * モブ撃破時に {@link MobData#expReward()} を撃破者へ付与する。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        String mobId = event.getEntity().getPersistentDataContainer()
                .get(mobIdKey, PersistentDataType.STRING);
        if (mobId == null || mobId.isBlank()) {
            return;
        }

        MobConfigService mobConfig = plugin.getMobConfigService();
        Optional<MobData> found = mobConfig == null
                ? Optional.empty()
                : mobConfig.find(mobId);
        if (found.isEmpty() || found.get().expReward() <= 0) {
            return;
        }

        LevelService levelService = plugin.getLevelService();
        if (levelService == null) {
            return;
        }

        LevelService.LevelResult result = levelService.grant(killer, found.get().expReward());
        if (result == LevelService.LevelResult.NONE) {
            return;
        }

        MessageService messages = plugin.getMessageService();
        if (messages == null) {
            return;
        }

        messages.actionBar(killer, MESSAGE_FILE, "gain",
                java.util.Map.of(
                        "exp", String.valueOf(found.get().expReward()),
                        "level", String.valueOf(result.newLevel()),
                        "current", String.valueOf(result.currentExp()),
                        "required", String.valueOf(result.requiredExp())));

        if (result.leveledUp()) {
            messages.send(killer, MESSAGE_FILE, "level_up",
                    java.util.Map.of(
                            "old_level", String.valueOf(result.oldLevel()),
                            "new_level", String.valueOf(result.newLevel()),
                            "levels", String.valueOf(result.levelsGained())));

            // 必要レベルを満たした装備を反映する。
            if (plugin.getEquipmentService() != null) {
                plugin.getEquipmentService().recompute(killer);
            }
        }
    }
}
