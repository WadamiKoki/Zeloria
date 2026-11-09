package com.zeloria.gui;

import com.zeloria.core.ConfigCache;
import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.player.EquipSlot;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code config/GuiData/menu.yml} / {@code inventory.yml} / {@code class_tree.yml} を
 * 解析するサービス。
 * <p>
 * {@link YamlLoader} が読み込んだ YAML から GUI 関連の設定を取り出し、
 * {@link MenuLayout}（{@code /menu} の装備枠・メニュー機能・効果音）と
 * {@link InventorySettings}（装備枠の配置・効果音・表示用マテリアル・固定表示名）、
 * {@link ClassTreeSettings}（クラスツリーの表示・効果音）へ変換して保持する。
 * {@code /admin reload} 時は {@link #reload()} を呼ぶことで最新の設定を反映できる。
 */
public class GuiConfigService {

    /** {@code config/} からの相対キー。 */
    private static final String MENU_KEY = "GuiData/menu.yml";
    private static final String INVENTORY_KEY = "GuiData/inventory.yml";
    private static final String CLASS_TREE_KEY = "GuiData/class_tree.yml";

    /** 同梱テンプレート（jar 内）の {@code menu.yml} パス。 */
    private static final String MENU_RESOURCE = "config/" + MENU_KEY;

    /**
     * 同梱テンプレートとサーバー設定の差分を確認するキー。
     * <p>
     * ここに挙げたキーがずれていると、ツール枠の位置がサーバーごとに食い違う。
     * 起動時に同梱テンプレートの値へ書き換えて保存する。
     */
    private static final List<String> MENU_DRIFT_KEYS = List.of(
            "equipment_slots.tool_1", "equipment_slots.tool_2");

    /** 差分チェック時に「未設定」を表す番兵値。 */
    private static final int MISSING_SLOT = Integer.MIN_VALUE;

    /** {@code inventory.yml} 内の効果音パス。 */
    private static final String INVENTORY_SOUNDS_PATH = "sounds";

    /** {@code inventory.yml} 内の表示用マテリアルパス。 */
    private static final String INVENTORY_MATERIALS_PATH = "materials";

    /** {@code inventory.yml} 内の固定表示名パス。 */
    private static final String INVENTORY_NAMES_PATH = "names";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;
    private final ConfigCache cache;

    private volatile MenuLayout menuLayout;
    private volatile InventorySettings inventorySettings;
    private volatile ClassTreeSettings classTreeSettings;

    public GuiConfigService(Zeloria plugin, YamlLoader yamlLoader, ConfigCache cache) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        this.cache = Objects.requireNonNull(cache, "cache");
        reload();
    }

    /**
     * GUI 設定を再読み込みする。
     * <p>
     * ファイルが存在しない・解析に失敗した場合は既定値で動作を継続する。
     */
    public synchronized void reload() {
        MenuLayout fallbackMenu = MenuLayout.defaults();
        InventorySettings fallbackInventory = InventorySettings.defaults();
        ClassTreeSettings fallbackClassTree = ClassTreeSettings.defaults();

        FileConfiguration menuConfig = yamlLoader.getConfig(MENU_KEY);
        // サーバー側のツール枠が同梱テンプレートとずれている場合は、解析前に同梱値へ同期する。
        syncMenuToolSlots(menuConfig);

        FileConfiguration inventoryConfig = yamlLoader.getConfig(INVENTORY_KEY);
        FileConfiguration classTreeConfig = yamlLoader.getConfig(CLASS_TREE_KEY);

        MenuLayout parsedMenu = parseMenu(menuConfig, fallbackMenu);
        InventorySettings parsedInventory = parseInventory(inventoryConfig, fallbackInventory);
        ClassTreeSettings parsedClassTree = parseClassTree(classTreeConfig, fallbackClassTree);
        this.menuLayout = parsedMenu;
        this.inventorySettings = parsedInventory;
        this.classTreeSettings = parsedClassTree;

        plugin.getLogger().info("GUI 設定を読み込みました (menu: "
                + (menuConfig == null ? "default" : "custom")
                + ", inventory: " + (inventoryConfig == null ? "default" : "custom")
                + ", class_tree: " + (classTreeConfig == null ? "default" : "custom")
                + ").");
    }

    /**
     * サーバー側 {@code menu.yml} のツール枠（{@code equipment_slots.tool_1 / tool_2}）を
     * 同梱テンプレートの値へ同期する。
     * <p>
     * 値が異なる場合のみ、同梱テンプレートの値（24 / 6）を書き込んで保存する。
     * 他のキーには触れないため、サーバー側のカスタマイズは保持される。
     *
     * @param serverConfig サーバー側の {@code menu.yml}（未配置の場合は {@code null}）
     */
    private void syncMenuToolSlots(FileConfiguration serverConfig) {
        if (serverConfig == null) {
            return;
        }
        FileConfiguration bundled = loadBundledMenu();
        if (bundled == null) {
            return;
        }

        List<String> applied = new ArrayList<>();
        for (String path : MENU_DRIFT_KEYS) {
            int expected = bundled.getInt(path, MISSING_SLOT);
            if (expected == MISSING_SLOT) {
                continue;
            }
            int actual = serverConfig.getInt(path, MISSING_SLOT);
            if (expected == actual) {
                continue;
            }
            serverConfig.set(path, expected);
            applied.add(path + " (" + describeSlot(actual) + " → " + expected + ")");
        }

        // 廃止したオフハンド枠がサーバー設定に残っている場合は削除する。
        String offhandPath = "equipment_slots.offhand";
        if (serverConfig.contains(offhandPath)) {
            serverConfig.set(offhandPath, null);
            applied.add(offhandPath + " (削除)");
        }

        if (applied.isEmpty()) {
            return;
        }

        Path file = yamlLoader.getConfigFolder().resolve(MENU_KEY);
        try {
            serverConfig.save(file.toFile());
            plugin.getLogger().info("config/GuiData/menu.yml の装備枠を同梱テンプレートの値へ更新しました: "
                    + String.join(", ", applied));
        } catch (IOException e) {
            plugin.getLogger().warning("config/GuiData/menu.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    /**
     * jar に同梱された {@code menu.yml} を読み込む。
     *
     * @return 同梱設定。リソースが無い・読み込みに失敗した場合は {@code null}
     */
    private FileConfiguration loadBundledMenu() {
        try (InputStream stream = plugin.getResource(MENU_RESOURCE)) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("同梱の menu.yml を読み込めませんでした: " + e.getMessage());
            return null;
        }
    }

    private static String describeSlot(int slot) {
        return slot == MISSING_SLOT ? "(未設定)" : String.valueOf(slot);
    }

    /**
     * {@code /menu} GUI のレイアウト設定を取得する。
     */
    public MenuLayout getMenu() {
        return menuLayout;
    }

    /**
     * 装備設定を取得する。
     */
    public InventorySettings getInventory() {
        return inventorySettings;
    }

    /**
     * クラスツリー GUI の設定を取得する。
     */
    public ClassTreeSettings getClassTree() {
        return classTreeSettings;
    }

    /**
     * 指定名の効果音を再生する。
     * <p>
     * {@code menu.yml} の {@code sounds} を優先し、未定義の場合は
     * {@code inventory.yml} の {@code sounds} へフォールバックする。
     *
     * @param player 対象プレイヤー
     * @param name   効果音名（例: {@code open}, {@code click}, {@code equip}）
     */
    public void playSound(Player player, String name) {
        if (player == null || name == null) {
            return;
        }

        GuiSound sound = menuLayout.sound(name);
        if (!sound.isPresent()) {
            sound = inventorySettings.sound(name);
        }
        sound.play(player);
    }

    /* ------------------------------------------------------------------
     * menu.yml の解析
     * ------------------------------------------------------------------ */

    private MenuLayout parseMenu(FileConfiguration config, MenuLayout fallback) {
        if (config == null) {
            return fallback;
        }

        String title = config.getString("gui.title", fallback.title());
        int rows = config.getInt("gui.rows", fallback.rows());
        int closeSlot = config.getInt("footer.close_slot", fallback.closeSlot());
        int playerHeadSlot = config.getInt("gui.player_head_slot", fallback.playerHeadSlot());

        Map<String, GuiMaterial> materials =
                readMaterials(config, "materials", fallback.materials(), MENU_KEY);
        Map<EquipSlot, Integer> equipmentSlots =
                readMenuEquipmentSlots(config, fallback.equipmentSlots());
        Map<Integer, GuiMaterial> decorations =
                readDecorations(config, fallback.decorations(), MENU_KEY);
        Map<String, GuiSound> sounds =
                readSounds(config, "sounds", fallback.sounds(), MENU_KEY);
        Map<String, GuiIcon> icons =
                readIcons(config, "icons", fallback.icons(), MENU_KEY);
        List<MenuFeature> features = new ArrayList<>(readFeatures(config, fallback.features()));
        features.addAll(readToggleFeatures(config));

        return new MenuLayout(title, rows, closeSlot, playerHeadSlot, materials,
                equipmentSlots, decorations, sounds, icons, features);
    }

    /**
     * {@code equipment_slots} セクションを装備種別 → GUI スロットへ変換する。
     * <p>
     * キーは {@link EquipSlot#getKey()}（{@code helmet}, {@code chestplate},
     * {@code leggings}, {@code boots}, {@code main_weapon}, {@code tool_1}, {@code tool_2}）に対応する。
     */
    private static Map<EquipSlot, Integer> readMenuEquipmentSlots(
            FileConfiguration config, Map<EquipSlot, Integer> fallback) {
        ConfigurationSection section = config.getConfigurationSection("equipment_slots");
        if (section == null) {
            return fallback;
        }

        Map<EquipSlot, Integer> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            EquipSlot slot = EquipSlot.fromKey(key);
            if (slot == null) {
                continue;
            }
            int guiSlot = section.getInt(key, -1);
            if (guiSlot >= 0) {
                result.put(slot, guiSlot);
            }
        }
        return result.isEmpty() ? fallback : result;
    }

    /**
     * {@code decorations} セクションを GUI スロット → マテリアルへ変換する。
     * <p>
     * 各要素は {@code material}（Material 名）・任意の {@code custom_model_data}・
     * {@code slots}（スロット番号のリスト）を持つ。Material が未指定・不正な場合は
     * {@link MenuLayout#DEFAULT_DECORATION_MATERIAL} を使う。
     */
    private Map<Integer, GuiMaterial> readDecorations(
            FileConfiguration config, Map<Integer, GuiMaterial> fallback, String sourceKey) {
        ConfigurationSection section = config.getConfigurationSection("decorations");
        if (section == null) {
            return fallback;
        }

        Material defaultMaterial = Material.GRAY_STAINED_GLASS_PANE;
        Map<Integer, GuiMaterial> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            Material material = cache.material(sourceKey, entry, "material", defaultMaterial);
            GuiMaterial configured = new GuiMaterial(
                    material == null ? defaultMaterial : material,
                    entry.getInt("custom_model_data", 0));
            for (int slot : entry.getIntegerList("slots")) {
                if (slot >= 0) {
                    result.put(slot, configured);
                }
            }
        }
        return result.isEmpty() ? fallback : result;
    }

    /**
     * {@code features} セクションをメニュー機能定義へ変換する。
     */
    private static List<MenuFeature> readFeatures(FileConfiguration config, List<MenuFeature> fallback) {
        ConfigurationSection section = config.getConfigurationSection("features");
        if (section == null) {
            return fallback;
        }

        List<MenuFeature> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            MenuFeature feature = MenuFeature.fromConfig(key, section.getConfigurationSection(key));
            if (feature != null) {
                result.add(feature);
            }
        }
        return result.isEmpty() ? fallback : result;
    }

    /**
     * {@code toggles} セクションからレベルアップ通知トグルを生成する。
     * <p>
     * {@code self-log-slot} / {@code others-log-slot} が定義されている場合だけ
     * ボタンを配置する。未定義の場合は空きスロットへの自動配置を行わない。
     */
    private static List<MenuFeature> readToggleFeatures(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("toggles");
        if (section == null) {
            return List.of();
        }

        List<MenuFeature> result = new ArrayList<>(2);
        addToggleFeature(result, section, "self-log-slot",
                "chat_log_self", MenuGui.ACTION_CHAT_LOG_SELF);
        addToggleFeature(result, section, "others-log-slot",
                "chat_log_others", MenuGui.ACTION_CHAT_LOG_OTHERS);
        return result;
    }

    private static void addToggleFeature(List<MenuFeature> result, ConfigurationSection section,
                                         String slotKey, String id, String action) {
        int slot = section.getInt(slotKey, -1);
        if (slot < 0) {
            return;
        }
        result.add(new MenuFeature(id, slot, id, action, "zeloria.player"));
    }

    /* ------------------------------------------------------------------
     * class_tree.yml の解析
     * ------------------------------------------------------------------ */

    private ClassTreeSettings parseClassTree(FileConfiguration config, ClassTreeSettings fallback) {
        if (config == null) {
            return fallback;
        }

        String title = config.getString("title", fallback.title());
        Map<String, GuiMaterial> materials =
                readMaterials(config, "materials", fallback.materials(), CLASS_TREE_KEY);
        Map<String, GuiSound> sounds =
                readSounds(config, "sounds", fallback.sounds(), CLASS_TREE_KEY);

        return new ClassTreeSettings(title, materials, sounds);
    }

    /* ------------------------------------------------------------------
     * inventory.yml の解析
     * ------------------------------------------------------------------ */

    private InventorySettings parseInventory(FileConfiguration config, InventorySettings fallback) {
        if (config == null) {
            return fallback;
        }

        Map<EquipSlot, Integer> equipmentSlots = readEquipmentSlots(config, fallback.equipmentSlots());
        Map<String, GuiSound> sounds =
                readSounds(config, INVENTORY_SOUNDS_PATH, fallback.sounds(), INVENTORY_KEY);
        Map<String, GuiMaterial> materials =
                readMaterials(config, INVENTORY_MATERIALS_PATH, fallback.materials(), INVENTORY_KEY);
        Map<String, String> names =
                readNames(config, INVENTORY_NAMES_PATH, fallback.names());

        return new InventorySettings(equipmentSlots, sounds, materials, names);
    }

    private static Map<EquipSlot, Integer> readEquipmentSlots(
            FileConfiguration config, Map<EquipSlot, Integer> fallback) {
        ConfigurationSection section = config.getConfigurationSection("equipment.slots");
        if (section == null) {
            return fallback;
        }

        Map<EquipSlot, Integer> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            EquipSlot slot = EquipSlot.fromKey(key);
            if (slot == null) {
                continue;
            }
            result.put(slot, section.getInt(key, -1));
        }
        return result.isEmpty() ? fallback : result;
    }

    /* ------------------------------------------------------------------
     * 共通パーサ
     * ------------------------------------------------------------------ */

    /**
     * {@code materials} セクションをキー → 解決済みマテリアルへ変換する。
     * <p>
     * 各要素は次の 2 形式を受け付ける。
     * <ul>
     *   <li>{@code key: MATERIAL_NAME}（省略形式）</li>
     *   <li>{@code key: { material: MATERIAL_NAME, custom_model_data: 123 }}（詳細形式）</li>
     * </ul>
     * 未定義キーは呼び出し元が渡した既定値を保持する。
     */
    private Map<String, GuiMaterial> readMaterials(
            FileConfiguration config, String path, Map<String, GuiMaterial> fallback,
            String sourceKey) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, GuiMaterial> result = new LinkedHashMap<>(fallback);
        for (String key : section.getKeys(false)) {
            GuiMaterial defaultMaterial = fallback.getOrDefault(key, GuiMaterial.STONE);
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                // 省略形式（key: MATERIAL_NAME）。数値などは対象外。
                if (section.isString(key)) {
                    Material material = cache.material(
                            sourceKey, section, key, defaultMaterial.material());
                    result.put(key, new GuiMaterial(
                            material == null ? defaultMaterial.material() : material,
                            defaultMaterial.customModelData()));
                }
                continue;
            }
            Material material = cache.material(
                    sourceKey, entry, "material", defaultMaterial.material());
            int customModelData = entry.getInt("custom_model_data", defaultMaterial.customModelData());
            result.put(key, new GuiMaterial(
                    material == null ? defaultMaterial.material() : material, customModelData));
        }
        return result;
    }

    /**
     * {@code names} セクションをキー → 表示テキストへ変換する。
     * <p>
     * 文字列以外の値は無視し、未定義キーは呼び出し元が渡した既定値を保持する。
     */
    private static Map<String, String> readNames(
            FileConfiguration config, String path, Map<String, String> fallback) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, String> result = new LinkedHashMap<>(fallback);
        for (String key : section.getKeys(false)) {
            if (!section.isString(key)) {
                continue;
            }
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private Map<String, GuiSound> readSounds(
            FileConfiguration config, String path, Map<String, GuiSound> fallback,
            String sourceKey) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, GuiSound> result = new LinkedHashMap<>(fallback);
        for (String key : section.getKeys(false)) {
            GuiSound defaultSound = fallback.getOrDefault(key, GuiSound.NONE);
            GuiSound sound = GuiSound.from(
                    section.getConfigurationSection(key), defaultSound, cache, sourceKey);
            if (sound.isPresent()) {
                result.put(key, sound);
            }
        }
        return result;
    }

    private Map<String, GuiIcon> readIcons(
            FileConfiguration config, String path, Map<String, GuiIcon> fallback,
            String sourceKey) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, GuiIcon> result = new LinkedHashMap<>(fallback);
        for (String key : section.getKeys(false)) {
            GuiIcon icon = GuiIcon.from(
                    section.getConfigurationSection(key), cache, sourceKey);
            if (icon != null) {
                result.put(key, icon);
            }
        }
        return result;
    }
}
