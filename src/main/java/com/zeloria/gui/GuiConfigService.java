package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.player.EquipSlot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

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

        MenuLayout parsedMenu = parseMenu(yamlLoader.getConfig(MENU_KEY), fallbackMenu);
        InventorySettings parsedInventory =
                parseInventory(yamlLoader.getConfig(INVENTORY_KEY), fallbackInventory);
        this.menuLayout = parsedMenu;
        this.inventorySettings = parsedInventory;

        plugin.getLogger().info("GUI 設定を読み込みました (menu: "
                + (yamlLoader.getConfig(MENU_KEY) == null ? "default" : "custom")
                + ", inventory: " + (yamlLoader.getConfig(INVENTORY_KEY) == null ? "default" : "custom")
                + ").");
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

        Map<String, String> fillers = readStringSection(config, "filler", fallback.fillers());
        Map<EquipSlot, Integer> equipmentSlots =
                readMenuEquipmentSlots(config, fallback.equipmentSlots());
        Map<Integer, String> decorations = readDecorations(config, fallback.decorations());
        Map<String, GuiSound> sounds = readSounds(config, "sounds", fallback.sounds());
        Map<String, GuiIcon> icons = readIcons(config, "icons", fallback.icons());
        List<MenuFeature> features = readFeatures(config, fallback.features());

        return new MenuLayout(title, rows, closeSlot, fillers, equipmentSlots, decorations,
                sounds, icons, features);
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
