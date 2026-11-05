package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * {@code config/MaterialData/*.yml} から読み込んだ素材アイテム定義。
 * <p>
 * 基本フォーマット（{@link ItemData}）に加えて、1 スロットあたりのスタック上限
 * {@code max_stack_size}（1〜9999）を保持する。
 * <pre>
 * id: "iron_ore"
 * name: "&lt;white&gt;鉄鉱石"
 * material: IRON_ORE
 * lore:
 *   - "&lt;gray&gt;製錬すると鉄インゴットになる。"
 * sell_price: 12
 * max_stack_size: 9999
 * </pre>
 */
public final class MaterialData extends ItemData {

    /** 指定可能なスタック上限の最小値。 */
    public static final int MIN_STACK_SIZE = 1;

    /** 指定可能なスタック上限の最大値。 */
    public static final int MAX_STACK_SIZE = 9999;

    /** {@code max_stack_size} 未指定時の既定値。 */
    public static final int DEFAULT_STACK_SIZE = 64;

    private final int maxStackSize;

    public MaterialData(String id, String name, Material material, List<String> lore,
                        long sellPrice, String sourceKey, int maxStackSize) {
        this(id, name, material, lore, sellPrice, sourceKey, maxStackSize, null);
    }

    public MaterialData(String id, String name, Material material, List<String> lore,
                        long sellPrice, String sourceKey, int maxStackSize, String itemModel) {
        super(id, name, material, lore, sellPrice, sourceKey, itemModel);
        this.maxStackSize = clamp(maxStackSize);
    }

    /**
     * YAML 設定から素材定義を生成する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code MaterialData/iron_ore.yml}）
     * @param config    読み込み済み設定
     * @return 素材定義
     * @throws IllegalArgumentException {@code material} が未指定・不正な場合
     */
    public static MaterialData fromConfig(String sourceKey, FileConfiguration config) {
        Material material = readMaterial(config);
        String fallback = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallback);
        String name = text(config.getString("name"), id);

        return new MaterialData(
                id,
                name,
                material,
                readLore(config),
                config.getLong("sell_price", 0L),
                sourceKey,
                config.getInt("max_stack_size", DEFAULT_STACK_SIZE),
                config.getString("item_model")
        );
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.MATERIAL;
    }

    @Override
    public int maxStackSize() {
        return maxStackSize;
    }

    private static int clamp(int value) {
        return Math.max(MIN_STACK_SIZE, Math.min(MAX_STACK_SIZE, value));
    }
}
