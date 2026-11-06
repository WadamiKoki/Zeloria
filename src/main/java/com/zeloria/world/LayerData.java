package com.zeloria.world;

import com.zeloria.npc.NpcPlacement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code config/LayerData/*.yml} を表すレイヤー設定モデル。
 * <p>
 * 既存のフィールド（{@code id} / {@code name} / {@code templateId} /
 * {@code maxInstances} / {@code isShared}）に加え、入場演出（{@code display}）、
 * ポータル定義（{@code portals}）、NPC 配置（{@code npcs}）、
 * モブスポナー定義（{@code spawners}）を保持する。
 */
public class LayerData {

    private final String sourceKey;
    private final String id;
    private final String name;
    private final String templateId;
    private final int maxInstances;
    private final boolean shared;
    private final LayerDisplay display;
    private final Map<String, PortalDefinition> portals;
    private final List<NpcPlacement> npcs;
    private final List<SpawnerPlacement> spawners;

    private LayerData(String sourceKey, String id, String name, String templateId,
                      int maxInstances, boolean shared, LayerDisplay display,
                      Map<String, PortalDefinition> portals, List<NpcPlacement> npcs,
                      List<SpawnerPlacement> spawners) {
        this.sourceKey = sourceKey;
        this.id = id;
        this.name = name;
        this.templateId = templateId;
        this.maxInstances = maxInstances;
        this.shared = shared;
        this.display = display;
        this.portals = Collections.unmodifiableMap(new LinkedHashMap<>(portals));
        this.npcs = List.copyOf(npcs);
        this.spawners = List.copyOf(spawners);
    }

    /**
     * YAML 設定からレイヤーデータを生成する。
     *
     * @param sourceKey キャッシュキー（例: {@code LayerData/Hub.yml}）
     * @param config    設定
     * @return レイヤーデータ
     */
    public static LayerData fromConfig(String sourceKey, FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        String fallbackId = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallbackId);
        String name = text(config.getString("name"), id);
        String templateId = text(config.getString("templateId"), id);

        int maxInstances = config.getInt("maxInstances", 1);
        boolean shared = config.getBoolean("isShared", false);

        LayerDisplay display = LayerDisplay.fromConfig(config.getConfigurationSection("display"));

        Map<String, PortalDefinition> portals = new LinkedHashMap<>();
        ConfigurationSection portalsSection = config.getConfigurationSection("portals");
        if (portalsSection != null) {
            for (String portalId : portalsSection.getKeys(false)) {
                ConfigurationSection portalSection = portalsSection.getConfigurationSection(portalId);
                if (portalSection != null) {
                    portals.put(portalId, PortalDefinition.fromConfig(portalId, portalSection));
                }
            }
        }

        return new LayerData(sourceKey, id, name, templateId, maxInstances, shared, display, portals,
                parseNpcs(config), parseSpawners(config));
    }

    /**
     * {@code npcs} セクションを解析する。
     * <p>
     * 不正なエントリ（{@code npcId} が空）は読み飛ばす。
     */
    private static List<NpcPlacement> parseNpcs(FileConfiguration config) {
        List<NpcPlacement> placements = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("npcs")) {
            NpcPlacement placement = NpcPlacement.fromMap(raw);
            if (placement.isValid()) {
                placements.add(placement);
            }
        }
        return placements;
    }

    /**
     * {@code spawners} セクションを解析する。
     * <p>
     * 不正なエントリ（{@code spawnerId} が空）は読み飛ばす。
     */
    private static List<SpawnerPlacement> parseSpawners(FileConfiguration config) {
        List<SpawnerPlacement> placements = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("spawners")) {
            SpawnerPlacement placement = SpawnerPlacement.fromMap(raw);
            if (placement.isValid()) {
                placements.add(placement);
            }
        }
        return placements;
    }

    /**
     * キャッシュキー（{@code config/} からの相対パス）。
     */
    public String getSourceKey() {
        return sourceKey;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTemplateId() {
        return templateId;
    }

    public int getMaxInstances() {
        return maxInstances;
    }

    public boolean isShared() {
        return shared;
    }

    public LayerDisplay getDisplay() {
        return display;
    }

    /**
     * ポータル定義（ポータル ID をキーとした不変マップ）。
     */
    public Map<String, PortalDefinition> getPortals() {
        return portals;
    }

    /**
     * レイヤー内に配置する NPC の定義（不変リスト）。
     * <p>
     * レイヤーワールドのロード時に {@code LayerNpcService} が参照する。
     */
    public List<NpcPlacement> getNpcs() {
        return npcs;
    }

    /**
     * レイヤー内に設置するモブスポナーの定義（不変リスト）。
     * <p>
     * レイヤーワールドのロード時に {@code SpawnerManager} が参照する。
     */
    public List<SpawnerPlacement> getSpawners() {
        return spawners;
    }

    /**
     * ID・テンプレート ID・ファイル名のいずれかに一致するか判定する（大文字小文字を無視）。
     */
    public boolean matches(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return id.equalsIgnoreCase(value)
                || templateId.equalsIgnoreCase(value)
                || fallbackId(sourceKey).equalsIgnoreCase(value);
    }

    /**
     * ワールド名がこのレイヤーのインスタンスか判定する。
     * <p>
     * {@code templateId} と完全一致する場合に加え、
     * {@code <templateId>_instance...} 形式のインスタンス ID も対象とする。
     *
     * @param worldName ワールド名（インスタンス ID）
     */
    public boolean matchesWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        if (worldName.equalsIgnoreCase(templateId)) {
            return true;
        }
        String prefix = templateId.toLowerCase(Locale.ROOT) + "_instance";
        return worldName.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    private static String fallbackId(String sourceKey) {
        if (sourceKey == null) {
            return "";
        }
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        return fileName.toLowerCase(Locale.ROOT).endsWith(".yml")
                ? fileName.substring(0, fileName.length() - ".yml".length())
                : fileName;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
