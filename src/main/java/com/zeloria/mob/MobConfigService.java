package com.zeloria.mob;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code config/MobData/*.yml} を解析して {@link MobData} の一覧を提供するサービス。
 * <p>
 * {@link YamlLoader} が読み込んだ YAML から {@code MobData/} 配下のものを抽出し、
 * モブ ID・ファイル名のどちらでも参照できるようキャッシュする。
 * {@code /admin reload} 時は {@link #reload()} を呼ぶことで最新の設定を反映できる。
 */
public class MobConfigService {

    private static final String KEY_PREFIX = "MobData/";
    private static final String YAML_EXTENSION = ".yml";
    private static final String YAML_ALT_EXTENSION = ".yaml";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private final List<MobData> mobs = new CopyOnWriteArrayList<>();
    private final Map<String, MobData> byId = new ConcurrentHashMap<>();

    public MobConfigService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * {@code MobData/} 配下の YAML を再解析し、キャッシュを置き換える。
     */
    public synchronized void reload() {
        mobs.clear();
        byId.clear();

        for (String key : yamlLoader.getKeys()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            MobData data;
            try {
                data = MobData.fromConfig(key, config);
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "MobData の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
                continue;
            }

            mobs.add(data);
            register(data.id(), data, key);
            register(fileNameOf(key), data, key);
        }

        plugin.getLogger().info("MobData を " + mobs.size() + " 件読み込みました。");
    }

    /**
     * モブ ID（またはファイル名）からモブ定義を取得する（大文字小文字は区別しない）。
     *
     * @param id モブ ID
     * @return モブ定義。見つからない場合は空
     */
    public Optional<MobData> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 読み込み済みのモブ定義を取得する（不変ビュー）。
     */
    public List<MobData> getAll() {
        return List.copyOf(mobs);
    }

    /**
     * 読み込み済みのモブ ID 一覧を取得する（タブ補完用）。
     */
    public List<String> getIds() {
        return mobs.stream().map(MobData::id).toList();
    }

    /**
     * 読み込み済みのモブ定義数。
     */
    public int size() {
        return mobs.size();
    }

    private void register(String key, MobData data, String sourceKey) {
        if (key == null || key.isBlank()) {
            return;
        }
        MobData previous = byId.putIfAbsent(key.toLowerCase(Locale.ROOT), data);
        if (previous != null && previous != data) {
            // 重複は先勝ち。運用者が気づけるよう警告のみ行う。
            plugin.getLogger().warning(
                    "MobData のキーが重複しています: '" + key + "' (" + sourceKey + ")");
        }
    }

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        String fileName = slash < 0 ? key : key.substring(slash + 1);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(YAML_ALT_EXTENSION)) {
            return fileName.substring(0, fileName.length() - YAML_ALT_EXTENSION.length());
        }
        return lower.endsWith(YAML_EXTENSION)
                ? fileName.substring(0, fileName.length() - YAML_EXTENSION.length())
                : fileName;
    }
}
