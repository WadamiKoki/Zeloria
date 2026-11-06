package com.zeloria.item;

import com.zeloria.core.Zeloria;
import com.zeloria.item.ArmorData;
import com.zeloria.item.ItemData;
import com.zeloria.item.ItemService;
import com.zeloria.item.ItemStatBonus;
import com.zeloria.item.WeaponData;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStats;
import com.zeloria.player.PlayerStatsService;
import com.zeloria.player.ZeloriaClass;
import com.zeloria.skill.ClassManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * 仮想装備の補正値を計算し、{@link PlayerStats#applyBonuses(PlayerStats.Bonuses)} へ反映するサービス。
 * <p>
 * 装備の着脱は {@code /menu} の {@link com.zeloria.gui.MenuService} が担当し、
 * 本クラスは「現在の装備から補正値を再計算する」責務だけを持つ。
 * 装備変更・ログイン・レベルアップ時に {@link #recompute(Player)} を呼ぶことで、
 * 基礎値（DB 保存対象）を変更せずに補正値だけを差し替える。
 * <p>
 * YAML のステータスは GUI / Lore と同じ表示単位（% 表記）で保持されているため、
 * {@link PlayerStats.Bonuses} が要求する比率・実数値へここで変換する。
 */
public class EquipmentService {

    private final Zeloria plugin;
    private final ItemService itemService;

    public EquipmentService(Zeloria plugin, ItemService itemService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemService = Objects.requireNonNull(itemService, "itemService");
    }

    /* ------------------------------------------------------------------
     * 装着可否
     * ------------------------------------------------------------------ */

    /**
     * アイテムの必要レベルを取得する。
     *
     * @param stack 判定対象
     * @return 必要レベル（0 は制限なし）
     */
    public int requiredLevel(ItemStack stack) {
        // アイテム生成時に PDC へ保存した値を優先する。
        // 保存が無い旧アイテムはアイテム定義の値へフォールバックする。
        return itemService.getRequiredLevel(stack);
    }

    /**
     * プレイヤーのレベルがアイテムの必要レベルを満たすか判定する。
     *
     * @param player 対象プレイヤー
     * @param stack  判定対象
     * @return 装着可能な場合は {@code true}
     */
    public boolean meetsRequiredLevel(Player player, ItemStack stack) {
        if (player == null) {
            return false;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        int level = data == null ? 0 : data.getLevel();
        return level >= requiredLevel(stack);
    }

    /**
     * プレイヤーの現在のクラスが、指定アイテムを装着できるか判定する。
     * <p>
     * 武器の場合のみクラス定義の {@code weaponTypes} を照合する。クラス側が空リスト
     * （全武器許可）または武器側のタイプが空の場合は装着を許可する。武器以外の
     * アイテムや、クラス未選択・定義なしの場合は装着を妨げない。
     *
     * @param player 対象プレイヤー
     * @param stack  判定対象
     * @return 装着可能な場合は {@code true}
     */
    public boolean canEquip(Player player, ItemStack stack) {
        if (player == null) {
            return false;
        }

        ItemData data = resolve(stack);
        if (!(data instanceof WeaponData weapon)) {
            return true;
        }

        ClassManager classManager = plugin.getClassManager();
        if (classManager == null) {
            return true;
        }

        PlayerData playerData = PlayerData.getCached(player.getUniqueId());
        if (playerData == null) {
            return true;
        }

        ZeloriaClass currentClass = classManager.current(playerData.getClassData()).orElse(null);
        if (currentClass == null || currentClass.weaponTypes().isEmpty()) {
            return true;
        }

        List<String> weaponTypes = weapon.weaponTypes();
        if (weaponTypes.isEmpty()) {
            return true;
        }
        for (String weaponType : weaponTypes) {
            if (currentClass.allowsWeaponType(weaponType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * アイテムのステータス補正を取得する。
     *
     * @param stack 判定対象
     * @return ステータス補正。プラグイン製アイテムでない場合は {@link ItemStatBonus#NONE}
     */
    public ItemStatBonus statBonus(ItemStack stack) {
        ItemData data = resolve(stack);
        if (data instanceof ArmorData armor) {
            return armor.statBonus();
        }
        if (data instanceof WeaponData weapon) {
            return weapon.statBonus();
        }
        return ItemStatBonus.NONE;
    }

    /* ------------------------------------------------------------------
     * 補正値の再計算
     * ------------------------------------------------------------------ */

    /**
     * 現在の仮想装備から補正値を再計算し、ステータスへ適用する。
     * <p>
     * 必要レベルを満たさない装備は補正対象から除外する（装備自体は外さない）。
     * メインスレッドから呼び出すこと。
     *
     * @param player 対象プレイヤー
     */
    public void recompute(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return;
        }

        int level = data.getLevel();
        ItemStatBonus total = ItemStatBonus.NONE;
        for (EquipSlot slot : EquipSlot.values()) {
            ItemStack equipped = data.getEquipment(slot);
            if (equipped == null || equipped.isEmpty()) {
                continue;
            }
            ItemStatBonus bonus = statBonus(equipped);
            if (requiredLevel(equipped) > level) {
                continue;
            }
            total = total.plus(bonus);
        }

        data.getStats().applyBonuses(toBonuses(total));

        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.apply(player, data);
        }

        // 装備の着脱結果をインベントリの仮想表示（ホットバー slot 0・防具 4 枠）へ反映する。
        VirtualInventoryService virtualInventory = plugin.getVirtualInventoryService();
        if (virtualInventory != null) {
            virtualInventory.refresh(player);
        }
    }

    /**
     * YAML の表示単位（%）を {@link PlayerStats.Bonuses} の単位へ変換する。
     *
     * @param bonus YAML 上の補正値
     * @return 変換後の補正値
     */
    public static PlayerStats.Bonuses toBonuses(ItemStatBonus bonus) {
        ItemStatBonus value = bonus == null ? ItemStatBonus.NONE : bonus;
        return new PlayerStats.Bonuses(
                value.maxHealth(),
                value.maxMana(),
                value.attackPower(),
                value.defense1(),
                value.defense2(),
                value.criticalRate(),
                value.criticalDamage(),
                value.accuracy(),
                value.evasion(),
                value.dodgeCooldownReduction() / 100.0,
                value.cooldownReduction() / 100.0,
                PlayerStats.DEFAULT_MOVE_SPEED * (value.moveSpeed() / 100.0),
                value.healthRegenFlat(),
                value.healthRegenPercent() / 100.0,
                value.manaRegenFlat(),
                value.manaRegenPercent() / 100.0,
                value.armorPenetration() / 100.0,
                value.lifesteal() / 100.0);
    }

    private ItemData resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return itemService.findItem(stack).orElse(null);
    }
}
