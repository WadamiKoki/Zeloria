package com.zeloria.skill;

import com.zeloria.core.YamlLoader;
import com.zeloria.core.Zeloria;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code config/SkillData/<classId>/variant/*.yml} を読み込み、派生スキル定義を管理する。
 * <p>
 * クラス ID はディレクトリ名から決定し、派生スキル ID は YAML の {@code variantId}
 * （未指定時はファイル名）から決定する。管理上のキーは
 * {@code <classId>:<variantId>} の複合キーであり、YAML 内にクラス ID は記述しない。
 * 置換対象は {@code base-skill-id} で指定された基本スキルを
 * {@code <classId>:<base-skill-id>} として解決する。
 * <p>
 * {@code /admin reload} から {@link #reload()} を呼ぶと最新の YAML に差し替わる。
 */
public final class SkillVariantManager {

    /** スキル定義を配置するディレクトリ（{@code config/} からの相対パス）。 */
    private static final String DIRECTORY = "SkillData/";

    /** 派生スキルを配置するクラス直下のディレクトリ名。 */
    private static final String VARIANT_SEGMENT = "variant";

    /** 実行可能なアクション種別。 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "SHOOT_PROJECTILE",
            "DAMAGE",
            "PARTICLE",
            "SOUND",
            "TELEPORT",
            "HEAL",
            "APPLY_EFFECT");

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    /** 複合派生スキル ID（{@code <classId>:<variantId>}）→ 定義（不変・差し替え式）。 */
    private volatile Map<String, SkillVariantData> variants = Map.of();

    public SkillVariantManager(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * {@code config/SkillData/<classId>/variant/} 配下の YAML をすべて読み直す。
     */
    public synchronized void reload() {
        Map<String, SkillVariantData> loaded = new LinkedHashMap<>();

        for (String key : yamlLoader.getKeysUnder(DIRECTORY)) {
            String classId = DefinitionParser.classIdOf(key, DIRECTORY, VARIANT_SEGMENT);
            if (classId.isEmpty()) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            try {
                SkillVariantData variant = parse(key, config, classId);
                if (variant == null) {
                    continue;
                }
                SkillVariantData previous = loaded.putIfAbsent(variant.id(), variant);
                if (previous != null) {
                    plugin.getLogger().warning("派生スキル ID が重複しています: '"
                            + variant.id() + "' (" + key + ")");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("派生スキル定義の読み込みに失敗しました: "
                        + key + " (" + e.getMessage() + ")");
            }
        }

        this.variants = Collections.unmodifiableMap(loaded);
        plugin.getLogger().info("派生スキルを " + variants.size() + " 件読み込みました。");
    }

    /**
     * 複合派生スキル ID（{@code <classId>:<variantId>}）から定義を取得する。
     */
    public Optional<SkillVariantData> find(String variantId) {
        String key = normalizeId(variantId);
        return key.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(variants.get(key));
    }

    /**
     * 複合派生スキル ID（{@code <classId>:<variantId>}）が登録されているか判定する。
     */
    public boolean has(String variantId) {
        String key = normalizeId(variantId);
        return !key.isEmpty() && variants.containsKey(key);
    }

    /**
     * 読み込み済みのすべての派生スキルを返す。
     */
    public Collection<SkillVariantData> all() {
        return variants.values();
    }

    private SkillVariantData parse(String key, FileConfiguration config, String classId) {
        String fallbackId = fileNameWithoutExtension(key);
        String localId = normalizeId(config.getString("variantId", fallbackId));
        String variantId = DefinitionParser.qualifyId(classId, localId);
        String baseSkillId = DefinitionParser.qualifyId(
                classId, normalizeId(config.getString("base-skill-id", "")));

        if (variantId.isEmpty()) {
            plugin.getLogger().warning("派生スキルの variantId が空です: " + key);
            return null;
        }
        if (baseSkillId.isEmpty()) {
            plugin.getLogger().warning("派生スキル '" + variantId
                    + "' の base-skill-id が未設定です: " + key);
            return null;
        }

        String displayName = config.getString("displayName", variantId);
        String description = config.getString("description", "");

        List<VariantAction> actions = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("actions")) {
            Object typeValue = raw.get("type");
            String type = typeValue == null
                    ? ""
                    : String.valueOf(typeValue).trim().toUpperCase(Locale.ROOT);
            if (type.isEmpty()) {
                plugin.getLogger().warning("派生スキル '" + variantId
                        + "' に type のないアクションがあります: " + key);
                continue;
            }
            if (!SUPPORTED_TYPES.contains(type)) {
                plugin.getLogger().warning("派生スキル '" + variantId
                        + "' のアクション種別 '" + type + "' は未対応のためスキップします: " + key);
                continue;
            }

            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                String paramKey = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                if (paramKey == null || "type".equalsIgnoreCase(paramKey)) {
                    continue;
                }
                params.put(paramKey, entry.getValue());
            }
            actions.add(new VariantAction(type, params));
        }

        if (actions.isEmpty()) {
            plugin.getLogger().warning("派生スキル '" + variantId
                    + "' に有効なアクションがありません: " + key);
        }

        return new SkillVariantData(variantId, baseSkillId, displayName, description, actions);
    }

    private static String fileNameWithoutExtension(String key) {
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

}
