package com.zeloria.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
 * 共有設定フォルダ直下の YAML ファイルを
 * 再帰的に読み込み、メモリ上にキャッシュする構成クラス。
 * <p>
 * 読み込み先は {@code channel.yml} の {@code shared-config-path} を解決した
 * 共有設定フォルダ自体（例: {@code ZeloriaServer/config}）で、全サーバー（チャンネル）が
 * 同じ設定を参照する。キャッシュのキーは共有設定フォルダからの相対パス
 * （区切り文字は {@code /}）で、例: {@code LayerData/Hub.yml}。
 * {@code getConfig(String)} では相対パスに加えて
 * ファイル名（拡張子なし）や YAML 内の {@code id} でも参照できる。
 * <p>
 * 読み込みはファイル I/O を伴うため、{@link #loadAll()} / {@link #reload()} は
 * サーバー起動時などメインスレッドから呼び出すことを想定している。
 * 読み込み後の {@link #getConfig(String)} はスレッドセーフ。
 * <p>
 * {@code config/MessageData/} 配下のメッセージ設定は
 * {@link #getMessageConfig(String)} から相対パス・拡張子なし名・{@code id} の
 * いずれでも参照できるよう、一括読み込み時に専用キャッシュへ展開する。
 * <p>
 * 同梱リソース（{@code src/main/resources/config/}）はテンプレートであり、
 * 本クラスは書き込み（ファイル作成・{@code saveResource(...)}）を一切行わない。
 * 初回起動前に、必要な YAML を手動で共有設定フォルダの {@code config/} へ
 * コピーしておくこと（同梱の {@code config/README.md} を参照）。
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

    private final JavaPlugin plugin;

    /**
     * 設定フォルダ: 共有設定フォルダ自体（例: {@code ZeloriaServer/config}）
     */
    private final Path configFolder;

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
        this.configFolder = resolveConfigFolder(plugin);
    }

    /**
     * YAML を読み込む設定フォルダを解決する。
     * <p>
     * {@link Zeloria} では {@code channel.yml} の {@code shared-config-path} を
     * 解決した共有設定フォルダ自体を設定フォルダとして扱う。
     * {@link Zeloria} 以外のプラグイン実装では、従来どおりプラグインの
     * データフォルダ直下の {@code config/} を使用する。
     *
     * @param plugin プラグイン
     * @return 設定フォルダ
     */
    private static Path resolveConfigFolder(JavaPlugin plugin) {
        if (plugin instanceof Zeloria zeloria) {
            File sharedFolder = zeloria.getSharedConfigFolder();
            if (sharedFolder != null) {
                return sharedFolder.toPath()
                        .toAbsolutePath()
                        .normalize();
            }
        }
        return plugin.getDataFolder().toPath()
                .resolve(CONFIG_DIR_NAME)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 共有設定フォルダ直下のすべての YAML ファイルを読み込む。
     * <p>
     * 同梱リソース（{@code src/main/resources/config/}）はテンプレートであり、
     * 本クラスは書き込み（ファイル作成・{@code saveResource(...)}）を行わない。
     * 初回起動前に、必要な YAML を手動で共有設定フォルダ（例:
     * {@code ZeloriaServer/config/}）へコピーしておくこと。
     * 必須 YAML の存在確認は {@link Zeloria#onEnable()} が行う。
     */
    public synchronized void loadAll() {
        reload();
    }

    /**
     * 既存のキャッシュをすべてクリアし、共有設定フォルダの {@code config/} 配下の
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
     * キャッシュをクリアし、共有設定フォルダの {@code config/} 配下の
     * YAML ファイルを再読み込みする。
     * <p>
     * 読み込みに失敗したファイルは警告を出してスキップする。共有設定フォルダは
     * 読み取り専用として扱い、ディレクトリの作成は行わない。
     */
    public synchronized void reload() {
        configs.clear();
        configsById.clear();
        messageConfigs.clear();
        messageConfigsById.clear();

        if (!Files.isDirectory(configFolder)) {
            plugin.getLogger().severe("共有設定フォルダが見つかりません: " + configFolder);
            return;
        }

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
