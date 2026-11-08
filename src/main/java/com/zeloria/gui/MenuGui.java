package com.zeloria.gui;

import com.zeloria.core.TextComponents;
import com.zeloria.gathering.GatheringService;
import com.zeloria.gathering.GatheringType;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerSettings;
import com.zeloria.player.PlayerStats;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.LevelService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code /menu} で開く独自メニュー GUI。
 * <p>
 * この画面の責務は次の 2 点だけに限定する。
 * <ul>
 *     <li>仮想装備の着脱（装備枠の表示・クリックで外す）</li>
 *     <li>今後追加される各種メニュー機能（{@link MenuFeature}）の呼び出しボタン</li>
 * </ul>
 * <p>
 * {@link InventoryHolder} を実装しており、{@code InventoryClickEvent} から
 * 独自メニューかどうかを判別できる。描画はすべてメインスレッドで行う。
 */
public final class MenuGui implements InventoryHolder {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 機能アイコンのフォールバック Material。 */
    private static final Material FEATURE_FALLBACK_MATERIAL = Material.PAPER;

    /** 自分のレベルアップ通知を切り替えるメニューアクション。 */
    public static final String ACTION_CHAT_LOG_SELF = "chat_log_self";

    /** 他人のレベルアップ通知を切り替えるメニューアクション。 */
    public static final String ACTION_CHAT_LOG_OTHERS = "chat_log_others";

    private final UUID playerId;
    private final MenuLayout layout;
    private final LevelService levelService;
    private final GatheringService gatheringService;
    private final ClassManager classManager;

    /** GUI スロット番号 → 仮想装備スロット。 */
    private final Map<Integer, EquipSlot> equipmentSlots;

    /** GUI スロット番号 → メニュー機能。 */
    private final Map<Integer, MenuFeature> featureSlots;

    private final Inventory inventory;

    /**
     * メニュー GUI を生成する。
     *
     * @param player   対象プレイヤー
     * @param layout   {@code gui/menu.yml} のレイアウト設定（装備枠・装飾枠の位置を参照）
     * @param settings {@code gui/inventory.yml} の設定（menu.yml に装備枠定義が無い場合の既定値）
     * @param levelService 必要経験値の表示に使用するレベルサービス
     * @param gatheringService 採取レベルの表示に使用するギャザリングサービス
     * @param classManager クラス一覧の取得に使用するクラスマネージャー
     */
    public MenuGui(Player player, MenuLayout layout, InventorySettings settings,
                   LevelService levelService, GatheringService gatheringService,
                   ClassManager classManager) {
        this.playerId = Objects.requireNonNull(player, "player").getUniqueId();
        this.layout = Objects.requireNonNull(layout, "layout");
        this.levelService = levelService;
        this.gatheringService = gatheringService;
        this.classManager = classManager;
        this.equipmentSlots = buildEquipmentSlots(layout, settings);
        this.featureSlots = buildFeatureSlots(layout);
        this.inventory = Bukkit.createInventory(this, layout.totalSlots(),
                TextComponents.mini(MINI, layout.title()));
    }

    /* ------------------------------------------------------------------
     * スロット判定
     * ------------------------------------------------------------------ */

    /**
     * 指定 GUI スロットが仮想装備枠か判定する。
     */
    public boolean isEquipmentSlot(int guiSlot) {
        return equipmentSlots.containsKey(guiSlot);
    }

    /**
     * 指定 GUI スロットに対応する仮想装備スロットを取得する。
     *
     * @return 装備スロット。装備枠でない場合は {@code null}
     */
    public EquipSlot equipSlotAt(int guiSlot) {
        return equipmentSlots.get(guiSlot);
    }

    /**
     * 指定 GUI スロットがメニュー機能ボタンか判定する。
     */
    public boolean isFeatureSlot(int guiSlot) {
        return featureSlots.containsKey(guiSlot);
    }

    /**
     * 指定 GUI スロットが装飾専用アイテム（区切り線）か判定する。
     */
    public boolean isDecorationSlot(int guiSlot) {
        return layout.isDecorationSlot(guiSlot);
    }

    /**
     * 指定 GUI スロットのメニュー機能を取得する。
     *
     * @return 機能定義。ボタンでない場合は {@code null}
     */
    public MenuFeature featureAt(int guiSlot) {
        return featureSlots.get(guiSlot);
    }

    /* ------------------------------------------------------------------
     * 描画
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーデータの内容を GUI へ反映する。
     * <p>
     * 前回の表示が残らないようインベントリを空にしてから、装飾アイテム・仮想装備枠・
     * メニュー機能ボタン・プレイヤー情報ヘッド・閉じるボタンだけを描画する。
     *
     * @param player 描画対象プレイヤー（機能ボタンの権限判定に使用）
     * @param data   プレイヤーデータ
     */
    public void render(Player player, PlayerData data) {
        Objects.requireNonNull(data, "data");

        inventory.clear();

        renderDecorations();
        renderEquipment(data);
        renderFeatures(player, data);
        renderPlayerHead(player, data);
        renderCloseButton();
    }

    /**
     * 装飾専用アイテム（区切り線）を描画する。
     * <p>
     * 表示専用で、クリックしても何も起きない（{@link MenuService} 側で無視する）。
     */
    private void renderDecorations() {
        for (Map.Entry<Integer, String> entry : layout.decorations().entrySet()) {
            int guiSlot = entry.getKey();
            if (!isValidSlot(guiSlot)) {
                continue;
            }
            Material material = matchMaterial(entry.getValue());
            inventory.setItem(guiSlot, new ItemStack(
                    material == null ? Material.LIGHT : material));
        }
    }

    /**
     * 仮想装備枠を描画する。
     */
    private void renderEquipment(PlayerData data) {
        for (Map.Entry<Integer, EquipSlot> entry : equipmentSlots.entrySet()) {
            int guiSlot = entry.getKey();
            if (!isValidSlot(guiSlot)) {
                continue;
            }

            EquipSlot equipSlot = entry.getValue();
            ItemStack equipped = data.getEquipment(equipSlot);
            if (equipped == null) {
                // 空き枠は枠アイコン（道具枠は額縁、それ以外はバリア）で示す。
                inventory.setItem(guiSlot, createEmptySlotItem(equipSlot));
            } else {
                inventory.setItem(guiSlot, decorateEquipped(equipped, equipSlot));
            }
        }
    }

    /**
     * メニュー機能ボタンを描画する。
     * <p>
     * 権限を持たない機能は表示しない。ここでは表示のみを担当し、
     * クリック時の実処理は {@link MenuService#handleFeatureClick} が担う。
     */
    private void renderFeatures(Player player, PlayerData data) {
        for (Map.Entry<Integer, MenuFeature> entry : featureSlots.entrySet()) {
            int guiSlot = entry.getKey();
            MenuFeature feature = entry.getValue();
            if (!isValidSlot(guiSlot) || !feature.isAvailableTo(player)) {
                continue;
            }

            // 設定トグルは ON / OFF に応じてアイコンと Lore を動的に切り替える。
            if (renderSettingFeature(guiSlot, feature, data)) {
                continue;
            }

            GuiIcon icon = layout.icon(feature.icon());
            ItemStack item = icon == null
                    ? simpleItem(FEATURE_FALLBACK_MATERIAL,
                    "<!italic><white>" + feature.id() + "</white>")
                    : icon.createItem(Map.of("id", feature.id(), "action", feature.action()));
            inventory.setItem(guiSlot, item);
        }
    }

    /**
     * レベルアップ通知設定のトグルボタンを描画する。
     *
     * @return 設定トグルとして描画した場合は {@code true}
     */
    private boolean renderSettingFeature(int guiSlot, MenuFeature feature, PlayerData data) {
        String action = feature.action().toLowerCase(Locale.ROOT);
        boolean self = ACTION_CHAT_LOG_SELF.equals(action);
        boolean others = ACTION_CHAT_LOG_OTHERS.equals(action);
        if (!self && !others) {
            return false;
        }

        PlayerSettings settings = data.getSettings();
        boolean enabled = self ? settings.chatLogSelf() : settings.chatLogOthers();
        ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String title = self ? "自分のレベルアップログ" : "他人のレベルアップログ";
            meta.displayName(MINI.deserialize("<!italic><white>" + title + ": "
                    + (enabled ? "<green>ON</green>" : "<red>OFF</red>")));
            meta.lore(List.of(
                    MINI.deserialize("<!italic><gray>クリックで ON / OFF を切り替えます</gray>"),
                    MINI.deserialize("<!italic><dark_gray>"
                            + (self
                            ? "自分のレベルアップ通知をチャットに表示します"
                            : "他のプレイヤーのレベルアップ通知をチャットに表示します")
                            + "</dark_gray>")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        inventory.setItem(guiSlot, item);
        return true;
    }

    /**
     * 右上にプレイヤー情報ヘッドを描画する。
     * <p>
     * 表示専用で、クリックしても何も起きない。レベル・経験値・HP / MP と
     * 主要な RPG ステータスを Lore にまとめる。
     */
    private void renderPlayerHead(Player player, PlayerData data) {
        int guiSlot = layout.playerHeadSlot();
        if (!isValidSlot(guiSlot) || player == null) {
            return;
        }

        PlayerStats stats = data.getStats();
        int classLevel = data.getCurrentClassLevel();
        long classExp = data.getCurrentClassExp();
        boolean maxLevel = levelService != null && classLevel >= levelService.maxLevel();
        long requiredExp = classLevel <= 0 || levelService == null || maxLevel
                ? 0L
                : levelService.requiredExp(classLevel);
        String expDisplay = maxLevel
                ? "<gold>MAX</gold>"
                : "<white>" + formatLong(classExp) + "</white>/<white>"
                        + formatLong(requiredExp) + "</white>";

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta instanceof SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(player);
            }
            meta.displayName(MINI.deserialize("<!italic><white>" + player.getName() + "</white>"));
            List<Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize("<!italic><gray>レベル: <yellow>" + classLevel
                    + "</yellow> <dark_gray>|</dark_gray> <gray>EXP: "
                    + expDisplay + "</gray>"));
            Component currentClass = classManager == null
                    ? MINI.deserialize("<!italic><gray>未選択</gray>")
                    : classManager.current(data.getClassData())
                            .map(definition -> definition.displayName())
                            .orElseGet(() -> MINI.deserialize("<!italic><gray>未選択</gray>"));
            lore.add(MINI.deserialize("<!italic><gray>クラス: </gray>").append(currentClass));
            lore.add(MINI.deserialize("<!italic><gray>HP: <red>" + formatDouble(stats.getCurrentHealth())
                    + "</red>/<white>" + formatDouble(stats.getMaxHealth()) + "</white>"
                    + " <dark_gray>|</dark_gray> <gray>MP: <blue>"
                    + formatDouble(stats.getCurrentMana()) + "</blue>/<white>"
                    + formatDouble(stats.getMaxMana()) + "</white></gray>"));
            lore.add(MINI.deserialize("<!italic><gray>攻撃力: <white>"
                    + formatDouble(stats.getAttackPower()) + "</white> <dark_gray>|</dark_gray> "
                    + "<gray>防御力: <white>" + formatDouble(stats.getDefense2()) + "</white></gray>"));
            lore.add(MINI.deserialize("<!italic><gray>会心率: <white>"
                    + formatDouble(stats.getCriticalRate()) + "%</white> <dark_gray>|</dark_gray> "
                    + "<gray>回避率: <white>" + formatDouble(stats.getEvasion())
                    + "%</white></gray>"));
            lore.add(MINI.deserialize("<!italic><gray>移動速度: <white>"
                    + formatDouble(stats.getMoveSpeed()
                    / PlayerStats.DEFAULT_MOVE_SPEED * 100.0)
                    + "%</white></gray>"));
            appendClassLore(lore, data);
            appendGatheringLore(lore, data);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        inventory.setItem(guiSlot, item);
    }

    /**
     * 全クラスのレベル・進捗率を Lore へ追記する。
     * <p>
     * {@link ClassManager#ids()} を ID 昇順に並べ、未進行のクラスも
     * レベル 1・進捗 0% として表示する。最大レベル到達時は {@code (MAX)} を表示する。
     * ギャザリング表示との区切りとして、先頭に空行を入れる。
     */
    private void appendClassLore(List<Component> lore, PlayerData data) {
        if (classManager == null) {
            return;
        }
        List<String> classIds = new ArrayList<>(classManager.ids());
        if (classIds.isEmpty()) {
            return;
        }
        Collections.sort(classIds);
        lore.add(Component.empty());

        for (String classId : classIds) {
            int level = data.getClassLevel(classId);
            String progress;
            if (levelService == null) {
                progress = "0%";
            } else if (level >= levelService.maxLevel()) {
                progress = "MAX";
            } else {
                long required = levelService.requiredExp(level);
                long percent = required <= 0L
                        ? 100L
                        : Math.round((double) data.getClassExp(classId) / (double) required * 100.0);
                progress = Math.max(0L, Math.min(100L, percent)) + "%";
            }
            lore.add(MINI.deserialize("<!italic><gray>"
                    + classId.toLowerCase(Locale.ROOT)
                    + " </gray><yellow>Lv." + level + "</yellow> <white>("
                    + progress + ")</white>"));
        }
    }

    /**
     * 採取レベル・進捗率を Lore の末尾へ追記する。
     * <p>
     * 最大レベル到達時は進捗率の代わりに {@code (MAX)} を表示する。
     * ギャザリングサービスが未初期化の場合は何も追記しない。
     */
    private void appendGatheringLore(List<Component> lore, PlayerData data) {
        if (gatheringService == null) {
            return;
        }
        lore.add(Component.empty());
        for (GatheringType type : GatheringType.values()) {
            int level = data.getGatheringLevel(type);
            String progress = level >= gatheringService.maxLevel(type)
                    ? "MAX"
                    : gatheringService.getProgressPercent(data, type) + "%";
            lore.add(MINI.deserialize("<!italic><gray>" + type.displayName()
                    + "</gray> <yellow>Lv." + level + "</yellow> <white>("
                    + progress + ")</white>"));
        }
    }

    private static String formatLong(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    /**
     * 閉じるボタンを描画する。
     */
    private void renderCloseButton() {
        setIfValid(layout.closeSlot(), icon("close", Map.of(),
                Material.BARRIER, "<!italic><red>閉じる</red>"));
    }

    /* ------------------------------------------------------------------
     * アイテム生成ヘルパ
     * ------------------------------------------------------------------ */

    /**
     * 空の装備枠に表示するダミーアイテムを生成する。
     */
    private ItemStack createEmptySlotItem(EquipSlot slot) {
        boolean toolSlot = slot != null && slot.isToolSlot();
        ItemStack item = new ItemStack(toolSlot ? Material.ITEM_FRAME : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(toolSlot
                    ? MINI.deserialize("<!italic><gray>" + slot.getDisplayName() + "</gray>")
                    : MINI.deserialize("<!italic><gray>空きスロット</gray>"));
            meta.lore(List.of(MINI.deserialize(toolSlot
                    ? "<!italic><dark_gray>採取ツールを装着できます</dark_gray>"
                    : "<!italic><dark_gray>装備するとここに表示されます</dark_gray>")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 装備中のアイテムに外し方のヒントを付与した表示用コピーを返す。
     */
    private ItemStack decorateEquipped(ItemStack item, EquipSlot slot) {
        ItemStack display = item.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return display;
        }

        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(MINI.deserialize("<!italic><dark_gray>" + slot.getDisplayName() + "</dark_gray>"));
        lore.add(MINI.deserialize("<!italic><gray>クリックで外す</gray>"));
        lore.add(MINI.deserialize("<!italic><dark_gray>カーソル装備でクリックすると入れ替え</dark_gray>"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    /**
     * 設定されたアイコンを生成する。未定義の場合は代替アイテムを返す。
     */
    private ItemStack icon(String key, Map<String, String> placeholders,
                           Material fallbackMaterial, String fallbackName) {
        GuiIcon icon = layout.icon(key);
        if (icon != null) {
            return icon.createItem(placeholders);
        }
        return simpleItem(fallbackMaterial, fallbackName);
    }

    /**
     * Material 名を解決する。不正な名前・アイテム以外の場合は {@code null} を返す。
     */
    private static Material matchMaterial(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(name.trim());
        return material == null || !material.isItem() ? null : material;
    }

    private ItemStack simpleItem(Material material, String displayName) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextComponents.mini(MINI, displayName));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void setIfValid(int guiSlot, ItemStack item) {
        if (isValidSlot(guiSlot)) {
            inventory.setItem(guiSlot, item);
        }
    }

    private boolean isValidSlot(int guiSlot) {
        return guiSlot >= 0 && guiSlot < inventory.getSize();
    }

    /* ------------------------------------------------------------------
     * マップ構築
     * ------------------------------------------------------------------ */

    private static Map<Integer, EquipSlot> buildEquipmentSlots(
            MenuLayout layout, InventorySettings settings) {
        Map<Integer, EquipSlot> result = new TreeMap<>();

        // menu.yml の equipment_slots を優先する。
        for (EquipSlot slot : EquipSlot.values()) {
            int guiSlot = layout.equipmentSlot(slot);
            if (guiSlot >= 0) {
                result.putIfAbsent(guiSlot, slot);
            }
        }

        // menu.yml に装備枠定義が無い場合は inventory.yml（既定値）を使う。
        if (result.isEmpty() && settings != null) {
            for (EquipSlot slot : EquipSlot.values()) {
                int guiSlot = settings.equipmentSlot(slot);
                if (guiSlot >= 0) {
                    result.putIfAbsent(guiSlot, slot);
                }
            }
        }

        // 旧 config に道具枠の定義が無い場合でも、下段のコンパス（閉じるボタン）の
        // 左隣 2 枠を採取ツール専用枠として確保する（既存の装備枠とは重複させない）。
        int closeSlot = layout.closeSlot();
        int toolBase = closeSlot > 1 ? closeSlot - 2 : 51;
        EquipSlot[] toolSlots = {EquipSlot.TOOL_1, EquipSlot.TOOL_2};
        for (int index = 0; index < toolSlots.length; index++) {
            EquipSlot toolSlot = toolSlots[index];
            if (result.containsValue(toolSlot)) {
                continue;
            }
            int candidate = toolBase + index;
            while (candidate >= 0 && result.containsKey(candidate)) {
                candidate++;
            }
            if (candidate >= 0) {
                result.putIfAbsent(candidate, toolSlot);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private static Map<Integer, MenuFeature> buildFeatureSlots(MenuLayout layout) {
        Map<Integer, MenuFeature> result = new TreeMap<>();
        for (MenuFeature feature : layout.features()) {
            if (feature.slot() >= 0) {
                result.putIfAbsent(feature.slot(), feature);
            }
        }
        // トグルボタンを含め、ボタンの配置は menu.yml の定義だけに従う。
        // 未定義のボタンを空きスロットへ自動配置することはしない。
        return Collections.unmodifiableMap(result);
    }

    /* ------------------------------------------------------------------
     * アクセサ
     * ------------------------------------------------------------------ */

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * この GUI を開いているプレイヤーの UUID を取得する。
     */
    public UUID playerId() {
        return playerId;
    }

    /**
     * レイアウト設定を取得する。
     */
    public MenuLayout layout() {
        return layout;
    }
}
