package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.skill.Skill;
import com.zeloria.player.ZeloriaClass;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code config/SkillData/<classId>/base/*.yml} を読み込んでスキル定義を管理する。
 * <p>
 * クラス ID はディレクトリ名から決定し、スキル ID は YAML の {@code id}
 * （未指定時はファイル名）から決定する。管理上のキーは
 * {@code <classId>:<skillId>} の複合キーであり、YAML 内にクラス ID は記述しない。
 * 派生スキル（{@code SkillData/<classId>/variant/*.yml}）は
 * {@link SkillVariantManager} が別途読み込む。
 * <p>
 * スキルはクラス定義（{@code ClassData/classes/*.yml} の {@code skills}）とスキルスロットで
 * 選択される想定であり、武器（Material）との紐付けは行わない。スキル定義 YAML の
 * {@code icon} はスキルスロット表示用の見た目としてのみ読み込む。
 * <p>
 * スキルアイテムを識別するための {@link NamespacedKey} を持つ
 * {@code PersistentDataContainer}（PDC）の読み取りのみ補助的に提供する。
 * 表示名や lore はデータ保存には使用せず、純粋な表示専用として扱う。
 */
public final class SkillManager {

    /** スキル定義を配置するディレクトリ（{@code config/} からの相対パス）。 */
    private static final String DIRECTORY = "SkillData/";

    /** 基本スキルを配置するクラス直下のディレクトリ名。 */
    private static final String BASE_SEGMENT = "base";

    /** アイコン未指定時のフォールバック素材。 */
    private static final Material FALLBACK_ICON = Material.PAPER;

    /** スキル ID を格納する PDC キー。 */
    public static final String PDC_SKILL_ID = "skill_id";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;
    private final NamespacedKey skillIdKey;

    /** 複合スキル ID（{@code <classId>:<skillId>}）→ スキル定義（不変・差し替え式）。 */
    private volatile Map<String, Skill> skills = Map.of();

    public SkillManager(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        this.skillIdKey = new NamespacedKey(plugin, PDC_SKILL_ID);
        reload();
    }

    /**
     * {@code config/SkillData/<classId>/base/} 配下の YAML をすべて読み直す。
     */
    public synchronized void reload() {
        Map<String, Skill> loaded = new LinkedHashMap<>();

        for (String key : yamlLoader.getKeys()) {
            if (!key.startsWith(DIRECTORY) || !isYaml(key)) {
                continue;
            }

            String classId = DefinitionParser.classIdOf(key, DIRECTORY, BASE_SEGMENT);
            if (classId.isEmpty()) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            try {
                Skill skill = parse(key, config, classId);
                Skill previous = loaded.putIfAbsent(skill.id(), skill);
                if (previous != null) {
                    plugin.getLogger().warning("スキル ID が重複しています: '"
                            + skill.id() + "' (" + key + ")");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("スキル定義の読み込みに失敗しました: "
                        + key + " (" + e.getMessage() + ")");
            }
        }

        this.skills = Collections.unmodifiableMap(loaded);
        plugin.getLogger().info("スキル定義を " + loaded.size() + " 件読み込みました。");
    }

    /**
     * 複合スキル ID（{@code <classId>:<skillId>}）から定義を取得する。
     */
    public Optional<Skill> find(String skillId) {
        String normalized = DefinitionParser.normalizeId(skillId);
        return normalized.isEmpty() ? Optional.empty() : Optional.ofNullable(skills.get(normalized));
    }

    /**
     * 読み込み済みのすべてのスキル定義を取得する（YAML の読み込み順）。
     */
    public Collection<Skill> all() {
        return List.copyOf(skills.values());
    }

    /**
     * 読み込み済みの複合スキル ID（{@code <classId>:<skillId>}）一覧を取得する。
     */
    public List<String> ids() {
        return List.copyOf(skills.keySet());
    }

    /**
     * 指定クラスが使用できるスキル定義を、YAML の {@code skills} 順で取得する。
     * <p>
     * クラス YAML の {@code skills} は複合キー {@code <classId>:<skillId>} で指定する。
     */
    public List<Skill> skillsFor(ZeloriaClass definition) {
        if (definition == null) {
            return List.of();
        }
        return definition.skills().stream()
                .map(skills::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * アイテムに付与された PDC からスキル ID を読み取る。
     * <p>
     * lore や displayName は判定に使用しない。
     *
     * @param item 対象アイテム
     * @return スキル ID。PDC が無い場合は空
     */
    public Optional<String> readSkillId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }

        String stored = meta.getPersistentDataContainer().get(skillIdKey, PersistentDataType.STRING);
        String normalized = DefinitionParser.normalizeId(stored);
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    /**
     * このマネージャが使用する PDC キーを取得する。
     */
    public NamespacedKey skillIdKey() {
        return skillIdKey;
    }

    private Skill parse(String key, FileConfiguration config, String classId) {
        String localId = DefinitionParser.normalizeId(
                DefinitionParser.text(config.getString("id"), DefinitionParser.fileName(key)));
        String id = DefinitionParser.qualifyId(classId, localId);
        String displayNameRaw = DefinitionParser.text(config.getString("displayName"), id);
        Component displayName = DefinitionParser.component(displayNameRaw, id);

        List<Component> description = DefinitionParser.description(config.get("description"));
        Material icon = DefinitionParser.material(
                config.getString("icon"), FALLBACK_ICON, "skill " + id, plugin.getLogger());
        List<String> upgrades = config.getStringList("upgrades");

        return new Skill(
                id,
                displayName,
                description,
                icon,
                config.getDouble("cooldown", 0.0),
                config.getDouble("manaCost", 0.0),
                config.getDouble("manaPercentCost", 0.0),
                config.getDouble("healthCost", 0.0),
                config.getDouble("healthPercentCost", 0.0),
                config.getDouble("baseDamageMultiplier", 1.0),
                config.getDouble("range", 0.0),
                config.getDouble("armorPenetration", 0.0),
                config.getDouble("lifesteal", 0.0),
                config.getString("particle", "CRIT"),
                config.getInt("particleCount", 12),
                config.getDouble("particleSpread", 0.5),
                config.getString("sound", ""),
                config.getDouble("soundVolume", 1.0),
                config.getDouble("soundPitch", 1.0),
                upgrades);
    }

    private static boolean isYaml(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }
}
