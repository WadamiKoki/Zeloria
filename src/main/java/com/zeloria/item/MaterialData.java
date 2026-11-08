package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * {@code config/ItemData/material/*.yml} から読み込んだ素材アイテム定義。
 * <p>
 * 素材は {@link ItemData} の共通フォーマットのみを持ち、スタック上限は
 * Minecraft の {@link Material} 既定値に従う。
 * <pre>
 * id: "iron_ore"
 * name: "&lt;white&gt;鉄鉱石"
 * material: IRON_ORE
 * lore:
 *   - "&lt;gray&gt;製錬すると鉄インゴットになる。"
 * sell_price: 12
 * </pre>
 */
public final class MaterialData extends ItemData {

    public MaterialData(String id, String name, Material material, List<String> lore,
                        long sellPrice, String sourceKey) {
        super(id, name, material, lore, sellPrice, sourceKey);
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
                sourceKey
        );
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.MATERIAL;
    }
}
