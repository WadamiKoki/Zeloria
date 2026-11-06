package com.zeloria.mob;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Objects;

/**
 * {@code config/SpawnerData/*.yml} から読み込んだスポナー定義。
 * <p>
 * {@code LayerData} 側は「スポナー ID と座標」だけを保持し、湧き数・間隔などの
 * 挙動はこの定義で管理する。
 * <pre>
 * id: "goblin_camp"
 * mobId: "goblin"
 * level: 3
 * spawnRateSeconds: 30
 * maxAlive: 3
 * spawnLimit: 10
 * spawnRadius: 5.0
 * display:
 *   type: ITEM_DISPLAY      # ITEM_DISPLAY または BLOCK_DISPLAY
 *   material: SPAWNER
 *   itemModel: "zeloria:goblin_spawner"
 *   scale: 1.0
 * </pre>
 * 負荷対策として {@code spawnRateSeconds} は最低 {@value #MIN_SPAWN_RATE_SECONDS} 秒、
 * {@code maxAlive} は最大 {@value #MAX_ALIVE} 体にクランプする。
 *
 * @param id               スポナー ID（LayerData の spawnerId と一致させる）
 * @param mobId            スポーンさせるモブ定義の ID
 * @param level            スポーンさせるモブのレベル
 * @param spawnRateSeconds スポーン判定の間隔（秒）
 * @param maxAlive         同時に生存させる最大数
 * @param spawnLimit       1 回の判定でスポーンさせる最大数
 * @param spawnRadius      プレイヤーを検知する半径（ブロック）
 * @param display          スポナーの見た目
 */
public record SpawnerData(
        String id,
        String mobId,
        int level,
        int spawnRateSeconds,
        int maxAlive,
        int spawnLimit,
        double spawnRadius,
        Display display
) {

    /** {@code spawnRateSeconds} の下限（秒）。 */
    public static final int MIN_SPAWN_RATE_SECONDS = 30;

    /** {@code maxAlive} の上限。 */
    public static final int MAX_ALIVE = 5;

    /** {@code spawnLimit} の既定値。 */
    public static final int DEFAULT_SPAWN_LIMIT = 1;

    /** {@code spawnRadius} の既定値（ブロック）。 */
    public static final double DEFAULT_SPAWN_RADIUS = 24.0;

    /** {@code spawnRadius} の下限（ブロック）。 */
    public static final double MIN_SPAWN_RADIUS = 1.0;

    /** {@code spawnRadius} の上限（ブロック）。 */
    public static final double MAX_SPAWN_RADIUS = 128.0;

    /** 見た目の既定スケール。 */
    public static final float DEFAULT_SCALE = 1.0f;

    /** 見た目の既定マテリアル。 */
    public static final Material DEFAULT_DISPLAY_MATERIAL = Material.SPAWNER;

    private static final String YAML_EXTENSION = ".yml";
    private static final String YAML_ALT_EXTENSION = ".yaml";

    public SpawnerData {
        id = text(id, "");
        mobId = text(mobId, "");
        level = Math.max(1, level);
        spawnRateSeconds = Math.max(MIN_SPAWN_RATE_SECONDS, spawnRateSeconds);
        maxAlive = Math.max(1, Math.min(MAX_ALIVE, maxAlive));
        spawnLimit = Math.max(1, spawnLimit);
        if (spawnRadius <= 0.0) {
            spawnRadius = DEFAULT_SPAWN_RADIUS;
        } else {
            spawnRadius = Math.max(MIN_SPAWN_RADIUS, Math.min(MAX_SPAWN_RADIUS, spawnRadius));
        }
        display = display == null ? Display.DEFAULT : display;
    }

    /**
     * スポーン判定の間隔を tick で返す。
     */
    public int spawnRateTicks() {
        return spawnRateSeconds * 20;
    }

    /**
     * スポナー定義として有効か判定する。
     */
    public boolean isValid() {
        return !id.isBlank() && !mobId.isBlank();
    }

    /**
     * YAML 設定からスポナー定義を生成する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code SpawnerData/goblin_camp.yml}）
     * @param config    読み込み済み設定
     * @return スポナー定義
     */
    public static SpawnerData fromConfig(String sourceKey, FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        String id = text(config.getString("id"), fallbackId(sourceKey));
        String mobId = text(config.getString("mobId"),
                text(config.getString("mob_id"), ""));

        return new SpawnerData(
                id,
                mobId,
                config.getInt("level", 1),
                config.getInt("spawnRateSeconds", MIN_SPAWN_RATE_SECONDS),
                config.getInt("maxAlive", MAX_ALIVE),
                config.getInt("spawnLimit", DEFAULT_SPAWN_LIMIT),
                config.getDouble("spawnRadius", DEFAULT_SPAWN_RADIUS),
                Display.fromConfig(config.getConfigurationSection("display"))
        );
    }

    /**
     * スポナーの見た目定義。
     *
     * @param type      表示方式
     * @param material  表示に使用する Material
     * @param itemModel ITEM_DISPLAY 用の item_model（任意）
     * @param scale     表示スケール
     */
    public record Display(DisplayType type, Material material, String itemModel, float scale) {

        /** 設定が無い場合の既定表示。 */
        public static final Display DEFAULT = new Display(
                DisplayType.ITEM_DISPLAY, DEFAULT_DISPLAY_MATERIAL, null, DEFAULT_SCALE);

        public Display {
            type = type == null ? DisplayType.ITEM_DISPLAY : type;
            material = material == null || material.isAir() ? DEFAULT_DISPLAY_MATERIAL : material;
            itemModel = text(itemModel, null);
            scale = scale <= 0.0f ? DEFAULT_SCALE : Math.min(scale, 16.0f);
        }

        /**
         * {@code display} セクションから読み込む。
         */
        public static Display fromConfig(ConfigurationSection section) {
            if (section == null) {
                return DEFAULT;
            }
            DisplayType type = DisplayType.parse(section.getString("type"));
            Material material = parseMaterial(section.getString("material"), DEFAULT_DISPLAY_MATERIAL);
            return new Display(
                    type,
                    material,
                    text(section.getString("itemModel"), text(section.getString("item_model"), null)),
                    (float) section.getDouble("scale", DEFAULT_SCALE));
        }
    }

    /**
     * スポナーの表示方式。
     */
    public enum DisplayType {
        ITEM_DISPLAY,
        BLOCK_DISPLAY;

        /**
         * 文字列から表示方式を解決する（不明な場合は ITEM_DISPLAY）。
         */
        public static DisplayType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ITEM_DISPLAY;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            return normalized.contains("BLOCK") ? BLOCK_DISPLAY : ITEM_DISPLAY;
        }
    }

    private static Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim());
        return material == null || material.isAir() ? fallback : material;
    }

    private static String fallbackId(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return "";
        }
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(YAML_ALT_EXTENSION)) {
            return fileName.substring(0, fileName.length() - YAML_ALT_EXTENSION.length());
        }
        return lower.endsWith(YAML_EXTENSION)
                ? fileName.substring(0, fileName.length() - YAML_EXTENSION.length())
                : fileName;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

