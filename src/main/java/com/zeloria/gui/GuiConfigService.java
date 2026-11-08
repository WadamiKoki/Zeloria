package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.player.EquipSlot;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code config/gui/menu.yml} と {@code config/gui/inventory.yml} を解析するサービス。
 * <p>
 * {@link YamlLoader} が読み込んだ YAML から GUI 関連の設定を取り出し、
 * {@link MenuLayout}（{@code /menu} の装備枠・メニュー機能・効果音）と
 * {@link InventorySettings}（装備枠の配置・効果音）へ変換して保持する。
 * {@code /admin reload} 時は {@link #reload()} を呼ぶことで最新の設定を反映できる。
 */
public class GuiConfigService {

    /** {@code config/} からの相対キー。 */
    private static final String MENU_KEY = "gui/menu.yml";
    private static final String INVENTORY_KEY = "gui/inventory.yml";

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

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private volatile MenuLayout menuLayout;
    private volatile InventorySettings inventorySettings;

    public GuiConfigService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
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

        FileConfiguration menuConfig = yamlLoader.getConfig(MENU_KEY);
        // サーバー側のツール枠が同梱テンプレートとずれている場合は、解析前に同梱値へ同期する。
        syncMenuToolSlots(menuConfig);

        MenuLayout parsedMenu = parseMenu(menuConfig, fallbackMenu);
        InventorySettings parsedInventory =
                parseInventory(yamlLoader.getConfig(INVENTORY_KEY), fallbackInventory);
        this.menuLayout = parsedMenu;
        this.inventorySettings = parsedInventory;

        plugin.getLogger().info("GUI 設定を読み込みました (menu: "
                + (menuConfig == null ? "default" : "custom")
                + ", inventory: " + (yamlLoader.getConfig(INVENTORY_KEY) == null ? "default" : "custom")
                + ").");
    }

    /**
     * サーバー側 {@code menu.yml} のツール枠（{@code equipment_slots.tool_1 / tool_2}）を
     * 同梱テンプレートの値へ同期する。
     * <p>
     * 値が異なる場合のみ、同梱テンプレートの値（51 / 52）を書き込んで保存する。
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
        if (applied.isEmpty()) {
            return;
        }

        Path file = yamlLoader.getConfigFolder().resolve(MENU_KEY);
        try {
            serverConfig.save(file.toFile());
            plugin.getLogger().info("config/gui/menu.yml のツール枠を同梱テンプレートの値へ更新しました: "
                    + String.join(", ", applied));
        } catch (IOException e) {
            plugin.getLogger().warning("config/gui/menu.yml の保存に失敗しました: " + e.getMessage());
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

    private static MenuLayout parseMenu(FileConfiguration config, MenuLayout fallback) {
        if (config == null) {
            return fallback;
        }

        String title = config.getString("gui.title", fallback.title());
        int rows = config.getInt("gui.rows", fallback.rows());
        int closeSlot = config.getInt("footer.close_slot", fallback.closeSlot());
        int playerHeadSlot = config.getInt("gui.player_head_slot", fallback.playerHeadSlot());

        Map<String, String> fillers = readStringSection(config, "filler", fallback.fillers());
        Map<EquipSlot, Integer> equipmentSlots =
                readMenuEquipmentSlots(config, fallback.equipmentSlots());
        Map<Integer, String> decorations = readDecorations(config, fallback.decorations());
        Map<String, GuiSound> sounds = readSounds(config, "sounds", fallback.sounds());
        Map<String, GuiIcon> icons = readIcons(config, "icons", fallback.icons());
        List<MenuFeature> features = new ArrayList<>(readFeatures(config, fallback.features()));
        features.addAll(readToggleFeatures(config));

        return new MenuLayout(title, rows, closeSlot, playerHeadSlot, fillers, equipmentSlots,
                decorations, sounds, icons, features);
    }

    /**
     * {@code equipment_slots} セクションを装備種別 → GUI スロットへ変換する。
     * <p>
     * キーは {@link EquipSlot#getKey()}（{@code helmet}, {@code chestplate},
     * {@code leggings}, {@code boots}, {@code main_weapon}, {@code offhand}）に対応する。
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
     * {@code decorations} セクションを GUI スロット → Material 名へ変換する。
     * <p>
     * 各要素は {@code material}（Material 名）と {@code slots}（スロット番号のリスト）を
     * 持つ。Material が未指定の場合は {@link MenuLayout#DEFAULT_DECORATION_MATERIAL} を使う。
     */
    private static Map<Integer, String> readDecorations(
            FileConfiguration config, Map<Integer, String> fallback) {
        ConfigurationSection section = config.getConfigurationSection("decorations");
        if (section == null) {
            return fallback;
        }

        Map<Integer, String> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String material = entry.getString("material", MenuLayout.DEFAULT_DECORATION_MATERIAL);
            if (material == null || material.isBlank()) {
                material = MenuLayout.DEFAULT_DECORATION_MATERIAL;
            }
            for (int slot : entry.getIntegerList("slots")) {
                if (slot >= 0) {
                    result.put(slot, material.trim().toUpperCase(Locale.ROOT));
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
     * inventory.yml の解析
     * ------------------------------------------------------------------ */

    private static InventorySettings parseInventory(FileConfiguration config, InventorySettings fallback) {
        if (config == null) {
            return fallback;
        }

        Map<EquipSlot, Integer> equipmentSlots = readEquipmentSlots(config, fallback.equipmentSlots());
        Map<String, GuiSound> sounds = readSounds(config, INVENTORY_SOUNDS_PATH, fallback.sounds());

        return new InventorySettings(equipmentSlots, sounds);
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

    private static Map<String, String> readStringSection(
            FileConfiguration config, String path, Map<String, String> fallback) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                result.put(key, value.trim().toUpperCase(Locale.ROOT));
            }
        }
        return result.isEmpty() ? fallback : result;
    }

    private static Map<String, GuiSound> readSounds(
            FileConfiguration config, String path, Map<String, GuiSound> fallback) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, GuiSound> result = new LinkedHashMap<>(fallback);
        for (String key : section.getKeys(false)) {
            GuiSound defaultSound = fallback.getOrDefault(key, GuiSound.NONE);
            GuiSound sound = GuiSound.from(section.getConfigurationSection(key), defaultSound);
            if (sound.isPresent()) {
                result.put(key, sound);
            }
        }
        return result;
    }

    private static Map<String, GuiIcon> readIcons(
            FileConfiguration config, String path, Map<String, GuiIcon> fallback) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        Map<String, GuiIcon> result = new LinkedHashMap<>(fallback);
        for (String key : section.getKeys(false)) {
            GuiIcon icon = GuiIcon.from(section.getConfigurationSection(key));
            if (icon != null) {
                result.put(key, icon);
            }
        }
        return result;
    }
}
