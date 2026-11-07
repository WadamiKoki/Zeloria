package com.zeloria.skill;

import com.zeloria.core.YamlLoader;
import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code config/ClassTree/*.yml} を読み込み、クラスごとのツリー定義を管理する。
 * <p>
 * ノードの形状は {@link TreeDataService} が読み込む {@code TreeData/tree.yml} と
 * 共有し、クラス YAML では表示名・説明・コスト・解放条件・効果だけを定義する。
 * 読み込み時に以下を検証し、問題は警告ログへ出力する。
 * <ul>
 *     <li>共通形状に存在しないノード</li>
 *     <li>負のコスト</li>
 *     <li>存在しないノードを指す前提条件（除外する）</li>
 *     <li>ルートから到達できないノード</li>
 *     <li>前提条件の循環参照</li>
 * </ul>
 */
public final class ClassTreeManager {

    /** クラスツリー定義を配置するディレクトリ（{@code config/} からの相対パス）。 */
    private static final String DIRECTORY = "ClassTree/";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;
    private final TreeDataService treeDataService;
    private final SkillVariantManager skillVariantManager;

    /** クラス ID → ツリー定義（不変・差し替え式）。 */
    private volatile Map<String, ClassTreeData> trees = Map.of();

    public ClassTreeManager(Zeloria plugin,
                            YamlLoader yamlLoader,
                            TreeDataService treeDataService,
                            SkillVariantManager skillVariantManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        this.treeDataService = Objects.requireNonNull(treeDataService, "treeDataService");
        this.skillVariantManager = skillVariantManager;
        reload();
    }

    /**
     * {@code config/ClassTree/} 配下の YAML をすべて読み直す。
     */
    public synchronized void reload() {
        TreeData shape = treeDataService.tree();
        Map<String, ClassTreeData> loaded = new LinkedHashMap<>();

        for (String key : yamlLoader.getKeys()) {
            if (!key.startsWith(DIRECTORY) || !isYaml(key)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            try {
                ClassTreeData tree = parse(key, config, shape);
                ClassTreeData previous = loaded.putIfAbsent(tree.classId(), tree);
                if (previous != null) {
                    plugin.getLogger().warning("クラスツリーのクラス ID が重複しています: '"
                            + tree.classId() + "' (" + key + ")");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("クラスツリー定義の読み込みに失敗しました: "
                        + key + " (" + e.getMessage() + ")");
            }
        }

        this.trees = Collections.unmodifiableMap(loaded);
        plugin.getLogger().info("クラスツリー定義を " + loaded.size() + " 件読み込みました。");
    }

    /**
     * クラス ID からツリー定義を取得する。
     *
     * @param classId クラス ID
     * @return ツリー定義。存在しない場合は空
     */
    public Optional<ClassTreeData> find(String classId) {
        String normalized = DefinitionParser.normalizeId(classId);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(trees.get(normalized));
    }

    /**
     * 読み込み済みのすべてのツリー定義を取得する。
     */
    public Collection<ClassTreeData> all() {
        return List.copyOf(trees.values());
    }

    private ClassTreeData parse(String key, FileConfiguration config, TreeData shape) {
        String classId = DefinitionParser.normalizeId(
                config.getString("id", DefinitionParser.fileName(key)));
        if (classId.isEmpty()) {
            throw new IllegalArgumentException("classId が空です");
        }

        String rawTitle = config.getString("title");
        Component title = rawTitle == null || rawTitle.isBlank()
                ? null
                : DefinitionParser.component(rawTitle, rawTitle);

        Map<String, ClassTreeNode> parsed = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("nodes");
        if (section != null) {
            for (String rawNodeId : section.getKeys(false)) {
                String nodeId = DefinitionParser.normalizeId(rawNodeId);
                if (nodeId.isEmpty()) {
                    continue;
                }
                if (shape.node(nodeId) == null) {
                    plugin.getLogger().warning(
                            "共通形状に存在しないクラスツリーノードです: '" + nodeId
                                    + "' (" + key + ") - 無視します。");
                    continue;
                }

                ConfigurationSection nodeSection = section.getConfigurationSection(rawNodeId);
                if (nodeSection == null) {
                    plugin.getLogger().warning("クラスツリーノードの形式が不正です: '"
                            + rawNodeId + "' (" + key + ")");
                    continue;
                }

                parsed.put(nodeId, parseNode(nodeId, nodeSection, key));
            }
        }

        // 存在しないノードを指す前提条件を除去する。
        Map<String, ClassTreeNode> sanitized = new LinkedHashMap<>();
        for (ClassTreeNode node : parsed.values()) {
            Set<String> requires = sanitizePrerequisites(
                    node, node.requires(), parsed, key, "requires");
            Set<String> requiresAny = sanitizePrerequisites(
                    node, node.requiresAny(), parsed, key, "requiresAny");
            sanitized.put(node.id(), new ClassTreeNode(
                    node.id(),
                    node.displayName(),
                    node.description(),
                    node.cost(),
                    requires,
                    requiresAny,
                    node.effectLore(),
                    node.statEffects(),
                    node.skillEffects(),
                    node.replaceSkill()));
        }

        ClassTreeData tree = new ClassTreeData(classId, title, sanitized);
        validate(tree, key);
        validateReplaceSkills(tree, key);
        return tree;
    }

    private ClassTreeNode parseNode(String nodeId, ConfigurationSection section, String key) {
        int cost = section.getInt("cost", 0);
        if (cost < 0) {
            plugin.getLogger().warning(
                    "クラスツリーノードのコストが負の値です: '" + nodeId
                            + "' (" + key + ") - 0 として扱います。");
            cost = 0;
        }

        Object effectLore = section.get("effectLore");
        if (effectLore == null) {
            // 旧形式（effects）との後方互換。
            effectLore = section.get("effects");
        }

        return new ClassTreeNode(
                nodeId,
                DefinitionParser.component(
                        section.getString("displayName"),
                        section.getString("name", nodeId)),
                DefinitionParser.description(section.get("description")),
                cost,
                toSet(section.getStringList("requires")),
                toSet(section.getStringList("requiresAny")),
                DefinitionParser.description(effectLore),
                readEffects(section, "statEffects"),
                readEffects(section, "skillEffects"),
                section.getString("replaceSkill"));
    }

    /**
     * {@code statEffects} / {@code skillEffects} の数値マップを読み込む。
     * 負の値（デバフ）もそのまま保持する。
     */
    private Map<String, Double> readEffects(ConfigurationSection section, String field) {
        Map<String, Double> effects = new LinkedHashMap<>();

        ConfigurationSection effectsSection = section.getConfigurationSection(field);
        if (effectsSection != null) {
            for (String rawKey : effectsSection.getKeys(false)) {
                addEffect(effects, rawKey, effectsSection.get(rawKey));
            }
            return effects;
        }

        Object raw = section.get(field);
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                addEffect(effects, entry.getKey(), entry.getValue());
            }
        }
        return effects;
    }

    private static void addEffect(Map<String, Double> effects, Object rawKey, Object rawValue) {
        if (rawKey == null) {
            return;
        }
        String key = String.valueOf(rawKey).trim();
        if (key.isEmpty()) {
            return;
        }
        double value;
        if (rawValue instanceof Number number) {
            value = number.doubleValue();
        } else if (rawValue != null) {
            try {
                value = Double.parseDouble(String.valueOf(rawValue).trim());
            } catch (NumberFormatException ignored) {
                return;
            }
        } else {
            return;
        }
        if (Double.isFinite(value)) {
            effects.put(key, value);
        }
    }

    /**
     * 赤ノードが参照する派生スキルが存在するか検証する。
     */
    private void validateReplaceSkills(ClassTreeData tree, String key) {
        if (skillVariantManager == null) {
            return;
        }
        for (ClassTreeNode node : tree.nodes().values()) {
            String replaceSkill = node.replaceSkill();
            if (replaceSkill == null) {
                continue;
            }
            if (!skillVariantManager.has(replaceSkill)) {
                plugin.getLogger().warning(
                        "クラスツリーノード '" + node.id() + "' の replaceSkill が"
                                + " 存在しません: '" + replaceSkill + "' (" + key + ")");
            }
        }
    }

    private Set<String> sanitizePrerequisites(ClassTreeNode node,
                                              Set<String> prerequisites,
                                              Map<String, ClassTreeNode> nodes,
                                              String key,
                                              String field) {
        if (prerequisites.isEmpty()) {
            return Set.of();
        }

        Set<String> result = new LinkedHashSet<>();
        for (String prerequisite : prerequisites) {
            if (nodes.containsKey(prerequisite)) {
                result.add(prerequisite);
            } else {
                plugin.getLogger().warning(
                        "クラスツリーノード '" + node.id() + "' の " + field
                                + " が存在しないノードを参照しています: '" + prerequisite
                                + "' (" + key + ") - 無視します。");
            }
        }
        return result;
    }

    private void validate(ClassTreeData tree, String key) {
        if (tree.nodes().isEmpty()) {
            plugin.getLogger().warning("クラスツリーノードが 1 件も定義されていません: " + key);
            return;
        }

        String rootId = tree.rootId();
        if (rootId == null) {
            plugin.getLogger().warning(
                    "クラスツリーにルートノードがありません: " + key
                            + " - 'root' を定義するか、前提条件を持たないノードを 1 件配置してください。");
        } else {
            ClassTreeNode root = tree.node(rootId);
            if (root != null && root.hasPrerequisites()) {
                plugin.getLogger().warning(
                        "ルートノードに前提条件が設定されています: '" + rootId + "' (" + key + ")");
            }
        }

        detectCycles(tree, key);

        if (rootId == null) {
            return;
        }

        // 前提条件 → 解放先 の逆方向グラフを作り、ルートから到達できるノードを求める。
        Map<String, Set<String>> dependents = new HashMap<>();
        for (ClassTreeNode node : tree.nodes().values()) {
            Set<String> prerequisites = new LinkedHashSet<>(node.requires());
            prerequisites.addAll(node.requiresAny());
            for (String prerequisite : prerequisites) {
                dependents.computeIfAbsent(prerequisite, ignored -> new LinkedHashSet<>())
                        .add(node.id());
            }
        }

        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        reachable.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String dependent : dependents.getOrDefault(current, Set.of())) {
                if (reachable.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }

        Set<String> unreachable = new LinkedHashSet<>(tree.nodes().keySet());
        unreachable.removeAll(reachable);
        if (!unreachable.isEmpty()) {
            plugin.getLogger().warning(
                    "ルートから到達できないクラスツリーノードがあります: " + unreachable
                            + " (" + key + ")");
        }
    }

    private void detectCycles(ClassTreeData tree, String key) {
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String nodeId : tree.nodes().keySet()) {
            detectCycle(nodeId, tree.nodes(), state, path, key);
        }
    }

    private void detectCycle(String nodeId,
                             Map<String, ClassTreeNode> nodes,
                             Map<String, Integer> state,
                             Deque<String> path,
                             String key) {
        Integer current = state.get(nodeId);
        if (current != null && current == 1) {
            plugin.getLogger().warning(
                    "クラスツリーの前提条件に循環参照があります: " + path + " -> " + nodeId
                            + " (" + key + ")");
            return;
        }
        if (current != null && current == 2) {
            return;
        }

        state.put(nodeId, 1);
        path.addLast(nodeId);

        ClassTreeNode node = nodes.get(nodeId);
        if (node != null) {
            Set<String> prerequisites = new LinkedHashSet<>(node.requires());
            prerequisites.addAll(node.requiresAny());
            for (String prerequisite : prerequisites) {
                if (nodes.containsKey(prerequisite)) {
                    detectCycle(prerequisite, nodes, state, path, key);
                }
            }
        }

        path.removeLast();
        state.put(nodeId, 2);
    }

    private static Set<String> toSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = DefinitionParser.normalizeId(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static boolean isYaml(String key) {
        return key.endsWith(".yml") || key.endsWith(".yaml");
    }
}
