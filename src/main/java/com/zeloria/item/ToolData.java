package com.zeloria.item;

import com.zeloria.gathering.GatheringType;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/**
 * {@code config/ItemData/tool/<種別>/*.yml} から読み込んだ採取ツール定義。
 * <p>
 * ツールは採取できるギャザリング種別と、使用に必要な採取レベルを保持する。
 * 見た目は {@code material} のバニラテクスチャをそのまま使用し、
 * カスタムモデルデータは使用しない。
 * <pre>
 * id: "test"
 * name: "&lt;yellow&gt;テスト用ツルハシ"
 * material: WOODEN_PICKAXE
 * gathering_type: MINING
 * required_level: 1
 * lore:
 *   - "&lt;gray&gt;テスト用。"
 * sell_price: 10
 * </pre>
 */
public final class ToolData extends ItemData {

    private final GatheringType gatheringType;
    private final int requiredLevel;

    public ToolData(String id, String name, Material material, List<String> lore,
                    long sellPrice, String sourceKey,
                    GatheringType gatheringType, int requiredLevel) {
        super(id, name, material, lore, sellPrice, sourceKey);
        this.gatheringType = gatheringType;
        this.requiredLevel = Math.max(1, requiredLevel);
    }

    /**
     * YAML 設定から採取ツール定義を生成する。
     * <p>
     * {@code gathering_type} が未指定の場合は、読み込み元パス
     * （{@code ItemData/tool/<種別>/...}）から種別を解決する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code ItemData/tool/mining/test.yml}）
     * @param config    読み込み済み設定
     * @return 採取ツール定義
     * @throws IllegalArgumentException {@code material} が不正、または種別が解決できない場合
     */
    public static ToolData fromConfig(String sourceKey, FileConfiguration config) {
        Material material = readMaterial(config);
        String fallback = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallback);
        String name = text(config.getString("name"), id);

        GatheringType gatheringType = resolveGatheringType(sourceKey, config);
        if (gatheringType == null) {
            throw new IllegalArgumentException("gathering_type が不正です: "
                    + config.getString("gathering_type"));
        }
        int requiredLevel = config.contains("required_level")
                ? config.getInt("required_level", 1)
                : config.getInt("requiredLevel", 1);

        return new ToolData(
                id,
                name,
                material,
                readLore(config),
                config.getLong("sell_price", 0L),
                sourceKey,
                gatheringType,
                requiredLevel
        );
    }

    /**
     * 採取対象のギャザリング種別を取得する。
     */
    public GatheringType gatheringType() {
        return gatheringType;
    }

    /**
     * 使用に必要な採取レベルを取得する。
     */
    public int requiredLevel() {
        return requiredLevel;
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.TOOL;
    }

    private static GatheringType resolveGatheringType(String sourceKey, FileConfiguration config) {
        GatheringType fromConfig = GatheringType.fromKey(
                config.getString("gathering_type", config.getString("gatheringType")));
        if (fromConfig != null) {
            return fromConfig;
        }
        // ItemData/tool/<種別>/<ファイル>.yml の 3 番目のセグメントから解決する。
        String[] segments = sourceKey == null
                ? new String[0]
                : sourceKey.toLowerCase(Locale.ROOT).split("/");
        if (segments.length >= 4 && "tool".equals(segments[1])) {
            return GatheringType.fromKey(segments[2]);
        }
        return null;
    }
}
