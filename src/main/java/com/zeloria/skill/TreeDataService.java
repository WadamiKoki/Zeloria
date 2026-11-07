package com.zeloria.skill;

import com.zeloria.core.YamlLoader;
import com.zeloria.core.Zeloria;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code config/TreeData/tree.yml} を読み込み、クラスツリー共通形状を管理する。
 * <p>
 * 形状は全クラスで共有する。読み込み結果は不変マップとして差し替えるため、
 * 参照側はロックなしで安全に読み取れる。再読み込みは {@code /admin reload} から行う。
 */
public final class TreeDataService {

    /** 共通形状の設定ファイルキー。 */
    public static final String CONFIG_PATH = "TreeData/tree.yml";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    /** 固定UI（灰色ガラス）専用の列。ノード配置は常に拒否する。 */
    private static final int FIXED_UI_COLUMN = 7;

    /** 読み込み済みの共通形状（不変・差し替え式）。 */
    private volatile TreeData tree = TreeData.defaults();

    public TreeDataService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * 共通形状を再読み込みする。
     */
    public synchronized void reload() {
        FileConfiguration config = yamlLoader.getConfig(CONFIG_PATH);
        if (config == null) {
            plugin.getLogger().warning(
                    "クラスツリー共通形状が見つかりません: " + CONFIG_PATH
                            + " - 空のツリーで動作します。");
            this.tree = TreeData.defaults();
            return;
        }

        int rows = config.getInt("rows", TreeData.DEFAULT_ROWS);
        int visibleRows = config.getInt("visible_rows", rows);
        int fixedColumn = config.getInt("fixed_column", TreeData.DEFAULT_FIXED_COLUMN);

        ConfigurationSection fixed = config.getConfigurationSection("fixed_slots");
        int resetSlot = readSlot(fixed, "reset", 8);
        int downSlot = readSlot(fixed, "down", readSlot(fixed, "scroll_down", 35));
        int upSlot = readSlot(fixed, "up", readSlot(fixed, "scroll_up", 26));
        int closeSlot = readSlot(fixed, "close", 53);

        Map<String, TreeShapeNode> nodes = new LinkedHashMap<>();
        Map<Integer, String> usedPositions = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("nodes");
        if (section != null) {
            for (String rawId : section.getKeys(false)) {
                String id = DefinitionParser.normalizeId(rawId);
                if (id.isEmpty()) {
                    continue;
                }

                ConfigurationSection nodeSection = section.getConfigurationSection(rawId);
                if (nodeSection == null) {
                    plugin.getLogger().warning(
                            "クラスツリーノードの形式が不正です: '" + rawId + "'");
                    continue;
                }

                int row = nodeSection.getInt("row", -1);
                int column = nodeSection.getInt("column", nodeSection.getInt("col", -1));
                if (row < 0 || column < 0 || column > 8) {
                    plugin.getLogger().warning(
                            "クラスツリーノードの位置が不正です: '" + rawId
                                    + "' (row=" + row + ", column=" + column + ")");
                    continue;
                }
                if (column == FIXED_UI_COLUMN || column == fixedColumn) {
                    plugin.getLogger().warning(
                            "固定UI列（col 7）にはノードを配置できません: '" + rawId
                                    + "' (column=" + column + ")");
                    continue;
                }

                String previous = usedPositions.putIfAbsent(row * 9 + column, id);
                if (previous != null) {
                    plugin.getLogger().warning(
                            "クラスツリーノードの位置が重複しています: '" + rawId
                                    + "' は '" + previous + "' と同じ位置です。");
                    continue;
                }

                Material icon = DefinitionParser.material(
                        nodeSection.getString("icon"),
                        Material.PAPER,
                        "tree node " + id,
                        plugin.getLogger());
                int customModelData = Math.max(0, nodeSection.getInt("custom_model_data", 0));
                nodes.put(id, new TreeShapeNode(id, row, column, icon, customModelData));
            }
        }

        Set<Integer> fixedSlots = new HashSet<>();
        warnDuplicateFixedSlot(fixedSlots, resetSlot, "reset");
        warnDuplicateFixedSlot(fixedSlots, downSlot, "down");
        warnDuplicateFixedSlot(fixedSlots, upSlot, "up");
        warnDuplicateFixedSlot(fixedSlots, closeSlot, "close");

        this.tree = new TreeData(
                config.getString("title"),
                rows,
                visibleRows,
                fixedColumn,
                resetSlot,
                downSlot,
                upSlot,
                closeSlot,
                nodes);

        plugin.getLogger().info("クラスツリー共通形状を読み込みました (nodes="
                + this.tree.nodes().size() + ", rows=" + this.tree.rows() + ").");
    }

    /**
     * 現在の共通形状を取得する。
     */
    public TreeData tree() {
        return tree;
    }

    private static int readSlot(ConfigurationSection section, String key, int fallback) {
        return section == null ? fallback : section.getInt(key, fallback);
    }

    private void warnDuplicateFixedSlot(Set<Integer> used, int slot, String name) {
        if (slot < 0) {
            return;
        }
        if (!used.add(slot)) {
            plugin.getLogger().warning(
                    "クラスツリー固定ボタンのスロットが重複しています: " + name + " (" + slot + ")");
        }
    }
}
