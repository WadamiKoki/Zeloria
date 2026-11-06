package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import com.zeloria.item.EquipmentService;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerService;
import com.zeloria.player.PlayerStatsService;
import com.zeloria.skill.ClassManager;
import com.zeloria.player.PlayerClassData;
import com.zeloria.player.ZeloriaClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code config/gui/class_select.yml} を読み込み、クラス選択 GUI を生成する。
 * <p>
 * GUI のタイトル・クラスアイコンの表示名・状態テキストはすべて Adventure の
 * {@link Component} で扱い、MiniMessage を YAML から解決する。
 * クラス ID はアイテムの {@code PersistentDataContainer}（PDC）へ格納し、
 * displayName / lore をデータ判定に使用しない。
 * <p>
 * 描画・クリック処理はメインスレッドで行い、{@link InventoryHolder} によって
 * この GUI のインベントリだけを対象に判定する。
 */
public final class ClassSelectGUI implements Listener {

    /** GUI 設定ファイル（{@code config/} からの相対パス）。 */
    private static final String CONFIG_PATH = "gui/class_select.yml";

    /** クラス ID を格納する PDC キー。 */
    private static final String PDC_CLASS_ID = "class_id";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final int DEFAULT_ROWS = 5;
    private static final int MAX_ROWS = 6;

    private static final String DEFAULT_TITLE =
            "<gradient:#00c6ff:#0072ff><bold>Zeloria</bold></gradient>"
                    + " <dark_gray>|</dark_gray> <white>クラス選択</white>";
    private static final String DEFAULT_SELECTED = "<green>▶ 選択中</green>";
    private static final String DEFAULT_UNLOCKED = "<yellow>クリックでこのクラスに切り替えます</yellow>";
    private static final String DEFAULT_LOCKED = "<red>未解放</red>";

    private static final GuiSound DEFAULT_OPEN =
            new GuiSound("block.ender_chest.open", 0.8f, 1.2f);
    private static final GuiSound DEFAULT_CLICK =
            new GuiSound("ui.button.click", 0.7f, 1.4f);
    private static final GuiSound DEFAULT_ERROR =
            new GuiSound("entity.villager.no", 0.8f, 1.0f);

    /** クラスアイコンの下に並べる成長率表示アイテムの既定 Material（アイテムフレーム）。 */
    private static final Material DEFAULT_GROWTH_MATERIAL = Material.ITEM_FRAME;

    private static final String DEFAULT_GROWTH_NAME =
            "<light_purple>{class}</light_purple> <gray>のレベル成長率</gray>";
    private static final String DEFAULT_GROWTH_LINE =
            "<gray>{label}</gray> <dark_gray>+</dark_gray> <aqua>{value}%</aqua>"
                    + " <gray>/ Lv</gray>";
    private static final String DEFAULT_GROWTH_EMPTY =
            "<dark_gray>成長率は設定されていません。</dark_gray>";

    private final Zeloria plugin;
    private final ClassManager classManager;
    private final NamespacedKey classIdKey;

    /** 現在のレイアウト設定（差し替え式）。 */
    private volatile Layout layout;

    public ClassSelectGUI(Zeloria plugin, ClassManager classManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.classManager = Objects.requireNonNull(classManager, "classManager");
        this.classIdKey = new NamespacedKey(plugin, PDC_CLASS_ID);
        reload();
    }

    /**
     * GUI 設定を再読み込みする（{@code /admin reload} 用）。
     */
    public void reload() {
        FileConfiguration config = plugin.getYamlLoader() == null
                ? null
                : plugin.getYamlLoader().getConfig(CONFIG_PATH);
        this.layout = parse(config);
    }

    /**
     * YAML 設定を読み取り、レイアウトへ変換する。
     */
    private Layout parse(FileConfiguration config) {
        if (config == null) {
            plugin.getLogger().warning("GUI 設定が見つかりません: " + CONFIG_PATH
                    + " - 既定レイアウトを使用します。");
            return defaults();
        }

        ConfigurationSection gui = config.getConfigurationSection("gui");
        String titleRaw = gui == null ? config.getString("title") : gui.getString("title");
        Component title = MINI.deserialize(
                titleRaw == null || titleRaw.isBlank() ? DEFAULT_TITLE : titleRaw);

        int rows = gui == null
                ? config.getInt("rows", DEFAULT_ROWS)
                : gui.getInt("rows", DEFAULT_ROWS);
        rows = Math.max(1, Math.min(MAX_ROWS, rows));

        Map<String, Integer> classSlots = new LinkedHashMap<>();
        ConfigurationSection classes = config.getConfigurationSection("classes");
        if (classes != null) {
            for (String key : classes.getKeys(false)) {
                String id = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) {
                    continue;
                }
                int slot = classes.getInt(key, -1);
                if (slot < 0) {
                    plugin.getLogger().warning("クラス '" + id + "' のスロットが不正です: " + slot);
                    continue;
                }
                classSlots.put(id, slot);
            }
        }

        ConfigurationSection sounds = config.getConfigurationSection("sounds");
        GuiSound openSound = GuiSound.from(section(sounds, "open"), DEFAULT_OPEN);
        GuiSound clickSound = GuiSound.from(section(sounds, "click"), DEFAULT_CLICK);
        GuiSound errorSound = GuiSound.from(section(sounds, "error"), DEFAULT_ERROR);

        ConfigurationSection status = config.getConfigurationSection("status");
        Component selectedLore = parseStatus(status, "selected", DEFAULT_SELECTED);
        Component unlockedLore = parseStatus(status, "unlocked", DEFAULT_UNLOCKED);
        Component lockedLore = parseStatus(status, "locked", DEFAULT_LOCKED);

        GrowthDisplay growth = parseGrowth(config.getConfigurationSection("growth"));

        return new Layout(title, rows, Map.copyOf(classSlots),
                openSound, clickSound, errorSound,
                selectedLore, unlockedLore, lockedLore, growth);
    }

    private static Layout defaults() {
        return new Layout(
                MINI.deserialize(DEFAULT_TITLE),
                DEFAULT_ROWS,
                Map.of(),
                DEFAULT_OPEN,
                DEFAULT_CLICK,
                DEFAULT_ERROR,
                MINI.deserialize(DEFAULT_SELECTED),
                MINI.deserialize(DEFAULT_UNLOCKED),
                MINI.deserialize(DEFAULT_LOCKED),
                GrowthDisplay.defaults());
    }

    /**
     * 成長率表示（クラスアイコンの下に並べるアイテムフレーム）の設定を読み取る。
     * <p>
     * セクションが無い場合は既定値（アイテムフレーム＋主要ステータス）を使用する。
     */
    private GrowthDisplay parseGrowth(ConfigurationSection section) {
        if (section == null) {
            return GrowthDisplay.defaults();
        }

        boolean enabled = section.getBoolean("enabled", true);
        Material material = DEFAULT_GROWTH_MATERIAL;
        String rawMaterial = section.getString("material");
        if (rawMaterial != null && !rawMaterial.isBlank()) {
            Material resolved = Material.matchMaterial(rawMaterial.trim().toUpperCase(Locale.ROOT));
            if (resolved == null || resolved.isAir()) {
                plugin.getLogger().warning(
                        "成長率表示の material が不正です: " + rawMaterial + " - 既定値を使用します。");
            } else {
                material = resolved;
            }
        }

        String name = section.getString("name", DEFAULT_GROWTH_NAME);
        String line = section.getString("line", DEFAULT_GROWTH_LINE);
        String empty = section.getString("empty", DEFAULT_GROWTH_EMPTY);

        List<StatDisplay> stats = new ArrayList<>();
        ConfigurationSection statsSection = section.getConfigurationSection("stats");
        if (statsSection != null) {
            for (String key : statsSection.getKeys(false)) {
                String label = statsSection.getString(key, key);
                if (key == null || key.isBlank()) {
                    continue;
                }
                stats.add(new StatDisplay(key.trim(), label == null ? key : label));
            }
        }
        if (stats.isEmpty()) {
            stats.addAll(GrowthDisplay.DEFAULT_STATS);
        }

        return new GrowthDisplay(enabled, material,
                name == null || name.isBlank() ? DEFAULT_GROWTH_NAME : name,
                line == null || line.isBlank() ? DEFAULT_GROWTH_LINE : line,
                empty == null || empty.isBlank() ? DEFAULT_GROWTH_EMPTY : empty,
                List.copyOf(stats));
    }

    private static ConfigurationSection section(ConfigurationSection parent, String child) {
        return parent == null ? null : parent.getConfigurationSection(child);
    }

    private static Component parseStatus(ConfigurationSection section, String key, String fallback) {
        String raw = section == null ? null : section.getString(key);
        return MINI.deserialize(raw == null || raw.isBlank() ? fallback : raw);
    }

    /**
     * GUI レイアウト設定。
     */
    private record Layout(
            Component title,
            int rows,
            Map<String, Integer> classSlots,
            GuiSound openSound,
            GuiSound clickSound,
            GuiSound errorSound,
            Component selectedLore,
            Component unlockedLore,
            Component lockedLore,
            GrowthDisplay growth
    ) {

        int size() {
            return rows * 9;
        }
    }

    /**
     * 成長率表示アイテム（アイテムフレーム）の設定。
     *
     * @param enabled  表示するか
     * @param material 表示に使用する Material
     * @param name     表示名（MiniMessage、{@code {class}} はクラス表示名に置換）
     * @param line     1 ステータス分の lore（MiniMessage、{@code {label}} / {@code {value}} を置換）
     * @param empty    成長率がすべて 0 のときの lore（MiniMessage）
     * @param stats    表示するステータス（上から順に lore へ並ぶ）
     */
    private record GrowthDisplay(
            boolean enabled,
            Material material,
            String name,
            String line,
            String empty,
            List<StatDisplay> stats
    ) {

        /** YAML に {@code growth.stats} が無い場合に表示する既定ステータス。 */
        static final List<StatDisplay> DEFAULT_STATS = List.of(
                new StatDisplay("maxHealth", "最大HP"),
                new StatDisplay("maxMana", "最大MP"),
                new StatDisplay("attackPower", "攻撃力"),
                new StatDisplay("criticalRate", "クリティカル率"),
                new StatDisplay("accuracy", "命中率"),
                new StatDisplay("evasion", "回避率"));

        static GrowthDisplay defaults() {
            return new GrowthDisplay(true, DEFAULT_GROWTH_MATERIAL,
                    DEFAULT_GROWTH_NAME, DEFAULT_GROWTH_LINE, DEFAULT_GROWTH_EMPTY,
                    DEFAULT_STATS);
        }
    }

    /**
     * 成長率表示の 1 行分（ステータスキーと表示ラベル）。
     *
     * @param key   {@code levelUpPercent} のキー名
     * @param label 表示ラベル
     */
    private record StatDisplay(String key, String label) {
    }

    /* ------------------------------------------------------------------
     * 開く・描画
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーにクラス選択 GUI を開く。
     * <p>
     * データが未キャッシュの場合は非同期読み込みを開始し、完了後に
     * メインスレッドで GUI を開く。
     *
     * @param player 対象プレイヤー
     */
    public void open(Player player) {
        Objects.requireNonNull(player, "player");

        PlayerData cached = PlayerData.getCached(player.getUniqueId());
        if (cached != null) {
            openNow(player, cached);
            return;
        }

        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            player.sendActionBar(Component.text(
                    "プレイヤーデータが読み込まれていません。", NamedTextColor.RED));
            return;
        }

        playerService.loadIfAbsent(player)
                .thenAccept(data -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        openNow(player, data);
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "クラス選択用のプレイヤーデータ読み込みに失敗しました: "
                                    + player.getName() + " (" + rootMessage(throwable) + ")");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendActionBar(Component.text(
                                    "プレイヤーデータの読み込みに失敗しました。", NamedTextColor.RED));
                        }
                    });
                    return null;
                });
    }

    /**
     * キャッシュ済みデータを用いて GUI を生成・表示する（メインスレッド専用）。
     */
    private void openNow(Player player, PlayerData data) {
        if (!player.isOnline()) {
            return;
        }

        Layout current = layout;
        PlayerClassData classData = data.getClassData();
        classManager.applyDefaultUnlocks(classData);

        Holder holder = new Holder(this);
        Inventory inventory = Bukkit.createInventory(holder, current.size(), current.title());
        holder.inventory = inventory;
        render(inventory, classData, current);

        player.openInventory(inventory);
        current.openSound().play(player);
    }

    /**
     * クラスアイコンのみを描画する。
     * <p>
     * 背景ガラスは敷かない（Geyser / Bedrock でアイコンをタップしやすいようにするため）。
     */
    private void render(Inventory inventory, PlayerClassData classData, Layout current) {
        inventory.clear();

        Set<Integer> used = new HashSet<>(current.classSlots().values());
        Map<Integer, ZeloriaClass> placed = new LinkedHashMap<>();
        int autoSlot = 10;

        for (ZeloriaClass definition : classManager.all()) {
            Integer configured = current.classSlots().get(definition.id());
            int slot;
            if (configured != null) {
                slot = configured;
            } else {
                while (autoSlot < inventory.getSize() && used.contains(autoSlot)) {
                    autoSlot += 2;
                }
                slot = autoSlot;
                used.add(slot);
                autoSlot += 2;
            }

            if (slot < 0 || slot >= inventory.getSize()) {
                plugin.getLogger().warning("クラス '" + definition.id() + "' のスロット "
                        + slot + " は GUI サイズ " + inventory.getSize() + " の範囲外です。");
                continue;
            }
            inventory.setItem(slot, createClassItem(definition, classData, current));
            placed.putIfAbsent(slot, definition);
            used.add(slot);
        }

        // 成長率表示はクラスアイコンをすべて配置した後に置き、アイコンを上書きしないようにする。
        if (!current.growth().enabled()) {
            return;
        }
        for (Map.Entry<Integer, ZeloriaClass> entry : placed.entrySet()) {
            placeGrowthItem(inventory, entry.getValue(), entry.getKey(), used, current.growth());
        }
    }

    /**
     * クラスアイコンの 1 行下（{@code アイコンのスロット + 9}）へ成長率表示アイテムを配置する。
     * <p>
     * 最下段のクラスアイコンには下段が存在しないため、その場合は表示を省略する。
     * 他のクラスアイコン／成長率表示とスロットが重複する場合も省略し、警告を残す。
     */
    private void placeGrowthItem(Inventory inventory, ZeloriaClass definition, int iconSlot,
                                 Set<Integer> used, GrowthDisplay growth) {
        int frameSlot = iconSlot + 9;
        if (frameSlot >= inventory.getSize()) {
            return;
        }
        if (used.contains(frameSlot)) {
            plugin.getLogger().warning("クラス '" + definition.id() + "' の成長率表示スロット "
                    + frameSlot + " は他の表示と重複するため省略します。");
            return;
        }
        used.add(frameSlot);
        inventory.setItem(frameSlot, createGrowthItem(definition, growth));
    }

    /**
     * 成長率表示アイテム（アイテムフレーム）を生成する。
     * <p>
     * lore には {@code levelUpPercent} の値が 0 以外のステータスだけを、
     * {@code growth.stats} の並び順で表示する。すべて 0 の場合は
     * {@code growth.empty} の文言を 1 行表示する。
     */
    private ItemStack createGrowthItem(ZeloriaClass definition, GrowthDisplay growth) {
        ItemStack item = new ItemStack(growth.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        Component name = MINI.deserialize(growth.name());
        meta.displayName(name.replaceText(builder -> builder
                .matchLiteral("{class}")
                .replacement(definition.displayName())));

        List<Component> lore = new ArrayList<>();
        for (StatDisplay stat : growth.stats()) {
            double percent = definition.levelUpPercent().valueOfKey(stat.key());
            if (percent == 0.0) {
                continue;
            }
            lore.add(MINI.deserialize(growth.line()
                    .replace("{label}", stat.label())
                    .replace("{value}", formatPercent(percent))));
        }
        if (lore.isEmpty()) {
            lore.add(MINI.deserialize(growth.empty()));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 成長率（%）を表示用の文字列へ整形する（整数なら小数点以下を省略）。
     */
    private static String formatPercent(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * クラス 1 件分の表示アイテムを生成する。
     */
    private ItemStack createClassItem(ZeloriaClass definition,
                                      PlayerClassData classData,
                                      Layout current) {
        ItemStack item = new ItemStack(definition.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(definition.displayName());
        List<Component> lore = new ArrayList<>(definition.description());
        lore.add(Component.empty());

        String currentId = classData == null ? null : classData.currentClassIdOrNull();
        if (definition.id().equals(currentId)) {
            lore.add(current.selectedLore());
        } else if (classData != null && classData.isUnlocked(definition.id())) {
            lore.add(current.unlockedLore());
        } else {
            lore.add(current.lockedLore());
        }

        meta.lore(lore);
        meta.getPersistentDataContainer().set(classIdKey, PersistentDataType.STRING, definition.id());
        item.setItemMeta(meta);
        return item;
    }

    /* ------------------------------------------------------------------
     * イベント処理
     * ------------------------------------------------------------------ */

    /**
     * クラス選択 GUI のクリックを処理する。
     * <p>
     * 上部 GUI のクリックはすべてキャンセルし、PDC に格納されたクラス ID のみを
     * 判定材料にする。プレイヤーインベントリ側の操作は、GUI への移動を伴う
     * シフトクリック（{@link InventoryAction#MOVE_TO_OTHER_INVENTORY}）だけを拒否する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        Holder holder = resolveHolder(event.getView().getTopInventory());
        if (holder == null || holder.gui != this) {
            return;
        }

        // ダブルクリックによる表示用アイテムの一括回収を防ぐ。
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String classId = readClassId(event.getCurrentItem());
        if (classId == null) {
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            layout.errorSound().play(player);
            player.sendActionBar(Component.text(
                    "プレイヤーデータが読み込まれていません。", NamedTextColor.RED));
            return;
        }

        // 装備中のアイテムは旧クラスの武器制限を持つ可能性があるため、
        // クラス変更前にすべて外してもらう。
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (equipmentService != null && equipmentService.hasEquipped(data)) {
            layout.errorSound().play(player);
            player.sendActionBar(Component.text(
                    "装備をすべて外してからクラスを変更してください", NamedTextColor.RED));
            return;
        }

        PlayerClassData classData = data.getClassData();
        Selection selection = select(classData, classId);
        Layout current = layout;

        switch (selection) {
            case SELECTED -> {
                current.clickSound().play(player);
                // クラス変更後の基礎ステータス（baseStats × レベル成長率）を即時反映する。
                PlayerStatsService statsService = plugin.getPlayerStatsService();
                if (statsService != null) {
                    statsService.apply(player, data);
                }
                classManager.find(classId).ifPresent(definition ->
                        player.sendActionBar(Component.empty()
                                .append(definition.displayName())
                                .append(Component.text(
                                        " にクラスチェンジしました。", NamedTextColor.GRAY))));
                render(top, classData, current);
            }
            case ALREADY -> {
                current.clickSound().play(player);
                classManager.find(classId).ifPresent(definition ->
                        player.sendActionBar(Component.empty()
                                .append(definition.displayName())
                                .append(Component.text(
                                        " はすでに選択されています。", NamedTextColor.GRAY))));
            }
            case LOCKED -> {
                current.errorSound().play(player);
                classManager.find(classId).ifPresent(definition ->
                        player.sendActionBar(Component.empty()
                                .append(definition.displayName())
                                .append(Component.text(
                                        " はまだ解放されていません。", NamedTextColor.RED))));
            }
            case UNKNOWN -> {
                current.errorSound().play(player);
                player.sendActionBar(Component.text(
                        "クラス定義が見つかりません: " + classId, NamedTextColor.RED));
            }
            case NO_DATA -> {
                current.errorSound().play(player);
                player.sendActionBar(Component.text(
                        "プレイヤーデータが読み込まれていません。", NamedTextColor.RED));
            }
        }
    }

    /**
     * クラス選択 GUI へのドラッグを拒否する。
     * <p>
     * プレイヤーインベントリ内で完結するドラッグは通常どおり許可する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        Holder holder = resolveHolder(event.getView().getTopInventory());
        if (holder == null || holder.gui != this) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * クラス選択の可否を判定し、可能なら現在のクラスを更新する。
     */
    private Selection select(PlayerClassData classData, String classId) {
        if (classData == null) {
            return Selection.NO_DATA;
        }
        if (classManager.find(classId).isEmpty()) {
            return Selection.UNKNOWN;
        }
        if (!classData.isUnlocked(classId)) {
            return Selection.LOCKED;
        }
        if (classId.equals(classData.currentClassIdOrNull())) {
            return Selection.ALREADY;
        }
        classData.setCurrentClassId(classId);
        return Selection.SELECTED;
    }

    private String readClassId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String stored = meta.getPersistentDataContainer().get(classIdKey, PersistentDataType.STRING);
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return stored.trim().toLowerCase(Locale.ROOT);
    }

    private static Holder resolveHolder(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder : null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    /**
     * この GUI のインベントリを識別するホルダー。
     */
    private static final class Holder implements InventoryHolder {

        private final ClassSelectGUI gui;
        private Inventory inventory;

        private Holder(ClassSelectGUI gui) {
            this.gui = gui;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * クラス選択操作の結果。
     */
    private enum Selection {
        SELECTED,
        ALREADY,
        LOCKED,
        UNKNOWN,
        NO_DATA
    }
}
