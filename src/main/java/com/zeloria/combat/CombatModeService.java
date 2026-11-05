package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.item.VirtualItem;
import com.zeloria.core.MessageService;
import com.zeloria.skill.SkillSlotService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 戦闘モード（ON / OFF）の状態をメモリ上で管理するサービス。
 * <p>
 * 状態はログインセッション内の一時状態として扱い、DB へは保存しない。
 * 仮想武器を手に持った状態で「スニーク + 下向き + 右クリック」すると切り替わる。
 * ON の間は {@link SkillSlotService} がホットバー slot 1〜5 にスキルを表示し、
 * OFF にすると退避していた実アイテムを空き枠へ戻したうえで、ホットバー slot 1〜5 を
 * 灰色ガラス板で埋める（実アイテムを裏で保持しない）。
 * <p>
 * 仮想武器を別スロットへ持ち替えた場合や死亡・退出時は自動的に OFF になる。
 */
public final class CombatModeService {

    /** メッセージ定義ファイル（{@code MessageData/combat.yml}）。 */
    private static final String MESSAGE_FILE = "combat";

    /** 戦闘モード ON を通知するメッセージキー。 */
    private static final String MESSAGE_ENABLED = "mode.enabled";

    private final Zeloria plugin;
    private final SkillSlotService skillSlotService;

    /** プレイヤー UUID → 戦闘モード状態。 */
    private final Map<UUID, Boolean> combatMode = new ConcurrentHashMap<>();

    public CombatModeService(Zeloria plugin, SkillSlotService skillSlotService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.skillSlotService = Objects.requireNonNull(skillSlotService, "skillSlotService");
    }

    /**
     * 戦闘モード中か判定する。
     *
     * @param player 対象プレイヤー
     * @return 戦闘モード中の場合は {@code true}
     */
    public boolean isActive(Player player) {
        return player != null && isActive(player.getUniqueId());
    }

    /**
     * 戦闘モード中か判定する。
     *
     * @param playerId プレイヤー UUID
     * @return 戦闘モード中の場合は {@code true}
     */
    public boolean isActive(UUID playerId) {
        return playerId != null && combatMode.getOrDefault(playerId, false);
    }

    /**
     * 戦闘モードへ切り替え可能な状態か判定する。
     * <p>
     * ホットバー slot 0（仮想武器）を選択している場合のみ許可する。
     *
     * @param player 対象プレイヤー
     * @return 切り替え可能な場合は {@code true}
     */
    public boolean canToggle(Player player) {
        if (player == null) {
            return false;
        }
        if (player.getInventory().getHeldItemSlot()
                != VirtualInventoryService.WEAPON_HOTBAR_SLOT) {
            return false;
        }
        return isVirtualWeapon(player.getInventory().getItemInMainHand());
    }

    /**
     * ホットバー slot 0 に仮想武器があるか判定する。
     *
     * @param player 対象プレイヤー
     * @return 仮想武器がある場合は {@code true}
     */
    public boolean hasVirtualWeaponInSlotZero(Player player) {
        if (player == null) {
            return false;
        }
        return isVirtualWeapon(player.getInventory()
                .getItem(VirtualInventoryService.WEAPON_HOTBAR_SLOT));
    }

    /**
     * アイテムが仮想武器（スキル表示ではない仮想アイテム）か判定する。
     *
     * @param item 判定対象
     * @return 仮想武器の場合は {@code true}
     */
    public static boolean isVirtualWeapon(ItemStack item) {
        return VirtualItem.isVirtualEquipment(item);
    }

    /**
     * 戦闘モードを切り替える。
     *
     * @param player 対象プレイヤー
     * @return 切り替え後の状態（ON の場合は {@code true}）
     */
    public boolean toggle(Player player) {
        if (player == null) {
            return false;
        }
        if (isActive(player)) {
            deactivate(player);
            return false;
        }
        if (!canToggle(player)) {
            return false;
        }
        activate(player);
        return true;
    }

    /**
     * 戦闘モードを ON にし、スキルスロットを表示する。
     *
     * @param player 対象プレイヤー
     */
    public void activate(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        combatMode.put(player.getUniqueId(), true);
        skillSlotService.show(player);
        sendEnabled(player);
    }

    /**
     * 戦闘モードを OFF にし、スキルスロットを元の実アイテムへ戻す。
     *
     * @param player 対象プレイヤー
     */
    public void deactivate(Player player) {
        if (player == null) {
            return;
        }
        combatMode.remove(player.getUniqueId());
        skillSlotService.hide(player);
        if (player.isOnline()) {
            player.sendActionBar(Component.empty());
        }
    }

    /**
     * 退出時の後始末を行う。
     * <p>
     * スキルスロットを元に戻してから状態を破棄する。退出後は
     * {@link VirtualInventoryService#clear(Player)} が仮想アイテムを除去する。
     *
     * @param player 対象プレイヤー
     */
    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }
        combatMode.remove(player.getUniqueId());
        skillSlotService.hide(player);
    }

    /**
     * 状態だけを破棄する（スキルスロットの復元は呼び出し側で行う）。
     *
     * @param playerId プレイヤー UUID
     */
    public void clearState(UUID playerId) {
        if (playerId != null) {
            combatMode.remove(playerId);
        }
    }

    /**
     * すべてのプレイヤーの戦闘モード状態を破棄する。
     */
    public void clearAll() {
        combatMode.clear();
    }

    private void sendEnabled(Player player) {
        MessageService messages = plugin.getMessageService();
        Component message = messages == null
                ? null
                : messages.component(MESSAGE_FILE, MESSAGE_ENABLED, Map.of()).orElse(null);
        player.sendActionBar(message == null
                ? Component.text("戦闘モード: ON", NamedTextColor.GREEN)
                : message);
    }
}
