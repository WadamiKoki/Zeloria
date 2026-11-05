package com.zeloria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code config/gui/*.yml} の汎用定義から生成する読み取り専用 GUI。
 * <p>
 * {@code rows} / {@code items} を持つシンプルな YAML をそのまま表示する。
 * NPC の {@code interactGui} など、コードを持たない GUI を開くために使用する。
 * <pre>
 * id: "guide_menu"
 * gui:
 *   title: "&lt;green&gt;案内人&lt;/green&gt;"
 *   rows: 3
 * items:
 *   close:
 *     slot: 22
 *     material: BARRIER
 *     name: "&lt;red&gt;閉じる&lt;/red&gt;"
 *     action: close
 * </pre>
 * クリック処理は {@link SimpleGuiListener} が担う。
 */
public final class SimpleGui implements InventoryHolder {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int DEFAULT_ROWS = 3;
    private static final int MAX_ROWS = 6;

    /** クリックで GUI を閉じるアクション名。 */
    public static final String ACTION_CLOSE = "close";

    private final Inventory inventory;
    private final Set<Integer> closeSlots = new HashSet<>();

    private SimpleGui(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /**
     * YAML 設定から GUI を生成する。
     *
     * @param config        GUI 設定
     * @param fallbackTitle タイトル未指定時に使用する文字列
     * @return 生成した GUI
     */
    public static SimpleGui from(FileConfiguration config, String fallbackTitle) {
        ConfigurationSection gui = config.getConfigurationSection("gui");

        String rawTitle = gui == null ? config.getString("title") : gui.getString("title");
        if (rawTitle == null || rawTitle.isBlank()) {
            rawTitle = fallbackTitle;
        }
        if (rawTitle == null || rawTitle.isBlank()) {
            rawTitle = "";
        }

        int rows = gui == null
                ? config.getInt("rows", DEFAULT_ROWS)
                : gui.getInt("rows", DEFAULT_ROWS);
        rows = Math.max(1, Math.min(MAX_ROWS, rows));

        SimpleGui result = new SimpleGui(rows, MINI.deserialize(rawTitle));
        result.render(config);
        return result;
    }

    /**
     * {@code items} セクションを読み込み、スロットへアイコンを配置する。
     */
    private void render(FileConfiguration config) {
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items == null) {
            return;
        }

        for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key);
            if (item == null) {
                continue;
            }

            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                Bukkit.getLogger().warning(
                        "GUI アイテム '" + key + "' のスロットが不正です: " + slot);
                continue;
            }

            GuiIcon icon = GuiIcon.from(item);
            if (icon == null) {
                continue;
            }
            inventory.setItem(slot, icon.createItem(Map.of()));

            if (ACTION_CLOSE.equalsIgnoreCase(item.getString("action", ""))) {
                closeSlots.add(slot);
            }
        }
    }

    /**
     * 指定スロットが「閉じる」アクションか判定する。
     */
    public boolean isCloseSlot(int slot) {
        return closeSlots.contains(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
