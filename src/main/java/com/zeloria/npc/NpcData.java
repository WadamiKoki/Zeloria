package com.zeloria.npc;

import com.zeloria.mob.MobData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.Objects;

/**
 * {@code config/NpcData/*.yml} から読み込んだ NPC 定義。
 * <p>
 * 1 ファイルが 1 種類の NPC に対応する。
 * <pre>
 * id: "guide"                     # 省略時はファイル名（拡張子なし）
 * name: "&lt;green&gt;案内人&lt;/green&gt;" # 表示名（MiniMessage 可）
 * mobType: VILLAGER               # スポーンさせるエンティティ種別
 * alwaysShowName: true            # 名前を常時表示するか
 * interactGui: "guide_menu"       # 右クリックで開く GUI の YAML 名（空なら開かない）
 * equipment:
 *   mainHand: AIR
 *   helmet: AIR
 *   chestplate: AIR
 *   leggings: AIR
 *   boots: AIR
 * </pre>
 *
 * @param id              NPC ID（コマンド引数・識別子）
 * @param name            表示名（MiniMessage 形式）
 * @param mobType         スポーンさせるエンティティ種別
 * @param alwaysShowName  名前を常時表示するか
 * @param interactGui     右クリックで開く GUI の YAML 名（未指定なら空文字）
 * @param equipment       装備設定
 */
public record NpcData(
        String id,
        String name,
        EntityType mobType,
        boolean alwaysShowName,
        String interactGui,
        NpcEquipment equipment
) {

    /** {@code mobType} が未指定・解決不能のときに使用する既定のエンティティ種別。 */
    public static final EntityType DEFAULT_MOB_TYPE = EntityType.VILLAGER;

    private static final String YAML_EXTENSION = ".yml";

    public NpcData {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name;
        mobType = mobType == null ? DEFAULT_MOB_TYPE : mobType;
        interactGui = interactGui == null ? "" : interactGui.trim();
        equipment = equipment == null ? NpcEquipment.EMPTY : equipment;
    }

    /**
     * YAML 設定から NPC 定義を生成する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code NpcData/guide.yml}）
     * @param config    読み込み済み設定
     * @return NPC 定義
     */
    public static NpcData fromConfig(String sourceKey, FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        String fallbackId = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallbackId);
        String name = text(config.getString("name"), id);

        return new NpcData(
                id,
                name,
                resolveMobType(config.getString("mobType")),
                config.getBoolean("alwaysShowName", true),
                text(config.getString("interactGui"), ""),
                NpcEquipment.fromConfig(config.getConfigurationSection("equipment"))
        );
    }

    /**
     * スポーンに使用するエンティティ種別を返す。
     */
    public EntityType spawnableMobType() {
        return mobType == null ? DEFAULT_MOB_TYPE : mobType;
    }

    /**
     * 右クリックで開く GUI が設定されているか判定する。
     */
    public boolean hasInteractGui() {
        return !interactGui.isBlank();
    }

    /**
     * {@code mobType} を {@link EntityType} へ解決する。
     * <p>
     * {@code villager} / {@code minecraft:villager} / {@code VILLAGER} のいずれも受け付け、
     * 未指定・解決不能の場合は {@link #DEFAULT_MOB_TYPE} を返す。
     */
    private static EntityType resolveMobType(String raw) {
        EntityType resolved = MobData.resolveEntityType(raw);
        return resolved == null ? DEFAULT_MOB_TYPE : resolved;
    }

    /**
     * {@code NpcData/<ファイル名>.yml} から拡張子なしファイル名を取り出す。
     */
    private static String fallbackId(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return "";
        }
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        return fileName.toLowerCase(Locale.ROOT).endsWith(YAML_EXTENSION)
                ? fileName.substring(0, fileName.length() - YAML_EXTENSION.length())
                : fileName;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
