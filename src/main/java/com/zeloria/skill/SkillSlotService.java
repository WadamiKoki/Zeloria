package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.core.MessageService;
import com.zeloria.combat.CombatModeService;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.item.VirtualItem;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.SkillManager;
import com.zeloria.skill.Skill;
import com.zeloria.player.PlayerData;
import com.zeloria.player.ZeloriaClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 戦闘モード中のスキルスロット表示を管理するサービス。
 * <p>
 * ホットバー slot 1〜5（数字キー 2〜6）に、現在のクラスのスキルを表示する。
 * 戦闘モード ON 時はプレイヤーが元から持っていたスロット 1〜5 の実アイテムを退避し、
 * OFF 時は退避アイテムをロック枠以外の空きスロットへ戻したうえで、割り当ての無い
 * スロットを含む slot 1〜5 を灰色ガラス板で埋める。スキル表示アイテムは
 * {@link VirtualItem#markSkillSlot(ItemStack)} で仮想アイテムとして扱うため、
 * ドロップ・移動・死亡時ドロップの保護対象になる。
 * <p>
 * スキルの割り当ては仮実装として「クラス定義の {@code skills} 順」に 1 件ずつ行う。
 * 既存インストールで {@code skills} が空の場合は、スキル ID の接頭辞
 * （例: {@code knight_}）で該当クラスのスキルを推定して割り当てる。
 */
public final class SkillSlotService {

    /** スキルスロットとして使用する最初のホットバー番号。 */
    public static final int FIRST_SKILL_SLOT = 1;

    /** スキルスロットとして使用する最後のホットバー番号。 */
    public static final int LAST_SKILL_SLOT = 5;

    /** スキルスロット数。 */
    public static final int SKILL_SLOT_COUNT = LAST_SKILL_SLOT - FIRST_SKILL_SLOT + 1;

    /** スキルアイコンの最大スタック数（クールダウン秒数の表示上限）。 */
    private static final int ICON_MAX_STACK = 64;

    /** 戦闘モード OFF 時に表示する灰色ガラス板（空きスキルスロット）。 */
    private static final ItemStack IDLE_ICON =
            VirtualItem.markPlaceholder(new ItemStack(Material.GRAY_STAINED_GLASS_PANE));

    /** メッセージ定義ファイル（{@code MessageData/equipment.yml}）。 */
    private static final String MESSAGE_FILE = "equipment";

    /** 退避アイテムが入りきらず足元へドロップした際の通知キー。 */
    private static final String MESSAGE_OVERFLOW = "overflow_dropped";

    private final Zeloria plugin;

    /** プレイヤー UUID → 退避した元アイテム（スキルスロット順）。 */
    private final Map<UUID, ItemStack[]> savedItems = new ConcurrentHashMap<>();

    public SkillSlotService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * スキルスロットの表示を開始する（表示済みの場合は内容を更新する）。
     * <p>
     * 元アイテムの退避は初回のみ行う。割り当てられていないスキルスロットには
     * 灰色ガラス板を配置し、スロットの見た目を統一する。
     *
     * @param player 対象プレイヤー
     */
    public void show(Player player) {
        if (!isAvailable(player)) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> show(player));
            return;
        }

        List<Skill> skills = assignedSkills(player);
        if (!savedItems.containsKey(player.getUniqueId())) {
            savedItems.put(player.getUniqueId(), saveAndClear(player));
        }
        applyIcons(player, skills);
    }

    /**
     * スキルスロットの表示を終了し、退避していた元アイテムをインベントリへ戻す。
     * <p>
     * ホットバー slot 1〜5 は戦闘モード OFF の常設表示として灰色ガラス板で埋め、
     * 元アイテムをホットバーへ戻さない（裏でアイテムを保持しない）。
     *
     * @param player 対象プレイヤー
     */
    public void hide(Player player) {
        if (player == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> hide(player));
            return;
        }
        // 戦闘モードを一度も ON にしていない場合は、ホットバーの実アイテムを動かさない。
        if (!savedItems.containsKey(player.getUniqueId()) && !hasVirtualSkillDisplay(player)) {
            return;
        }
        clearSlots(player, true);
    }

    /**
     * 戦闘モード OFF 時のホットバー slot 1〜5 を灰色ガラス板で埋める。
     * <p>
     * スロットに実アイテムが入っていた場合は、失わないようロック枠以外の
     * 空きスロットへ移動する。既に灰色ガラス板が配置されている場合は何もしない。
     *
     * @param player 対象プレイヤー
     */
    public void showIdle(Player player) {
        if (!isAvailable(player)) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> showIdle(player));
            return;
        }

        CombatModeService combatModeService = plugin.getCombatModeService();
        if (combatModeService != null && combatModeService.isActive(player)) {
            // 戦闘モード中はスキルアイコンを優先する。
            return;
        }

        PlayerInventory inventory = player.getInventory();
        List<ItemStack> displaced = new ArrayList<>();
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            ItemStack current = inventory.getItem(slot);
            if (isIdleIcon(current)) {
                continue;
            }
            if (current != null && !current.getType().isAir() && !VirtualItem.isVirtual(current)) {
                displaced.add(current.clone());
            }
            inventory.setItem(slot, IDLE_ICON.clone());
        }

        for (ItemStack item : displaced) {
            returnToInventory(player, item);
        }
    }

    /**
     * 死亡時にスキルスロットの表示を解除し、退避していた元アイテムを返す。
     * <p>
     * 返却したアイテムは呼び出し側が死亡ドロップへ追加するか、インベントリへ戻す。
     * スキル表示アイテム自体はインベントリから除去する。
     *
     * @param player 対象プレイヤー
     * @return 退避していた元アイテム（存在しない場合は空）
     */
    public List<ItemStack> detach(Player player) {
        if (player == null || !Bukkit.isPrimaryThread()) {
            return List.of();
        }

        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            if (VirtualItem.isVirtual(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }

        ItemStack[] saved = savedItems.remove(player.getUniqueId());
        if (saved == null) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>(SKILL_SLOT_COUNT);
        for (ItemStack item : saved) {
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 退避アイテムをインベントリの空きスロットへ戻す。
     *
     * @param player 対象プレイヤー
     * @param items  戻すアイテム
     */
    public void restoreItems(Player player, List<ItemStack> items) {
        if (player == null || items == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            List<ItemStack> copy = List.copyOf(items);
            Bukkit.getScheduler().runTask(plugin, () -> restoreItems(player, copy));
            return;
        }
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                returnToInventory(player, item);
            }
        }
    }

    /**
     * 現在のクラスに割り当てられたスキルを、スキルスロット順で取得する。
     *
     * @param player 対象プレイヤー
     * @return スキル一覧
     */
    public List<Skill> assignedSkills(Player player) {
        if (player == null) {
            return List.of();
        }
        ClassManager classManager = plugin.getClassManager();
        SkillManager skillManager = plugin.getSkillManager();
        if (classManager == null || skillManager == null) {
            return List.of();
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            return List.of();
        }

        ZeloriaClass definition = classManager
                .find(data.getClassData().currentClassIdOrNull())
                .orElse(null);
        if (definition == null) {
            return List.of();
        }

        List<Skill> skills = skillManager.skillsFor(definition);
        if (!skills.isEmpty()) {
            return skills;
        }

        // 既存インストールでクラス定義の skills が空の場合のフォールバック。
        String prefix = definition.id() + "_";
        return skillManager.all().stream()
                .filter(skill -> skill.id().startsWith(prefix))
                .sorted(Comparator.comparing(Skill::id))
                .toList();
    }

    /**
     * 指定ホットバースロットに割り当てられたスキルを取得する。
     *
     * @param player     対象プレイヤー
     * @param hotbarSlot ホットバー番号（1〜5）
     * @return スキル。割り当てが無い場合は空
     */
    public Optional<Skill> skillAt(Player player, int hotbarSlot) {
        int index = hotbarSlot - FIRST_SKILL_SLOT;
        if (index < 0 || index >= SKILL_SLOT_COUNT) {
            return Optional.empty();
        }
        List<Skill> skills = assignedSkills(player);
        return index < skills.size() ? Optional.of(skills.get(index)) : Optional.empty();
    }

    /**
     * 戦闘モードを継続できる装備状態か判定する。
     * <p>
     * ホットバー slot 0 に仮想武器があり、選択中のスロットが 0〜5 の場合に限り
     * 戦闘モードを維持する。
     *
     * @param player 対象プレイヤー
     * @return 維持できる場合は {@code true}
     */
    public boolean isCombatLoadout(Player player) {
        if (player == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        if (!VirtualItem.isVirtualEquipment(
                inventory.getItem(VirtualInventoryService.WEAPON_HOTBAR_SLOT))) {
            return false;
        }
        int held = inventory.getHeldItemSlot();
        return held >= VirtualInventoryService.WEAPON_HOTBAR_SLOT
                && held <= LAST_SKILL_SLOT;
    }

    /**
     * プラグイン無効化時に、オンラインプレイヤーの表示を解除する。
     * <p>
     * 無効化中は空きスロット表示（灰色ガラス）を配置せず、スキルアイコンと
     * 退避アイテムの復元だけを行う。
     */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearSlots(player, false);
        }
        savedItems.clear();
    }

    /**
     * スキルスロットを空にし、退避アイテムをインベントリの空き枠へ戻す。
     *
     * @param player        対象プレイヤー
     * @param showIdleAfter 解除後に灰色ガラス板を配置する場合 {@code true}
     */
    private void clearSlots(Player player, boolean showIdleAfter) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] saved = savedItems.remove(player.getUniqueId());
        List<ItemStack> overflow = new ArrayList<>();

        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            ItemStack current = inventory.getItem(slot);
            if (saved != null && saved[index] != null) {
                // 退避アイテムはホットバーへ戻さず、空きスロットへ逃がす。
                overflow.add(saved[index]);
            }
            if (current != null
                    && !current.getType().isAir()
                    && !VirtualItem.isVirtual(current)) {
                overflow.add(current.clone());
            }
            inventory.setItem(slot, null);
        }

        for (ItemStack item : overflow) {
            if (item != null && !item.getType().isAir()) {
                returnToInventory(player, item);
            }
        }

        if (showIdleAfter) {
            showIdle(player);
        }
    }

    private ItemStack[] saveAndClear(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] saved = new ItemStack[SKILL_SLOT_COUNT];
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            ItemStack current = inventory.getItem(slot);
            if (current != null
                    && !current.getType().isAir()
                    && !VirtualItem.isVirtual(current)) {
                saved[index] = current.clone();
            }
            inventory.setItem(slot, null);
        }
        return saved;
    }

    private void applyIcons(Player player, List<Skill> skills) {
        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            ItemStack current = inventory.getItem(slot);
            if (!isEmptyOrVirtual(current)) {
                continue;
            }

            Skill skill = index < skills.size() ? skills.get(index) : null;
            ItemStack desired = skill == null
                    ? IDLE_ICON.clone()
                    : createIcon(skill, slot);
            if (current != null && !current.getType().isAir()) {
                // isSimilar は個数を比較しないが、クールダウン表示の個数を
                // 誤って 1 に戻さないことを明示する。
                ItemStack comparable = desired.clone();
                comparable.setAmount(current.getAmount());
                if (current.isSimilar(comparable)) {
                    continue;
                }
            }
            inventory.setItem(slot, desired);
        }
    }

    private static ItemStack createIcon(Skill skill, int hotbarSlot) {
        ItemStack icon = new ItemStack(skill.icon());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(skill.displayName());
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("スキル " + (hotbarSlot + 1), NamedTextColor.GRAY));
            if (!skill.description().isEmpty()) {
                lore.addAll(skill.description());
            }
            lore.add(Component.text("右クリックで発動", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            // バニラでスタック不可のアイコン（剣など）でもクールダウン秒数を
            // 個数として表示できるようにする（Geyser / Bedrock を含む）。
            meta.setMaxStackSize(ICON_MAX_STACK);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(meta);
        }
        return VirtualItem.markSkillSlot(icon);
    }

    private static boolean isEmptyOrVirtual(ItemStack item) {
        return item == null || item.getType().isAir() || VirtualItem.isVirtual(item);
    }

    /**
     * ホットバー slot 1〜5 にスキル表示アイコンが残っているか判定する。
     */
    private static boolean hasVirtualSkillDisplay(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            if (VirtualItem.isSkillSlot(inventory.getItem(FIRST_SKILL_SLOT + index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * アイテムが戦闘モード OFF 時用の灰色ガラス板か判定する。
     */
    private static boolean isIdleIcon(ItemStack item) {
        return item != null
                && item.getType() == Material.GRAY_STAINED_GLASS_PANE
                && VirtualItem.isPlaceholder(item);
    }

    private void returnToInventory(Player player, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        int slot = VirtualInventoryService.firstEmptyUnlockedSlot(inventory);
        if (slot >= 0) {
            inventory.setItem(slot, item);
            return;
        }
        if (player.isOnline()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            MessageService messages = plugin.getMessageService();
            if (messages != null) {
                messages.actionBar(player, MESSAGE_FILE, MESSAGE_OVERFLOW, Map.of());
            }
        }
    }

    private static boolean isAvailable(Player player) {
        return player != null && player.isOnline();
    }

}
