package com.zeloria.item;

import com.zeloria.core.Zeloria;
import com.zeloria.core.MessageService;
import com.zeloria.item.VirtualItem;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import com.zeloria.combat.CombatModeService;
import com.zeloria.skill.SkillSlotService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 装備中の武器・防具をプレイヤーのインベントリへ「見た目だけ」表示するサービス。
 * <p>
 * 表示先は次の 5 スロットに限定する。
 * <ul>
 *     <li>ホットバー slot 0: メイン武器の仮想スロット（プレイヤー操作不可）</li>
 *     <li>バニラ防具 4 枠: 装備中の防具の仮想スロット（プレイヤー操作不可）</li>
 * </ul>
 * ホットバー slot 1〜8 は装備表示の対象外。slot 1〜5 は戦闘モードのスキル枠で、
 * OFF 時は {@link com.zeloria.skill.SkillSlotService#showIdle(Player)} が
 * 灰色ガラス板を配置する。
 * <p>
 * 表示用アイテムは {@link VirtualItem#mark(ItemStack)} で複製したうえで PDC を付与し、
 * {@link PlayerData} には保存しない。ログイン時や装備変更時は必ず {@link #refresh(Player)}
 * を呼び、DB の装備データからその都度生成する。
 */
public class VirtualInventoryService {

    /** メイン武器を表示するホットバーのスロット番号。 */
    public static final int WEAPON_HOTBAR_SLOT = 0;

    /** ホットバー + メインインベントリのスロット数（0〜35）。 */
    private static final int STORAGE_SLOT_COUNT = 36;

    /** バニラ防具スロット（{@link PlayerInventory} のインデックス）。 */
    public static final int BOOTS_SLOT = 36;
    public static final int LEGGINGS_SLOT = 37;
    public static final int CHESTPLATE_SLOT = 38;
    public static final int HELMET_SLOT = 39;

    /** オフハンドスロット（{@link PlayerInventory} のインデックス）。 */
    public static final int OFFHAND_SLOT = 40;

    /** 空きスロット表示の共通文言（斜体は無効化する）。 */
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String EMPTY_SLOT_NAME = "<!italic><gray>空きスロット</gray>";
    private static final String EMPTY_SLOT_LORE =
            "<!italic><dark_gray>装備するとここに表示されます</dark_gray>";

    /** メインハンドが空のときに表示するバリア。 */
    private static final ItemStack EMPTY_WEAPON_DISPLAY = createPlaceholder(Material.BARRIER);

    /** 防具スロットが空のときに表示する木のボタン。 */
    private static final ItemStack EMPTY_ARMOR_DISPLAY = createPlaceholder(Material.OAK_BUTTON);

    /**
     * 空きスロット表示用のアイテムを生成する。
     * <p>
     * PDC マーカーを付与して仮想アイテムとして扱い、プレイヤーの実アイテムと
     * 区別できるようにする。
     */
    private static ItemStack createPlaceholder(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MINI.deserialize(EMPTY_SLOT_NAME));
            meta.lore(List.of(MINI.deserialize(EMPTY_SLOT_LORE)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return VirtualItem.markPlaceholder(item);
    }

    /** メッセージ定義ファイル（{@code MessageData/equipment.yml}）。 */
    private static final String MESSAGE_FILE = "equipment";

    /** 退避アイテムが入りきらず足元へドロップした際の通知キー。 */
    private static final String MESSAGE_OVERFLOW = "overflow_dropped";

    private final Zeloria plugin;

    public VirtualInventoryService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /* ------------------------------------------------------------------
     * 表示の更新
     * ------------------------------------------------------------------ */

    /**
     * 現在の仮想装備データをプレイヤーのインベントリへ反映する。
     * <p>
     * メインスレッド以外から呼ばれた場合は、次のティックでメインスレッドへ委譲する。
     *
     * @param player 対象プレイヤー
     */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> refresh(player));
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return;
        }
        apply(player, data);
    }

    /**
     * 装備データの内容をインベントリへ反映する（メインスレッド専用）。
     *
     * @param player 対象プレイヤー
     * @param data   プレイヤーデータ
     */
    private void apply(Player player, PlayerData data) {
        PlayerInventory inventory = player.getInventory();

        // 過去のログインやクラッシュで残った表示用アイテムを掃除する。
        // ロック枠はこの後の比較で必要な分だけ差し替えるため、対象外とする。
        removeVirtualItems(player, false);

        // 何らかの経路でカーソル上に残った仮想アイテムも取り除く。
        if (VirtualItem.isVirtual(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }

        // ロック枠に残っている実アイテム（旧データや外部プラグイン由来）は、
        // 上書きして失わないよう退避しておく。
        List<ItemStack> displaced = new ArrayList<>();
        collectDisplaced(inventory.getItem(WEAPON_HOTBAR_SLOT), displaced);
        collectDisplaced(inventory.getHelmet(), displaced);
        collectDisplaced(inventory.getChestplate(), displaced);
        collectDisplaced(inventory.getLeggings(), displaced);
        collectDisplaced(inventory.getBoots(), displaced);

        applySlot(inventory, WEAPON_HOTBAR_SLOT, data.getEquipment(EquipSlot.MAIN_WEAPON));
        applyArmor(inventory, EquipSlot.HELMET, data.getEquipment(EquipSlot.HELMET));
        applyArmor(inventory, EquipSlot.CHESTPLATE, data.getEquipment(EquipSlot.CHESTPLATE));
        applyArmor(inventory, EquipSlot.LEGGINGS, data.getEquipment(EquipSlot.LEGGINGS));
        applyArmor(inventory, EquipSlot.BOOTS, data.getEquipment(EquipSlot.BOOTS));

        // 仮想アイテムを配置した後に退避アイテムを戻すことで、
        // 空き枠探索がロック枠（特に slot 0）へ吸い込まれるのを防ぐ。
        for (ItemStack item : displaced) {
            returnToInventory(player, inventory, item);
        }

        // 戦闘モード OFF 時はホットバー slot 1〜5 を灰色ガラスで埋める。
        // 戦闘モード中はスキルアイコンを維持するため、呼び出し側で判定する。
        SkillSlotService skillSlotService = plugin.getSkillSlotService();
        CombatModeService combatModeService = plugin.getCombatModeService();
        if (skillSlotService != null
                && (combatModeService == null || !combatModeService.isActive(player))) {
            skillSlotService.showIdle(player);
        }
    }

    /**
     * 指定スロットへ仮想表示アイテムを配置する。
     * <p>
     * 武器が未装備の場合はバリア（空きスロット表示）を表示し、プレイヤー操作を防ぐ。
     */
    private void applySlot(PlayerInventory inventory, int slot, ItemStack equipped) {
        ItemStack current = inventory.getItem(slot);
        ItemStack display = displayOrEmpty(equipped, EMPTY_WEAPON_DISPLAY);
        if (matchesDisplay(current, display)) {
            return;
        }
        inventory.setItem(slot, display);
    }

    /**
     * 防具スロットへ仮想表示アイテムを配置する。
     * <p>
     * 防具が未装備の場合は木のボタン（空きスロット表示）を表示し、プレイヤー操作を防ぐ。
     */
    private void applyArmor(PlayerInventory inventory, EquipSlot slot, ItemStack equipped) {
        ItemStack current = armorItem(inventory, slot);
        ItemStack display = displayOrEmpty(equipped, EMPTY_ARMOR_DISPLAY);
        if (matchesDisplay(current, display)) {
            return;
        }
        setArmorItem(inventory, slot, display);
    }

    /**
     * 装備アイテムの仮想表示を生成する。未装備の場合は空きスロット表示を返す。
     */
    private static ItemStack displayOrEmpty(ItemStack equipped, ItemStack emptyDisplay) {
        if (equipped == null || equipped.isEmpty()) {
            return emptyDisplay == null ? null : emptyDisplay.clone();
        }
        return VirtualItem.mark(equipped);
    }

    /**
     * 既に同じ仮想表示が配置されているか判定する。
     * <p>
     * 実アイテムが入っている場合は、たとえ見た目が同じでも {@code false} を返し、
     * 仮想表示へ置き換える（実アイテムは退避済み）。
     */
    private static boolean matchesDisplay(ItemStack current, ItemStack display) {
        if (display == null) {
            return current == null || current.getType().isAir();
        }
        if (current == null || current.getType().isAir()) {
            return false;
        }
        return VirtualItem.isVirtual(current) && current.isSimilar(display);
    }

    private static void collectDisplaced(ItemStack current, List<ItemStack> displaced) {
        if (current == null || current.getType().isAir() || VirtualItem.isVirtual(current)) {
            return;
        }
        displaced.add(current.clone());
    }

    /**
     * 退避した実アイテムをロック枠以外へ戻す。
     * <p>
     * まず既存の同種スタックへまとめ、次に空きスロット、最後にオフハンドへ移す。
     * それでも入りきらない場合は、テレポート完了後にプレイヤーの足元へドロップする
     * （ログイン直後のテレポートでアイテムが置き去りになるのを防ぐ）。
     */
    private void returnToInventory(Player player, PlayerInventory inventory, ItemStack item) {
        ItemStack remaining = item.clone();
        remaining = mergeIntoRange(inventory, remaining, 9, STORAGE_SLOT_COUNT - 1);
        remaining = mergeIntoRange(inventory, remaining, 6, 8);
        remaining = mergeIntoRange(inventory, remaining, 1, 5);
        if (remaining == null) {
            return;
        }

        int slot = firstEmptyUnlockedSlot(inventory);
        if (slot >= 0) {
            inventory.setItem(slot, remaining);
            return;
        }

        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand == null || offhand.getType().isAir()) {
            inventory.setItemInOffHand(remaining);
            return;
        }

        ItemStack dropped = remaining;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                MessageService messages = plugin.getMessageService();
                if (messages != null) {
                    messages.actionBar(player, MESSAGE_FILE, MESSAGE_OVERFLOW, Map.of());
                }
            }
        });
    }

    /**
     * 指定範囲の同種スタックへアイテムをまとめる。
     *
     * @return まとめきれなかった残り。すべて収まった場合は {@code null}
     */
    private static ItemStack mergeIntoRange(PlayerInventory inventory, ItemStack item,
                                            int start, int end) {
        ItemStack remaining = item;
        for (int slot = start; slot <= end; slot++) {
            if (isLockedSlot(slot)) {
                continue;
            }
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()
                    || VirtualItem.isVirtual(existing)
                    || !existing.isSimilar(remaining)) {
                continue;
            }
            int max = Math.min(existing.getMaxStackSize(), remaining.getMaxStackSize());
            int space = max - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            inventory.setItem(slot, existing);
            remaining.setAmount(remaining.getAmount() - moved);
            if (remaining.getAmount() <= 0) {
                return null;
            }
        }
        return remaining;
    }

    /**
     * ロック枠を除いた最初の空きスロットを取得する。
     * <p>
     * スキル枠として予定されているホットバー slot 1〜5 を避けるため、
     * メインインベントリ（9〜35）→ ホットバー 6〜8 → ホットバー 1〜5 の順で探す。
     * ホットバー slot 0 と防具・オフハンドは対象外。
     *
     * @return 空きスロット。空きが無い場合は {@code -1}
     */
    public static int firstEmptyUnlockedSlot(PlayerInventory inventory) {
        int slot = firstEmptyInRange(inventory, 9, STORAGE_SLOT_COUNT - 1);
        if (slot >= 0) {
            return slot;
        }
        slot = firstEmptyInRange(inventory, 6, 8);
        if (slot >= 0) {
            return slot;
        }
        return firstEmptyInRange(inventory, 1, 5);
    }

    private static int firstEmptyInRange(PlayerInventory inventory, int start, int end) {
        for (int slot = start; slot <= end; slot++) {
            if (isLockedSlot(slot)) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    /* ------------------------------------------------------------------
     * 表示のクリア
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーのインベントリから仮想表示アイテムだけを除去する。
     * <p>
     * 退出時・死亡時に呼び出し、仮想アイテムがバニラのプレイヤーデータや
     * ドロップへ混入しないようにする。実アイテムには触れない。
     *
     * @param player 対象プレイヤー
     */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        removeVirtualItems(player, true);
        if (VirtualItem.isVirtual(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    /**
     * インベントリ内の仮想表示アイテムを除去する。
     * <p>
     * PDC マーカーのあるアイテムだけを対象とするため、プレイヤーの実アイテムには影響しない。
     * 戦闘モード中はスキルスロット（ホットバー slot 1〜5）の表示を維持する。
     *
     * @param player        対象プレイヤー
     * @param includeLocked ロック枠（ホットバー slot 0・防具 4 枠）も除去する場合 {@code true}
     */
    private void removeVirtualItems(Player player, boolean includeLocked) {
        PlayerInventory inventory = player.getInventory();
        CombatModeService combatModeService = plugin.getCombatModeService();
        boolean preserveSkillSlots = !includeLocked
                && combatModeService != null
                && combatModeService.isActive(player.getUniqueId());

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!includeLocked && isLockedSlot(slot)) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (!includeLocked && preserveSkillSlots && VirtualItem.isSkillSlot(item)) {
                continue;
            }
            // 空きスロット表示（バリア・ガラス）は常設の見た目なので維持する。
            if (!includeLocked && VirtualItem.isPlaceholder(item)) {
                continue;
            }
            if (VirtualItem.isVirtual(item)) {
                inventory.setItem(slot, null);
            }
        }
    }

    /* ------------------------------------------------------------------
     * スロット判定ヘルパ
     * ------------------------------------------------------------------ */

    /**
     * プレイヤー操作を禁止するスロットか判定する。
     * <p>
     * 対象はホットバー slot 0 とバニラ防具 4 枠のみ。
     * ホットバー slot 1〜8 は対象外。
     *
     * @param slot {@link PlayerInventory} のスロット番号
     * @return ロック対象の場合は {@code true}
     */
    public static boolean isLockedSlot(int slot) {
        return slot == WEAPON_HOTBAR_SLOT
                || slot == BOOTS_SLOT
                || slot == LEGGINGS_SLOT
                || slot == CHESTPLATE_SLOT
                || slot == HELMET_SLOT;
    }

    private static ItemStack armorItem(PlayerInventory inventory, EquipSlot slot) {
        return switch (slot) {
            case HELMET -> inventory.getHelmet();
            case CHESTPLATE -> inventory.getChestplate();
            case LEGGINGS -> inventory.getLeggings();
            case BOOTS -> inventory.getBoots();
            default -> null;
        };
    }

    private static void setArmorItem(PlayerInventory inventory, EquipSlot slot, ItemStack item) {
        switch (slot) {
            case HELMET -> inventory.setHelmet(item);
            case CHESTPLATE -> inventory.setChestplate(item);
            case LEGGINGS -> inventory.setLeggings(item);
            case BOOTS -> inventory.setBoots(item);
            default -> {
                // 武器・オフハンドはこのメソッドの対象外。
            }
        }
    }
}
