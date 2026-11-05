package com.zeloria.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * プラグインデータフォルダ ({@code plugins/Zeloria/config/}) 配下の YAML ファイルを
 * 再帰的に読み込み、メモリ上にキャッシュする構成クラス。
 * <p>
 * キャッシュのキーは {@code config/} からの相対パス（区切り文字は {@code /}）で、
 * 例: {@code LayerData/Hub.yml}。{@code getConfig(String)} では相対パスに加えて
 * ファイル名（拡張子なし）や YAML 内の {@code id} でも参照できる。
 * <p>
 * 読み込みはファイル I/O を伴うため、{@link #loadAll()} / {@link #reload()} は
 * サーバー起動時などメインスレッドから呼び出すことを想定している。
 * 読み込み後の {@link #getConfig(String)} はスレッドセーフ。
 * <p>
 * {@code config/MessageData/} 配下のメッセージ設定は
 * {@link #getMessageConfig(String)} から相対パス・拡張子なし名・{@code id} の
 * いずれでも参照できるよう、一括読み込み時に専用キャッシュへ展開する。
 */
public class YamlLoader {

    /** 設定ファイルを配置するディレクトリ名（データフォルダ直下）。 */
    private static final String CONFIG_DIR_NAME = "config";

    /** メッセージ設定を配置するディレクトリ名（{@code config/} 直下）。 */
    private static final String MESSAGE_DIR_NAME = "MessageData";

    /** 読み込み対象とする拡張子（第一候補）。 */
    private static final String YAML_EXTENSION = ".yml";

    /** 読み込み対象とする拡張子（第二候補）。 */
    private static final String YAML_ALT_EXTENSION = ".yaml";

    /** YAML 内の識別子として扱うキー。 */
    private static final String ID_KEY = "id";

    /**
     * 起動時に {@code config/MessageData/} へ展開する既定メッセージファイル。
     * 既にファイルが存在する場合は上書きしない。
     */
    private static final List<String> DEFAULT_MESSAGE_FILES = List.of(
            "admin.yml",
            "combat.yml",
            "death.yml",
            "equipment.yml",
            "gate.yml",
            "level.yml",
            "skill.yml",
            "system.yml"
    );

    /**
     * 起動時に {@code config/} へ展開する既定設定ファイル（GUI・アイテム）。
     * サーバー管理者の編集内容を上書きしないよう、既存ファイルには触れない。
     */
    private static final List<String> DEFAULT_CONFIG_FILES = List.of(
            "gui/menu.yml",
            "gui/class_select.yml",
            "ClassData/archer.yml",
            "ClassData/sorcerer.yml",
            "ClassData/rogue.yml",
            "ClassData/knight.yml",
            "ClassData/vanguard.yml",
            "SkillData/archer/skill1.yml",
            "SkillData/archer/skill2.yml",
            "SkillData/archer/skill3.yml",
            "SkillData/archer/skill4.yml",
            "SkillData/archer/skill5.yml",
            "SkillData/sorcerer/skill1.yml",
            "SkillData/sorcerer/skill2.yml",
            "SkillData/sorcerer/skill3.yml",
            "SkillData/sorcerer/skill4.yml",
            "SkillData/sorcerer/skill5.yml",
            "SkillData/rogue/skill1.yml",
            "SkillData/rogue/skill2.yml",
            "SkillData/rogue/skill3.yml",
            "SkillData/rogue/skill4.yml",
            "SkillData/rogue/skill5.yml",
            "SkillData/knight/skill1.yml",
            "SkillData/knight/skill2.yml",
            "SkillData/knight/skill3.yml",
            "SkillData/knight/skill4.yml",
            "SkillData/knight/skill5.yml",
            "SkillData/vanguard/skill1.yml",
            "SkillData/vanguard/skill2.yml",
            "SkillData/vanguard/skill3.yml",
            "SkillData/vanguard/skill4.yml",
            "SkillData/vanguard/skill5.yml",
            "MaterialData/iron_ore.yml",
            "LevelData/level_settings.yml",
            "ArmorData/helmet.yml",
            "ArmorData/chestplate.yml",
            "ArmorData/leggings.yml",
            "ArmorData/boots.yml",
            "WeaponData/archer/test.yml",
            "WeaponData/knight/test.yml",
            "WeaponData/rogue/test.yml",
            "WeaponData/sorcerer/test.yml",
            "WeaponData/vanguard/test.yml",
            "NpcData/guide.yml",
            "gui/guide_menu.yml"
    );

    private final JavaPlugin plugin;

    /**
     * 設定フォルダ: {@code plugins/Zeloria/config/}
     */
    private final Path configFolder;

    /**
     * メッセージ設定フォルダ: {@code plugins/Zeloria/config/MessageData/}
     */
    private final Path messageFolder;

    /**
     * 相対パス（{@code /} 区切り）をキーとした読み込み済み設定。
     */
    private final Map<String, FileConfiguration> configs = new ConcurrentHashMap<>();

    /**
     * YAML 内の {@code id} をキーとしたインデックス。
     */
    private final Map<String, FileConfiguration> configsById = new ConcurrentHashMap<>();

    /**
     * {@code MessageData/} 配下のメッセージ設定（相対パス・拡張子なし・ファイル名で参照可能）。
     */
    private final Map<String, FileConfiguration> messageConfigs = new ConcurrentHashMap<>();

    /**
     * {@code MessageData/} 配下のメッセージ設定を YAML 内の {@code id} で引くインデックス。
     */
    private final Map<String, FileConfiguration> messageConfigsById = new ConcurrentHashMap<>();

    public YamlLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configFolder = plugin.getDataFolder().toPath()
                .resolve(CONFIG_DIR_NAME)
                .toAbsolutePath()
                .normalize();
        this.messageFolder = this.configFolder.resolve(MESSAGE_DIR_NAME);
    }

    /**
     * {@code config/} ディレクトリが存在しない場合は作成したうえで、
     * 配下のすべての YAML ファイルを読み込む。
     * <p>
     * {@code config/MessageData/} については、同梱の既定メッセージファイル
     * （{@code gate.yml} / {@code skill.yml} / {@code system.yml} / {@code admin.yml}）が
     * 存在しない場合のみ自動展開する。{@code config/gui/} も同様に既定ファイルを展開する。
     * 他のデータ YAML は自動展開しない。
     */
    public synchronized void loadAll() {
        createConfigFolder();
        createMessageFolder();
        saveDefaultMessages();
        saveDefaultConfigs();
        reload();
    }

    /**
     * 既存のキャッシュをすべてクリアし、{@code plugins/Zeloria/config/} 配下の
     * YAML ファイルを再読み込みする（{@code /admin reload} 用）。
     * <p>
     * 読み込みに失敗したファイルは警告を出してスキップする。
     *
     * @return 再読み込み後の設定件数
     */
    public synchronized int loadAllConfigs() {
        reload();
        return configs.size();
    }

    /**
     * キャッシュをクリアし、{@code config/} 配下の YAML ファイルを再読み込みする。
     * <p>
     * 読み込みに失敗したファイルは警告を出してスキップする。
     */
    public synchronized void reload() {
        configs.clear();
        configsById.clear();
        messageConfigs.clear();
        messageConfigsById.clear();

        if (!createConfigFolder()) {
            return;
        }
        createMessageFolder();

        try (Stream<Path> stream = Files.walk(configFolder)) {
            // ディレクトリ（gui/ や LayerData/ など）自体を YAML として読み込まないよう、
            // File#isFile() で「実ファイル」であることを明示的に検証する。
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toFile().isFile())
                    .filter(this::isYamlFile)
                    .sorted()
                    .toList();

            for (Path file : files) {
                loadFile(file);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("YAML ファイルの走査に失敗しました: " + e.getMessage());
            return;
        }

        cacheMessages();

        plugin.getLogger().info("YAML 設定を " + configs.size()
                + " 件（うち MessageData " + getMessageCount() + " 件）読み込みました。");
    }

    /**
     * {@code config/MessageData/} 配下のメッセージ設定を取得する。
     * <p>
     * キーは以下のいずれか:
     * <ul>
     *     <li>{@code MessageData/} からの相対パス（例: {@code gate.yml}）</li>
     *     <li>拡張子を省略した名前（例: {@code gate}）</li>
     *     <li>YAML 内の {@code id}（例: {@code gate}）</li>
     *     <li>キャッシュキー（例: {@code MessageData/gate.yml}）</li>
     * </ul>
     *
     * @param key キー
     * @return メッセージ設定。見つからない場合は {@code null}
     */
    public FileConfiguration getMessageConfig(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String normalized = normalizeMessageKey(key);

        FileConfiguration config = messageConfigs.get(normalized);
        if (config != null) {
            return config;
        }
        if (!normalized.endsWith(YAML_EXTENSION)) {
            config = messageConfigs.get(normalized + YAML_EXTENSION);
            if (config != null) {
                return config;
            }
        }

        config = messageConfigsById.get(normalized);
        if (config != null) {
            return config;
        }

        return findByFileName(messageConfigs, normalized);
    }

    /**
     * 読み込み済みのメッセージ設定を取得する（名前順、不変ビュー）。
     */
    public Map<String, FileConfiguration> getMessageConfigs() {
        return Collections.unmodifiableMap(new TreeMap<>(messageConfigs));
    }

    /**
     * 読み込み済みのメッセージ設定キー一覧を取得する（名前順、不変ビュー）。
     */
    public List<String> getMessageKeys() {
        return messageConfigs.keySet().stream()
                .filter(key -> key.endsWith(YAML_EXTENSION))
                .sorted()
                .distinct()
                .toList();
    }

    /**
     * 読み込み済みのメッセージ設定数（ファイル数）を取得する。
     */
    public int getMessageCount() {
        return (int) messageConfigs.values().stream().distinct().count();
    }

    /**
     * 指定キーの設定ファイルを取得する。
     * <p>
     * キーは以下のいずれか:
     * <ul>
     *     <li>{@code config/} からの相対パス（例: {@code LayerData/Hub.yml}）</li>
     *     <li>拡張子を省略した相対パス（例: {@code LayerData/Hub}）</li>
     *     <li>YAML 内の {@code id}（例: {@code hub}）</li>
     * </ul>
     *
     * @param path キー
     * @return 読み込み済みの設定。見つからない場合は {@code null}
     */
    public FileConfiguration getConfig(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String key = normalizeKey(path);
        if (isDirectory(key)) {
            // フォルダ名（gui / LayerData など）は設定ファイルではないためロード対象外。
            return null;
        }

        FileConfiguration config = configs.get(key);
        if (config != null) {
            return config;
        }

        if (!hasYamlExtension(key)) {
            config = configs.get(key + YAML_EXTENSION);
            if (config == null) {
                config = configs.get(key + YAML_ALT_EXTENSION);
            }
            if (config != null) {
                return config;
            }
        }

        // 相対パスではなくファイル名のみ／識別 ID で指定された場合のフォールバック。
        config = configsById.get(key);
        if (config != null) {
            return config;
        }

        return findByFileName(key);
    }

    /**
     * 読み込み済みのすべての設定を取得する（相対パス順、不変ビュー）。
     *
     * @return 相対パスをキーとした設定のマップ
     */
    public Map<String, FileConfiguration> getConfigs() {
        return Collections.unmodifiableMap(new TreeMap<>(configs));
    }

    /**
     * 読み込み済みのキー（相対パス）一覧を取得する（相対パス順、不変ビュー）。
     */
    public List<String> getKeys() {
        return configs.keySet().stream().sorted().toList();
    }

    /**
     * 指定キーの設定が読み込まれているか判定する。
     */
    public boolean contains(String path) {
        return getConfig(path) != null;
    }

    /**
     * 読み込み済みの設定数を取得する。
     */
    public int size() {
        return configs.size();
    }

    /**
     * 読み込み済み設定から {@code MessageData/} 配下のものを抽出し、
     * 相対パス・拡張子なし・ファイル名・{@code id} の各キーで引けるようキャッシュする。
     */
    private void cacheMessages() {
        String prefix = MESSAGE_DIR_NAME + "/";

        for (Map.Entry<String, FileConfiguration> entry : configs.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }

            FileConfiguration config = entry.getValue();
            String relative = key.substring(prefix.length());

            messageConfigs.put(relative, config);

            String withoutExtension = stripExtension(relative);
            if (!withoutExtension.equals(relative)) {
                messageConfigs.putIfAbsent(withoutExtension, config);
            }

            Path fileName = Path.of(relative).getFileName();
            if (fileName != null) {
                String name = fileName.toString();
                messageConfigs.putIfAbsent(name, config);
                messageConfigs.putIfAbsent(stripExtension(name), config);
            }

            String id = config.getString(ID_KEY);
            if (id != null && !id.isBlank()) {
                messageConfigsById.putIfAbsent(id, config);
                messageConfigs.putIfAbsent(id, config);
            }
        }
    }

    private static String stripExtension(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(YAML_ALT_EXTENSION)) {
            return name.substring(0, name.length() - YAML_ALT_EXTENSION.length());
        }
        if (lower.endsWith(YAML_EXTENSION)) {
            return name.substring(0, name.length() - YAML_EXTENSION.length());
        }
        return name;
    }

    /**
     * 指定キーが {@code config/} 配下の既存ディレクトリを指しているか判定する。
     * <p>
     * {@code gui} や {@code LayerData} のようなフォルダ名は設定ファイルではないため、
     * {@link #getConfig(String)} では {@code null} を返す。また、配下に YAML が 1 件も
     * 無いフォルダでも「存在するフォルダ」として扱えるよう、ファイルシステムを直接参照する。
     * 大文字小文字を区別するファイルシステムでも {@code LayerData} / {@code layerdata} の
     * 表記揺れを許容するため、見つからない場合は親ディレクトリを走査して照合する。
     *
     * @param path フォルダ名、または {@code config/} からの相対パス
     * @return 既存ディレクトリを指している場合は {@code true}
     */
    public boolean isDirectory(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String key = normalizeKey(path);
        if (key.isBlank() || hasYamlExtension(key)) {
            // 拡張子付きの指定は設定ファイル名として扱う（ディレクトリ判定の I/O も省く）。
            return false;
        }

        try {
            Path candidate = configFolder.resolve(key).normalize();
            if (!candidate.startsWith(configFolder)) {
                return false;
            }
            if (Files.isDirectory(candidate)) {
                return true;
            }

            Path parent = candidate.getParent();
            Path name = candidate.getFileName();
            if (parent == null || name == null || !Files.isDirectory(parent)) {
                return false;
            }

            String expected = name.toString();
            try (Stream<Path> children = Files.list(parent)) {
                return children.anyMatch(child -> child.getFileName() != null
                        && child.getFileName().toString().equalsIgnoreCase(expected)
                        && Files.isDirectory(child));
            }
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    /**
     * YAML の拡張子（{@code .yml} / {@code .yaml}）を持っているか判定する。
     */
    private static boolean hasYamlExtension(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(YAML_EXTENSION) || lower.endsWith(YAML_ALT_EXTENSION);
    }

    /**
     * メッセージ設定キーを {@code MessageData/} からの相対名へ正規化する。
     */
    private String normalizeMessageKey(String key) {
        String normalized = key.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        String prefix = MESSAGE_DIR_NAME + "/";
        while (normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * {@code config/MessageData/} が存在しない場合は作成する。
     *
     * @return ディレクトリが利用可能な場合は {@code true}
     */
    private boolean createMessageFolder() {
        if (Files.isDirectory(messageFolder)) {
            return true;
        }
        try {
            Files.createDirectories(messageFolder);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("メッセージフォルダの作成に失敗しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * 同梱の既定メッセージファイルを、未配置のものだけ {@code config/MessageData/} へ展開する。
     * <p>
     * サーバー管理者による編集内容を上書きしないよう、既存ファイルには触れない。
     */
    private void saveDefaultMessages() {
        for (String fileName : DEFAULT_MESSAGE_FILES) {
            Path target = messageFolder.resolve(fileName);
            if (Files.exists(target)) {
                continue;
            }
            try {
                plugin.saveResource(CONFIG_DIR_NAME + "/" + MESSAGE_DIR_NAME + "/" + fileName, false);
            } catch (IllegalArgumentException e) {
                // 開発実行などで同梱リソースが無い場合は、空のファイルを作らずスキップする。
                plugin.getLogger().fine(
                        "既定メッセージファイルを展開できませんでした: " + fileName + " (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * 同梱の既定設定ファイル（{@code config/gui/*.yml}）を、未配置のものだけ
     * {@code config/} へ展開する。
     * <p>
     * サーバー管理者による編集内容を上書きしないよう、既存ファイルには触れない。
     */
    private void saveDefaultConfigs() {
        for (String relativePath : DEFAULT_CONFIG_FILES) {
            Path target = configFolder.resolve(relativePath).normalize();
            if (!target.startsWith(configFolder) || Files.exists(target)) {
                continue;
            }
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                plugin.saveResource(CONFIG_DIR_NAME + "/" + relativePath.replace('\\', '/'), false);
            } catch (IllegalArgumentException e) {
                // 開発実行などで同梱リソースが無い場合は、空のファイルを作らずスキップする。
                plugin.getLogger().fine(
                        "既定設定ファイルを展開できませんでした: " + relativePath + " (" + e.getMessage() + ")");
            } catch (IOException e) {
                plugin.getLogger().warning(
                        "既定設定ファイルの展開に失敗しました: " + relativePath + " (" + e.getMessage() + ")");
            }
        }
    }

    private void loadFile(Path file) {
        String key = toKey(file);
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
            configs.put(key, config);

            String id = config.getString(ID_KEY);
            if (id != null && !id.isBlank()) {
                FileConfiguration previous = configsById.putIfAbsent(id, config);
                if (previous != null) {
                    plugin.getLogger().warning(
                            "YAML の id が重複しています: '" + id + "' (" + key + ")");
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "YAML の読み込みに失敗しました: " + key + " (" + e.getMessage() + ")");
        }
    }

    /**
     * {@code config/} ディレクトリが存在しない場合は作成する。
     * <p>
     * 同梱デフォルト YAML の展開（{@code saveResource}）は行わない。
     *
     * @return ディレクトリが利用可能な場合は {@code true}
     */
    private boolean createConfigFolder() {
        if (Files.isDirectory(configFolder)) {
            return true;
        }
        try {
            Files.createDirectories(configFolder);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("設定フォルダの作成に失敗しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * {@code config/} からの相対パスをキャッシュキーへ変換する。
     */
    private String toKey(Path file) {
        return configFolder.relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace(file.getFileSystem().getSeparator(), "/");
    }

    /**
     * 指定されたキーを正規化する。バックスラッシュを {@code /} に統一し、
     * 先頭の {@code ./} や {@code /} を除去する。{@code config/} 配下の絶対パスも受け付ける。
     */
    private String normalizeKey(String path) {
        String key = path.trim().replace('\\', '/');

        while (key.startsWith("./")) {
            key = key.substring(2);
        }

        try {
            Path resolved = configFolder.getFileSystem().getPath(key);
            if (resolved.isAbsolute()) {
                Path normalized = resolved.normalize();
                if (normalized.startsWith(configFolder)) {
                    return configFolder.relativize(normalized).toString().replace('\\', '/');
                }
            }
        } catch (InvalidPathException e) {
            // パスとして解釈できない場合は文字列のまま扱う。
        }

        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key;
    }

    private FileConfiguration findByFileName(String key) {
        return findByFileName(configs, key);
    }

    private static FileConfiguration findByFileName(Map<String, FileConfiguration> source, String key) {
        for (Map.Entry<String, FileConfiguration> entry : source.entrySet()) {
            String entryKey = entry.getKey();
            int slash = entryKey.lastIndexOf('/');
            String fileName = slash >= 0 ? entryKey.substring(slash + 1) : entryKey;
            if (fileName.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
            if (!hasYamlExtension(key) && stripExtension(fileName).equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isYamlFile(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        // File#isFile() を併用し、ディレクトリを設定ファイルとして扱わないことを保証する。
        return file.toFile().isFile() && hasYamlExtension(name.toString());
    }
}
