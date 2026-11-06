package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.player.ClassStats;
import com.zeloria.player.PlayerClassData;
import com.zeloria.player.ZeloriaClass;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code config/ClassData/*.yml} を読み込み、クラス定義を管理する。
 * <p>
 * YAML の {@code displayName} / {@code description} は MiniMessage として解決し、
 * {@link Component} の状態で保持する。定義は不変マップとして差し替えるため、
 * 参照側はロックなしで安全に読み取れる。再読み込みは {@code /admin reload} から行う。
 */
public final class ClassManager {

    /** クラス定義を配置するディレクトリ（{@code config/} からの相対パス）。 */
    private static final String DIRECTORY = "ClassData/";

    /** アイコン未指定時のフォールバック。 */
    private static final Material FALLBACK_ICON = Material.PAPER;

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    /** クラス ID → クラス定義（不変・差し替え式）。 */
    private volatile Map<String, ZeloriaClass> classes = Map.of();

    public ClassManager(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * {@code config/ClassData/} 配下の YAML をすべて読み直す。
     * <p>
     * 読み込みに失敗したファイルは警告を出してスキップし、他のクラス定義の
     * 読み込みは継続する。
     */
    public synchronized void reload() {
        Map<String, ZeloriaClass> loaded = new LinkedHashMap<>();

        for (String key : yamlLoader.getKeys()) {
            if (!key.startsWith(DIRECTORY) || !isYaml(key)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            try {
                ZeloriaClass definition = parse(key, config);
                ZeloriaClass previous = loaded.putIfAbsent(definition.id(), definition);
                if (previous != null) {
                    plugin.getLogger().warning("クラス ID が重複しています: '"
                            + definition.id() + "' (" + key + ")");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("クラス定義の読み込みに失敗しました: "
                        + key + " (" + e.getMessage() + ")");
            }
        }

        this.classes = Collections.unmodifiableMap(loaded);
        plugin.getLogger().info("クラス定義を " + loaded.size() + " 件読み込みました。");
    }

    /**
     * クラス ID から定義を取得する。
     *
     * @param classId クラス ID
     * @return クラス定義。存在しない場合は空
     */
    public Optional<ZeloriaClass> find(String classId) {
        String normalized = DefinitionParser.normalizeId(classId);
        return normalized.isEmpty() ? Optional.empty() : Optional.ofNullable(classes.get(normalized));
    }

    /**
     * 読み込み済みのすべてのクラス定義を取得する（YAML の読み込み順）。
     */
    public Collection<ZeloriaClass> all() {
        return List.copyOf(classes.values());
    }

    /**
     * 読み込み済みのクラス ID 一覧を取得する。
     */
    public List<String> ids() {
        return List.copyOf(classes.keySet());
    }

    /**
     * 読み込み済みのクラス定義数を取得する。
     */
    public int size() {
        return classes.size();
    }

    /**
     * {@code unlockedByDefault: true} のクラスをプレイヤーデータへ解放する。
     * <p>
     * ログイン時の復元直後に呼び出すことで、既存プレイヤーにも新規追加された
     * 初期解放クラスが反映される。既に解放済みの場合は何もしない。
     *
     * @param data 対象のプレイヤークラスデータ（{@code null} の場合は何もしない）
     */
    public void applyDefaultUnlocks(PlayerClassData data) {
        if (data == null) {
            return;
        }
        for (ZeloriaClass definition : classes.values()) {
            if (definition.unlockedByDefault()) {
                data.unlock(definition.id());
            }
        }
    }

    /**
     * プレイヤーの現在のクラス定義を取得する。
     *
     * @param data プレイヤークラスデータ
     * @return 現在のクラス定義。未選択・定義なしの場合は空
     */
    public Optional<ZeloriaClass> current(PlayerClassData data) {
        return data == null ? Optional.empty() : find(data.currentClassIdOrNull());
    }

    private ZeloriaClass parse(String key, FileConfiguration config) {
        String id = DefinitionParser.normalizeId(
                DefinitionParser.text(config.getString("id"), DefinitionParser.fileName(key)));
        String displayNameRaw = DefinitionParser.text(config.getString("displayName"), id);
        Component displayName = DefinitionParser.component(displayNameRaw, id);

        List<Component> description = DefinitionParser.description(config.get("description"));
        Material icon = DefinitionParser.material(
                config.getString("icon"), FALLBACK_ICON, "class " + id, plugin.getLogger());
        boolean unlockedByDefault = config.getBoolean("unlockedByDefault", false);
        List<String> skills = config.getStringList("skills");
        ClassStats baseStats = ClassStats.fromConfig(
                config.getConfigurationSection("baseStats"), ClassStats.DEFAULT);
        // 旧キー（levelUpStats: 固定加算値）も後方互換のため読み込む。
        ConfigurationSection percentSection = config.getConfigurationSection("levelUpPercent");
        if (percentSection == null) {
            percentSection = config.getConfigurationSection("levelUpStats");
        }
        ClassStats levelUpPercent = ClassStats.fromConfig(percentSection, ClassStats.NONE);

        return new ZeloriaClass(id, displayName, description, icon, unlockedByDefault, skills,
                baseStats, levelUpPercent);
    }

    private static boolean isYaml(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }
}
