package com.zeloria.item;

import com.zeloria.core.Zeloria;
import com.zeloria.core.MessageService;
import com.zeloria.item.VirtualItem;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import com.zeloria.player.VirtualInventoryTab;
import com.zeloria.player.VirtualItemEntry;
import com.zeloria.combat.CombatModeService;
import com.zeloria.skill.SkillSlotService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 装備中の武器・防具と、タブ切り替え式の仮想インベントリをプレイヤーへ表示するサービス。
 * <p>
 * 装備表示は次の 5 スロットに限定する。
 * <ul>
 *     <li>ホットバー slot 0: メイン武器の仮想スロット（プレイヤー操作不可）</li>
 *     <li>バニラ防具 4 枠: 装備中の防具の仮想スロット（プレイヤー操作不可）</li>
 * </ul>
 * ホットバー slot 1〜8 は装備表示の対象外。slot 1〜5 は戦闘モードのスキル枠で、
 * OFF 時は {@link com.zeloria.skill.SkillSlotService#showIdle(Player)} が
 * 灰色ガラス板を配置する。
 * <p>
 * メインインベントリ（slot 9〜35）は、保管庫・装備保管庫の 2 タブを切り替えて表示する。
 * 1 タブ 100 枠を 24 枠ずつ表示し、slot 17 / 35 の矢印で 8 枠ずつスクロール、
 * slot 26 のチェストでタブを切り替える。表示用アイテムの数量は常に 1 とし、
 * 実際の所持数は Lore に表示する。
 * <p>
 * 表示用アイテムは {@link VirtualItem#mark(ItemStack)} で複製したうえで PDC を付与し、
 * {@link PlayerData} には保存しない。ログイン時や装備変更時は必ず {@link #refresh(Player)}
 * を呼び、DB のデータからその都度生成する。
 */
public class VirtualInventoryService {

    /** メイン武器を表示するホットバーのスロット番号。 */
    public static final int WEAPON_HOTBAR_SLOT = 0;

    /** バニラ防具スロット（{@link PlayerInventory} のインデックス）。 */
    public static final int BOOTS_SLOT = 36;
    public static final int LEGGINGS_SLOT = 37;
    public static final int CHESTPLATE_SLOT = 38;
    public static final int HELMET_SLOT = 39;

    /** オフハンドスロット（{@link PlayerInventory} のインデックス）。 */
    public static final int OFFHAND_SLOT = 40;

    /** 右端 3 枠のガラスパネル開始スロット。 */
    public static final int HOTBAR_PANEL_FIRST_SLOT = 6;

    /** 右端 3 枠のガラスパネル終了スロット。 */
    public static final int HOTBAR_PANEL_LAST_SLOT = 8;

    /* ------------------------------------------------------------------
     * 仮想インベントリ（メインインベントリ 9〜35）
     * ------------------------------------------------------------------ */

    /** 仮想インベントリの開始スロット。 */
    public static final int BAG_FIRST_SLOT = 9;

    /** 仮想インベントリの終了スロット。 */
    public static final int BAG_LAST_SLOT = 35;

    /** 上スクロールボタンのスロット。 */
    public static final int SCROLL_UP_SLOT = 17;

    /** 下スクロールボタンのスロット。 */
    public static final int SCROLL_DOWN_SLOT = 35;

    /** タブ切り替えボタンのスロット。 */
    public static final int TAB_SWITCH_SLOT = 26;

    /** 1 ページに表示するアイテム枠（8 枠 × 3 行）。 */
    public static final int BAG_PAGE_SIZE = 24;

    /** スクロール 1 回あたりに移動する枠数（1 行分）。 */
    public static final int BAG_SCROLL_STEP = 8;

    /** 1 タブあたりの最大枠数。 */
    public static final int BAG_MAX_ENTRIES = 100;

    /** アイテム表示に使用するスロット（9〜16, 18〜25, 27〜34）。 */
    private static final int[] BAG_ITEM_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16,
            18, 19, 20, 21, 22, 23, 24, 25,
            27, 28, 29, 30, 31, 32, 33, 34
    };

    /** PDC キー: スクロール方向（{@code up} / {@code down}）。 */
    public static final String KEY_SCROLL = "scroll";

    /** PDC キー: タブ切り替えボタン。 */
    public static final String KEY_TAB_SWITCH = "tab_switch";

    /** PDC キー: 仮想インベントリのアイテム（値は枠キー）。 */
    public static final String KEY_VIRTUAL_ITEM = "virtual_item";

    /** PDC キー: 仮想インベントリのタブ名。 */
    public static final String KEY_BAG_TAB = "bag_tab";

    private static final NamespacedKey SCROLL_KEY =
            new NamespacedKey(ItemService.MODEL_NAMESPACE, KEY_SCROLL);
    private static final NamespacedKey TAB_SWITCH_KEY =
            new NamespacedKey(ItemService.MODEL_NAMESPACE, KEY_TAB_SWITCH);
    private static final NamespacedKey VIRTUAL_ITEM_KEY =
            new NamespacedKey(ItemService.MODEL_NAMESPACE, KEY_VIRTUAL_ITEM);
    private static final NamespacedKey BAG_TAB_KEY =
            new NamespacedKey(ItemService.MODEL_NAMESPACE, KEY_BAG_TAB);

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

    /** プレイヤーごとの現在のタブ。 */
    private final Map<UUID, VirtualInventoryTab> currentTabs = new HashMap<>();

    /** プレイヤーごとの現在のスクロール位置（0 始まりの先頭インデックス）。 */
    private final Map<UUID, Integer> currentScrolls = new HashMap<>();

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

        // ロック枠に残っている実アイテム（外部プラグインや手動配置など）は、
        // 上書きして失わないよう退避しておく。
        List<ItemStack> displaced = new ArrayList<>();
        collectDisplaced(inventory.getItem(WEAPON_HOTBAR_SLOT), displaced);
        absorbHotbarPanelItems(data, inventory, displaced);
        collectDisplaced(inventory.getHelmet(), displaced);
        collectDisplaced(inventory.getChestplate(), displaced);
        collectDisplaced(inventory.getLeggings(), displaced);
        collectDisplaced(inventory.getBoots(), displaced);

        applySlot(inventory, WEAPON_HOTBAR_SLOT, data.getEquipment(EquipSlot.MAIN_WEAPON));
        applyArmor(inventory, EquipSlot.HELMET, data.getEquipment(EquipSlot.HELMET));
        applyArmor(inventory, EquipSlot.CHESTPLATE, data.getEquipment(EquipSlot.CHESTPLATE));
        applyArmor(inventory, EquipSlot.LEGGINGS, data.getEquipment(EquipSlot.LEGGINGS));
        applyArmor(inventory, EquipSlot.BOOTS, data.getEquipment(EquipSlot.BOOTS));

        // メインインベントリ（9〜35）をタブ切り替え式の仮想インベントリへ切り替える。
        // メインインベントリに残っている実アイテムは、失わないよう仮想インベントリへ取り込む。
        absorbPhysicalBagItems(player, data, inventory);
        renderBag(player, data, inventory);

        // 仮想アイテムを配置した後に退避アイテムを戻すことで、
        // 空き枠探索がロック枠（特に slot 0）へ吸い込まれるのを防ぐ。
        for (ItemStack item : displaced) {
            returnToInventory(player, inventory, item);
        }

        // 右端 3 枠は仮想ストレージ化に伴い、常時ガラスパネルで埋める。
        applyHotbarPanels(inventory);

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
     * 右端 3 枠に残っている実アイテムを仮想インベントリへ取り込む。
     * <p>
     * プラグイン製アイテムは対応するタブへ格納し、判定できないアイテムだけを
     * 退避リストへ回す。パネル表示で上書きしてアイテムを失わないための処理。
     */
    private void absorbHotbarPanelItems(PlayerData data, PlayerInventory inventory,
                                        List<ItemStack> displaced) {
        ItemService itemService = plugin.getItemService();
        for (int slot = HOTBAR_PANEL_FIRST_SLOT; slot <= HOTBAR_PANEL_LAST_SLOT; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir() || VirtualItem.isVirtual(current)) {
                continue;
            }

            ItemData definition = itemService == null
                    ? null
                    : itemService.findItem(current).orElse(null);
            if (definition == null) {
                displaced.add(current.clone());
            } else {
                store(data, tabFor(definition), definition.id(),
                        Math.max(1, current.getAmount()));
            }
            inventory.setItem(slot, null);
        }
    }

    /**
     * ホットバー右端 3 枠を操作不可のガラスパネルで埋める。
     * <p>
     * 仮想表示アイテムとしてマーカーを付与するため、ドロップ・移動・拾得の
     * 対象にはならない。既に同じパネルがある場合は再生成しない。
     */
    private void applyHotbarPanels(PlayerInventory inventory) {
        for (int slot = HOTBAR_PANEL_FIRST_SLOT; slot <= HOTBAR_PANEL_LAST_SLOT; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (VirtualItem.isPlaceholder(current)
                    && current.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                continue;
            }
            ItemStack panel = VirtualItem.markPlaceholder(
                    new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
            inventory.setItem(slot, panel);
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
        // メインインベントリ（9〜35）は仮想インベントリ表示、ホットバー右端 3 枠は
        // ガラスパネルのため、退避先はホットバー 1〜5 のみとする。
        ItemStack remaining = item.clone();
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
     * メインインベントリ（9〜35）は仮想インベントリが使用し、ホットバー右端 3 枠
     * （6〜8）はガラスパネルで固定するため、ホットバー 1〜5 のみを探す。
     * ホットバー slot 0 と防具・オフハンドは対象外。
     *
     * @return 空きスロット。空きが無い場合は {@code -1}
     */
    public static int firstEmptyUnlockedSlot(PlayerInventory inventory) {
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
     * 仮想インベントリ（タブ・スクロール・追加/消費）
     * ------------------------------------------------------------------ */

    /**
     * ログイン時にタブを初期状態（保管庫・先頭）へ戻して表示する。
     *
     * @param player 対象プレイヤー
     */
    public void onJoin(Player player) {
        if (player == null) {
            return;
        }
        currentTabs.put(player.getUniqueId(), VirtualInventoryTab.STORAGE);
        currentScrolls.put(player.getUniqueId(), 0);
        if (PlayerData.getCached(player.getUniqueId()) == null) {
            // DB 読み込みが参加処理に間に合わなかった場合は、1 秒後に再試行する。
            Bukkit.getScheduler().runTaskLater(plugin, () -> updateBag(player), 20L);
            return;
        }
        updateBag(player);
    }

    /**
     * 仮想インベントリの表示を更新する。
     *
     * @param player 対象プレイヤー
     */
    public void updateBag(Player player) {
        refresh(player);
    }

    /**
     * 現在のタブを取得する。
     */
    public VirtualInventoryTab currentTab(Player player) {
        if (player == null) {
            return VirtualInventoryTab.STORAGE;
        }
        return currentTabs.getOrDefault(player.getUniqueId(), VirtualInventoryTab.STORAGE);
    }

    /**
     * タブを「保管庫 → 装備保管庫 → 保管庫…」の順で切り替える。
     *
     * @param player 対象プレイヤー
     */
    public void switchTab(Player player) {
        if (player == null) {
            return;
        }
        VirtualInventoryTab next = currentTab(player).next();
        currentTabs.put(player.getUniqueId(), next);
        currentScrolls.put(player.getUniqueId(), 0);
        updateBag(player);
    }

    /**
     * 表示位置を 1 行（8 枠）ずつスクロールする。
     *
     * @param player 対象プレイヤー
     * @param delta  変化量（上へは負、下へは正）
     */
    public void scroll(Player player, int delta) {
        if (player == null || delta == 0) {
            return;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return;
        }
        int count = data.getVirtualBag(currentTab(player)).size();
        int maxScroll = maxScroll(count);
        int offset = Math.max(0, Math.min(maxScroll,
                currentScroll(player, count) + delta * BAG_SCROLL_STEP));
        currentScrolls.put(player.getUniqueId(), offset);
        updateBag(player);
    }

    /**
     * アイテムを仮想インベントリへ追加する。
     * <p>
     * {@code ItemData} のカテゴリから追加先タブを判定する。
     * 武器・防具は 1 枠 1 個（論理数量 1）、素材・通常アイテムは同一 ID の枠へ加算する。
     * 数量は {@link Integer#MAX_VALUE} でクランプする。
     *
     * @param player 対象プレイヤー
     * @param itemId アイテム ID
     * @param amount 追加数
     * @return 実際に追加できた数（タブが満杯の場合は 0）
     */
    public long addItem(Player player, String itemId, long amount) {
        if (player == null || !player.isOnline() || itemId == null
                || itemId.isBlank() || amount <= 0) {
            return 0L;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return 0L;
        }
        ItemData definition = findItem(itemId);
        if (definition == null) {
            return 0L;
        }

        VirtualInventoryTab target = tabFor(definition);
        long added = store(data, target, definition.id(), amount);
        if (added > 0) {
            updateBag(player);
        }
        return added;
    }

    /**
     * 仮想インベントリのアイテムを消費する。
     * <p>
     * 論理数量を減算し、0 以下になった枠は削除する。表示中の Lore も更新する。
     *
     * @param player 対象プレイヤー
     * @param tab    タブ
     * @param key    枠キー
     * @param amount 消費数
     * @return 消費できた場合は {@code true}
     */
    public boolean consumeItem(Player player, VirtualInventoryTab tab, String key, long amount) {
        if (player == null || tab == null || key == null || amount <= 0) {
            return false;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return false;
        }
        VirtualItemEntry entry = data.getVirtualBagEntry(tab, key);
        if (entry == null) {
            return false;
        }

        VirtualItemEntry updated = entry.minus(amount);
        if (updated.amount() <= 0) {
            data.removeVirtualBagEntry(tab, key);
        } else {
            data.putVirtualBagEntry(tab, key, updated);
        }
        updateBag(player);
        return true;
    }

    /**
     * 仮想インベントリのアイテムを使用する。
     * <p>
     * 武器・防具は装備処理を行う。素材などの消費は
     * {@link #consumeItem(Player, VirtualInventoryTab, String, long)} を使用する。
     *
     * @param player 対象プレイヤー
     * @param tab    タブ
     * @param key    枠キー
     * @return 処理できた場合は {@code true}
     */
    public boolean useEntry(Player player, VirtualInventoryTab tab, String key) {
        if (player == null || tab == null || key == null) {
            return false;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return false;
        }
        VirtualItemEntry entry = data.getVirtualBagEntry(tab, key);
        if (entry == null) {
            return false;
        }
        ItemData definition = findItem(entry.itemId());
        if (definition == null
                || (definition.category() != ItemCategory.ARMOR
                && definition.category() != ItemCategory.WEAPON)) {
            return false;
        }
        return equipEntry(player, tab, key);
    }

    /**
     * 仮想インベントリの枠を別タブへ移動する。
     * <p>
     * 保管庫と装備保管庫の間で移動する。保管庫から装備保管庫へ移動できるのは
     * 武器・防具のみ。
     *
     * @param player 対象プレイヤー
     * @param from   移動元タブ
     * @param key    移動元の枠キー
     * @return 移動できた場合は {@code true}
     */
    public boolean moveEntry(Player player, VirtualInventoryTab from, String key) {
        if (player == null || from == null || key == null) {
            return false;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return false;
        }
        VirtualItemEntry entry = data.getVirtualBagEntry(from, key);
        if (entry == null) {
            return false;
        }

        VirtualInventoryTab target = from == VirtualInventoryTab.STORAGE
                ? VirtualInventoryTab.EQUIPMENT_STORAGE
                : VirtualInventoryTab.STORAGE;
        if (target == VirtualInventoryTab.EQUIPMENT_STORAGE) {
            ItemData definition = findItem(entry.itemId());
            if (definition == null
                    || (definition.category() != ItemCategory.ARMOR
                    && definition.category() != ItemCategory.WEAPON)) {
                return false;
            }
        }

        long moved = store(data, target, entry.itemId(), entry.amount());
        if (moved <= 0) {
            return false;
        }
        if (moved >= entry.amount()) {
            data.removeVirtualBagEntry(from, key);
        } else {
            data.putVirtualBagEntry(from, key, entry.minus(moved));
        }
        updateBag(player);
        return true;
    }

    private boolean equipEntry(Player player, VirtualInventoryTab tab, String key) {
        if (player == null || tab == null || key == null) {
            return false;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        ItemService itemService = plugin.getItemService();
        if (data == null || itemService == null) {
            return false;
        }
        VirtualItemEntry entry = data.getVirtualBagEntry(tab, key);
        if (entry == null) {
            return false;
        }
        ItemData definition = findItem(entry.itemId());
        if (definition == null) {
            return false;
        }

        ItemStack stack = itemService.createItem(definition);
        EquipSlot equipSlot = resolveEquipSlot(definition, stack);
        if (equipSlot == null) {
            return false;
        }
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (equipmentService != null
                && (!equipmentService.meetsRequiredLevel(player, stack)
                || !equipmentService.canEquip(player, stack))) {
            return false;
        }

        ItemStack previous = data.getEquipment(equipSlot);
        ItemData previousDefinition = previous == null
                ? null
                : itemService.findItem(previous).orElse(null);
        if (previous != null && previousDefinition == null) {
            return false;
        }
        if (previousDefinition != null
                && store(data, VirtualInventoryTab.EQUIPMENT_STORAGE,
                previousDefinition.id(), 1L) <= 0) {
            return false;
        }

        data.removeVirtualBagEntry(tab, key);
        data.setEquipment(equipSlot, stack);
        if (equipmentService != null) {
            equipmentService.recompute(player);
        } else {
            updateBag(player);
        }
        return true;
    }

    /**
     * アイテム定義から装着先スロットを解決する。
     * <p>
     * 武器は素材が棒・クワなどでもメイン武器として扱う。防具は定義の
     * {@code equipment_slot} を優先し、判定できない場合のみ素材から推定する。
     */
    private static EquipSlot resolveEquipSlot(ItemData definition, ItemStack stack) {
        if (definition instanceof WeaponData) {
            return EquipSlot.MAIN_WEAPON;
        }
        if (definition instanceof ArmorData armor) {
            return armor.equipmentSlot();
        }
        return EquipSlot.match(stack);
    }

    /**
     * 指定タブへアイテムを格納する（表示更新は行わない）。
     *
     * @return 実際に格納できた数
     */
    private long store(PlayerData data, VirtualInventoryTab tab, String itemId, long amount) {
        if (data == null || tab == null || itemId == null || itemId.isBlank() || amount <= 0) {
            return 0L;
        }

        ItemData definition = findItem(itemId);
        boolean singleInstance = tab == VirtualInventoryTab.EQUIPMENT_STORAGE
                || (definition != null
                && (definition.category() == ItemCategory.ARMOR
                || definition.category() == ItemCategory.WEAPON));
        if (singleInstance) {
            long remaining = Math.min(amount, VirtualItemEntry.MAX_AMOUNT);
            long added = 0L;
            while (remaining > 0) {
                Map<String, VirtualItemEntry> bag = data.getVirtualBag(tab);
                if (bag.size() >= BAG_MAX_ENTRIES) {
                    break;
                }
                String key = uniqueKey(bag, itemId);
                data.putVirtualBagEntry(tab, key, VirtualItemEntry.of(itemId, 1L));
                remaining--;
                added++;
            }
            return added;
        }

        Map<String, VirtualItemEntry> bag = data.getVirtualBag(tab);
        VirtualItemEntry existing = bag.get(itemId);
        if (existing == null && bag.size() >= BAG_MAX_ENTRIES) {
            return 0L;
        }
        long current = existing == null ? 0L : existing.amount();
        long updated = Math.min(VirtualItemEntry.MAX_AMOUNT, current + amount);
        long added = updated - current;
        if (added <= 0) {
            return 0L;
        }
        data.putVirtualBagEntry(tab, itemId, new VirtualItemEntry(
                itemId, updated, existing == null ? "" : existing.serializedData()));
        return added;
    }

    private static String uniqueKey(Map<String, VirtualItemEntry> bag, String itemId) {
        if (!bag.containsKey(itemId)) {
            return itemId;
        }
        int suffix = 2;
        String key = itemId + "#" + suffix;
        while (bag.containsKey(key)) {
            suffix++;
            key = itemId + "#" + suffix;
        }
        return key;
    }

    private static VirtualInventoryTab tabFor(ItemData definition) {
        if (definition == null) {
            return VirtualInventoryTab.STORAGE;
        }
        return switch (definition.category()) {
            case ARMOR, WEAPON -> VirtualInventoryTab.EQUIPMENT_STORAGE;
            case MATERIAL, TOOL -> VirtualInventoryTab.STORAGE;
        };
    }

    private ItemData findItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            return null;
        }
        for (ItemCategory category : ItemCategory.values()) {
            Optional<ItemData> found = itemService.find(category, itemId);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }

    /**
     * メインインベントリ（9〜35）へ現在のタブを描画する。
     */
    private void renderBag(Player player, PlayerData data, PlayerInventory inventory) {
        // ItemService 未初期化時に実アイテムを消してしまわないよう、描画自体を中止する。
        if (plugin.getItemService() == null) {
            return;
        }
        for (int slot = BAG_FIRST_SLOT; slot <= BAG_LAST_SLOT; slot++) {
            inventory.setItem(slot, null);
        }

        VirtualInventoryTab tab = currentTab(player);
        List<Map.Entry<String, VirtualItemEntry>> entries = sortedBagEntries(data, tab);
        int maxScroll = maxScroll(entries.size());
        int offset = Math.max(0, Math.min(maxScroll, currentScroll(player, entries.size())));
        currentScrolls.put(player.getUniqueId(), offset);

        for (int index = 0; index < BAG_PAGE_SIZE; index++) {
            int entryIndex = offset + index;
            if (entryIndex >= entries.size()) {
                break;
            }
            Map.Entry<String, VirtualItemEntry> mapEntry = entries.get(entryIndex);
            ItemStack display = displayEntry(mapEntry.getKey(), mapEntry.getValue(), tab);
            if (display != null) {
                inventory.setItem(BAG_ITEM_SLOTS[index], display);
            }
        }

        int used = entries.size();
        int free = Math.max(0, BAG_MAX_ENTRIES - used);
        inventory.setItem(SCROLL_UP_SLOT, scrollButton("up", "▲ 上へスクロール", offset, maxScroll));
        inventory.setItem(SCROLL_DOWN_SLOT, scrollButton("down", "▼ 下へスクロール", offset, maxScroll));
        inventory.setItem(TAB_SWITCH_SLOT, tabButton(tab, offset, maxScroll, used, free));
    }

    /**
     * 表示用にタブ内のエントリ一覧を生成する。
     * <p>
     * 保管庫（{@link VirtualInventoryTab#STORAGE}）のみ自動ソートする。
     * ツール（{@link ItemCategory#TOOL}）を最上部へまとめ、それ以外は
     * itemId の昇順、同一 itemId は枠キーの昇順で安定させる。
     * 装備保管庫は既存の並び順を維持する。
     *
     * @param data プレイヤーデータ
     * @param tab  表示中のタブ
     * @return 表示順に並べたエントリ
     */
    private List<Map.Entry<String, VirtualItemEntry>> sortedBagEntries(
            PlayerData data, VirtualInventoryTab tab) {
        List<Map.Entry<String, VirtualItemEntry>> entries =
                new ArrayList<>(data.getVirtualBag(tab).entrySet());
        if (tab != VirtualInventoryTab.STORAGE) {
            return entries;
        }
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, VirtualItemEntry> entry) ->
                        isToolEntry(entry.getValue()) ? 0 : 1)
                .thenComparing(entry -> entry.getValue().itemId(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.getKey(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * エントリがツールか判定する。
     */
    private boolean isToolEntry(VirtualItemEntry entry) {
        if (entry == null) {
            return false;
        }
        ItemData definition = findItem(entry.itemId());
        return definition != null && definition.category() == ItemCategory.TOOL;
    }

    private int currentScroll(Player player, int entryCount) {
        if (player == null) {
            return 0;
        }
        int offset = currentScrolls.getOrDefault(player.getUniqueId(), 0);
        return Math.max(0, Math.min(maxScroll(entryCount), offset));
    }

    /**
     * スクロール可能な最大先頭インデックスを返す。
     * <p>
     * 8 枠刻みで移動しつつ、最後のアイテムまで表示できる位置まで許可する。
     */
    private static int maxScroll(int entryCount) {
        int needed = Math.max(0, entryCount - BAG_PAGE_SIZE);
        if (needed <= 0) {
            return 0;
        }
        return ((needed + BAG_SCROLL_STEP - 1) / BAG_SCROLL_STEP) * BAG_SCROLL_STEP;
    }

    /**
     * 仮想インベントリの 1 枠を表示用 ItemStack へ変換する。
     * <p>
     * 数量は常に 1 に固定し、実際の所持数は Lore に表示する。
     */
    private ItemStack displayEntry(String key, VirtualItemEntry entry, VirtualInventoryTab tab) {
        if (entry == null || entry.itemId().isBlank()) {
            return null;
        }
        ItemData definition = findItem(entry.itemId());
        ItemService itemService = plugin.getItemService();
        if (definition == null || itemService == null) {
            return null;
        }

        ItemStack stack = itemService.createItem(definition);
        stack.setAmount(1);
        appendAmountLore(stack, entry.amount());

        ItemStack marked = VirtualItem.mark(stack);
        if (marked == null) {
            return null;
        }
        ItemMeta meta = marked.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    VIRTUAL_ITEM_KEY, PersistentDataType.STRING, key);
            meta.getPersistentDataContainer().set(
                    BAG_TAB_KEY, PersistentDataType.STRING, tab.name());
            marked.setItemMeta(meta);
        }
        return marked;
    }

    /**
     * 論理数量が 2 以上の場合のみ、Lore の末尾へ所持数を追加する。
     */
    private static void appendAmountLore(ItemStack stack, long amount) {
        if (stack == null || amount <= 1) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        String formatted = String.format(Locale.US, "%,d", amount);
        lore.add(MINI.deserialize(
                "<!italic><gray>所持数: <white>" + formatted + "</white></gray>"));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private static ItemStack scrollButton(String direction, String label,
                                          int offset, int maxScroll) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int page = offset / BAG_SCROLL_STEP + 1;
            int maxPage = maxScroll / BAG_SCROLL_STEP + 1;
            meta.displayName(MINI.deserialize("<!italic><white>" + label + "</white>"));
            meta.lore(List.of(MINI.deserialize(
                    "<!italic><gray>表示位置: <white>" + page + "</white>/<white>"
                            + maxPage + "</white></gray>")));
            meta.getPersistentDataContainer().set(
                    SCROLL_KEY, PersistentDataType.STRING, direction);
            item.setItemMeta(meta);
        }
        return VirtualItem.mark(item);
    }

    private static ItemStack tabButton(VirtualInventoryTab tab, int offset, int maxScroll,
                                       int used, int free) {
        Material material = tab == VirtualInventoryTab.EQUIPMENT_STORAGE
                ? Material.CHEST
                : Material.ENDER_CHEST;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int page = offset / BAG_SCROLL_STEP + 1;
            int maxPage = maxScroll / BAG_SCROLL_STEP + 1;
            meta.displayName(MINI.deserialize("<!italic><white>タブ切り替え</white>"));
            meta.lore(List.of(
                    MINI.deserialize("<!italic><gray>現在のタブ: <white>"
                            + tab.displayName() + "</white></gray>"),
                    MINI.deserialize("<!italic><gray>使用枠: <white>" + used
                            + "</white>/<white>" + BAG_MAX_ENTRIES + "</white></gray>"),
                    MINI.deserialize("<!italic><gray>空き容量: <green>" + free
                            + "</green> 枠</gray>"),
                    MINI.deserialize("<!italic><gray>表示位置: <white>" + page
                            + "</white>/<white>" + maxPage + "</white></gray>"),
                    MINI.deserialize("<!italic><dark_gray>クリックで次のタブへ</dark_gray>")));
            meta.getPersistentDataContainer().set(
                    TAB_SWITCH_KEY, PersistentDataType.STRING, tab.name());
            item.setItemMeta(meta);
        }
        return VirtualItem.mark(item);
    }

    /**
     * メインインベントリ（9〜35）に残っている実アイテムを
     * 仮想インベントリへ取り込む。
     * <p>
     * プラグイン製アイテムでない場合はホットバー 1〜8 へ退避し、
     * 入りきらない場合のみ足元へドロップする。
     */
    private void absorbPhysicalBagItems(Player player, PlayerData data, PlayerInventory inventory) {
        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            return;
        }

        List<ItemStack> unsupported = new ArrayList<>();
        for (int slot = BAG_FIRST_SLOT; slot <= BAG_LAST_SLOT; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir() || VirtualItem.isVirtual(current)) {
                continue;
            }
            ItemData definition = itemService.findItem(current).orElse(null);
            if (definition == null) {
                unsupported.add(current.clone());
            } else {
                store(data, tabFor(definition), definition.id(),
                        Math.max(1, current.getAmount()));
            }
            inventory.setItem(slot, null);
        }

        for (ItemStack item : unsupported) {
            returnToInventory(player, inventory, item);
        }
    }

    /* ------------------------------------------------------------------
     * 仮想インベントリのスロット判定
     * ------------------------------------------------------------------ */

    /**
     * メインインベントリの仮想インベントリ領域（9〜35）か判定する。
     */
    public static boolean isBagSlot(int slot) {
        return slot >= BAG_FIRST_SLOT && slot <= BAG_LAST_SLOT;
    }

    /**
     * アイテム表示枠（9〜16, 18〜25, 27〜34）か判定する。
     */
    public static boolean isBagItemSlot(int slot) {
        for (int candidate : BAG_ITEM_SLOTS) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    /**
     * スクロールボタンか判定する。
     */
    public static boolean isScrollButton(ItemStack stack) {
        return readString(stack, SCROLL_KEY) != null;
    }

    /**
     * スクロール方向（{@code up} / {@code down}）を取得する。
     *
     * @return 方向。スクロールボタンでない場合は {@code null}
     */
    public static String scrollDirection(ItemStack stack) {
        return readString(stack, SCROLL_KEY);
    }

    /**
     * タブ切り替えボタンか判定する。
     */
    public static boolean isTabSwitch(ItemStack stack) {
        return readString(stack, TAB_SWITCH_KEY) != null;
    }

    /**
     * 仮想インベントリのアイテムが保持する枠キーを取得する。
     *
     * @return 枠キー。仮想インベントリのアイテムでない場合は {@code null}
     */
    public static String bagEntryKey(ItemStack stack) {
        String key = readString(stack, VIRTUAL_ITEM_KEY);
        return key == null || key.isBlank() ? null : key;
    }

    /**
     * 仮想インベントリのアイテムが属するタブを取得する。
     *
     * @return タブ。判定できない場合は {@code null}
     */
    public static VirtualInventoryTab bagTabOf(ItemStack stack) {
        return VirtualInventoryTab.fromName(readString(stack, BAG_TAB_KEY));
    }

    private static String readString(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
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
        currentTabs.remove(player.getUniqueId());
        currentScrolls.remove(player.getUniqueId());
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
     * 対象はホットバー slot 0・右端 3 枠（6〜8）とバニラ防具 4 枠。
     * ホットバー slot 1〜5 はスキルスロットのため対象外。
     *
     * @param slot {@link PlayerInventory} のスロット番号
     * @return ロック対象の場合は {@code true}
     */
    public static boolean isLockedSlot(int slot) {
        return slot == WEAPON_HOTBAR_SLOT
                || (slot >= HOTBAR_PANEL_FIRST_SLOT && slot <= HOTBAR_PANEL_LAST_SLOT)
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
