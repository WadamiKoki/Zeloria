package com.zeloria.skill;

import com.zeloria.core.TextComponents;
import com.zeloria.core.Zeloria;
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
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.Objects;
import java.util.Optional;

/**
 * 戦闘モード中のスキルスロット表示を管理するサービス。
 * <p>
 * ホットバー slot 1〜5（数字キー 2〜6）に、現在のクラスのスキルを表示する。
 * 通常レイヤーでは slot 1〜5 が常に仮想表示で埋まるため、実アイテムの退避・返却は行わない。
 * 戦闘モード ON 時はスキルアイコン、OFF 時は割り当ての無いスロットを含めて
 * 灰色ガラス板を配置する。スキル表示アイテムは
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

    /** スキル未割り当て時に表示する黒色ガラス板（空きスキルスロット）。 */
    private static final ItemStack IDLE_ICON = createIdleIcon();

    /**
     * スキル未割り当て時のスキルスロット表示アイテムを生成する。
     */
    private static ItemStack createIdleIcon() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("スキルスロット", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return VirtualItem.markPlaceholder(item);
    }

    private final Zeloria plugin;

    public SkillSlotService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * スキルスロットの表示を開始する（表示済みの場合は内容を更新する）。
     * <p>
     * 割り当てられていないスキルスロットには灰色ガラス板を配置し、
     * スロットの見た目を統一する。
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

        applyIcons(player, assignedSkills(player));
    }

    /**
     * スキルスロットの表示を終了し、slot 1〜5 を空きスロット表示へ戻す。
     * <p>
     * 実アイテムの退避・返却は行わない。スキル表示アイコンは仮想アイテムのため、
     * 空きスロット表示で上書きするだけで安全に消去できる。
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
        showIdle(player);
    }

    /**
     * 戦闘モード OFF 時のホットバー slot 1〜5 を空きスロット表示で埋める。
     * <p>
     * 実アイテムの退避・返却は行わない。仮想表示または空のスロットだけを対象とし、
     * 想定外の実アイテムが存在する場合は上書きせずそのまま残す。
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
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            ItemStack current = inventory.getItem(slot);
            if (isIdleIcon(current)) {
                continue;
            }
            if (current == null || current.getType().isAir()
                    || VirtualItem.isVirtual(current)) {
                inventory.setItem(slot, IDLE_ICON.clone());
            }
        }
    }

    /**
     * 仮想スキル表示だけを除去する（死亡・退出・プラグイン停止時）。
     * <p>
     * 実アイテムの退避・返却は行わない。空きスロット表示も配置しないため、
     * 退出処理後にバニラのプレイヤーデータへ仮想アイテムが残らない。
     *
     * @param player 対象プレイヤー
     */
    public void clearDisplay(Player player) {
        if (player == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> clearDisplay(player));
            return;
        }

        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < SKILL_SLOT_COUNT; index++) {
            int slot = FIRST_SKILL_SLOT + index;
            if (VirtualItem.isVirtual(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
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

        // クラス定義の skills が空の場合のフォールバック。
        // スキル ID は <classId>:<skillId> の複合キーなので、クラス ID の接頭辞で絞り込む。
        String prefix = definition.id() + ":";
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
     * プラグイン無効化時に、オンラインプレイヤーの仮想スキル表示を解除する。
     * <p>
     * 無効化中は空きスロット表示（灰色ガラス）も配置しない。
     */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearDisplay(player);
        }
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
            meta.displayName(TextComponents.noItalic(skill.displayName()));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("スキル " + (hotbarSlot + 1), NamedTextColor.GRAY));
            if (!skill.description().isEmpty()) {
                lore.addAll(skill.description());
            }
            lore.add(Component.text("右クリックで発動", NamedTextColor.DARK_GRAY));
            meta.lore(TextComponents.noItalic(lore));
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
     * アイテムが戦闘モード OFF 時用の灰色ガラス板か判定する。
     */
    private static boolean isIdleIcon(ItemStack item) {
        return item != null
                && item.getType() == Material.GRAY_STAINED_GLASS_PANE
                && VirtualItem.isPlaceholder(item);
    }

    private static boolean isAvailable(Player player) {
        return player != null && player.isOnline();
    }

}
