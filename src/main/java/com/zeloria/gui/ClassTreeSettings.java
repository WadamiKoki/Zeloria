package com.zeloria.gui;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config/GuiData/class_tree.yml} を表すクラスツリー GUI の設定。
 * <p>
 * タイトル・表示用マテリアル・効果音を保持する。ノードの位置や親子関係は
 * {@code ClassData/tree/tree.yml} と {@code ClassData/tree/<classId>.yml} が担当し、
 * このクラスには含めない。
 *
 * @param title     GUI タイトル（MiniMessage）。未指定の場合は {@code null}
 * @param materials 表示用マテリアル（キー → 解決済み Material）
 * @param sounds    効果音（キー → 効果音設定）
 */
public record ClassTreeSettings(
        String title,
        Map<String, GuiMaterial> materials,
        Map<String, GuiSound> sounds
) {

    public ClassTreeSettings {
        title = title == null || title.isBlank() ? null : title;
        materials = materials == null ? Map.of() : Map.copyOf(materials);
        sounds = sounds == null ? Map.of() : Map.copyOf(sounds);
    }

    /**
     * 既定設定（{@code class_tree.yml} が読み込めない場合のフォールバック）。
     * <p>
     * タイトルは {@code null} のままにし、{@code ClassTreeData} / {@code tree.yml} の
     * タイトルを優先させる。マテリアルと効果音は従来のハードコード値を維持する。
     */
    public static ClassTreeSettings defaults() {
        Map<String, GuiMaterial> materials = new LinkedHashMap<>();
        materials.put("filler", new GuiMaterial(Material.GRAY_STAINED_GLASS_PANE, 0));
        materials.put("reset", new GuiMaterial(Material.BARRIER, 0));
        materials.put("scroll", new GuiMaterial(Material.ARROW, 0));
        materials.put("fallback", new GuiMaterial(Material.PAPER, 0));
        materials.put("close", new GuiMaterial(Material.COMPASS, 0));
        materials.put("accept", new GuiMaterial(Material.RED_CONCRETE, 0));
        materials.put("cancel", new GuiMaterial(Material.LIME_CONCRETE, 0));

        return new ClassTreeSettings(
                null,
                materials,
                Map.of(
                        "open", new GuiSound("block.ender_chest.open", 0.8f, 1.2f),
                        "click", new GuiSound("ui.button.click", 0.6f, 1.4f),
                        "error", new GuiSound("entity.villager.no", 0.7f, 1.0f)
                )
        );
    }

    /**
     * 指定名のマテリアルを取得する。
     *
     * @param name     マテリアル名（例: {@code filler}）
     * @param fallback 未定義の場合の代替値
     * @return 解決済みマテリアル
     */
    public GuiMaterial material(String name, GuiMaterial fallback) {
        if (name == null) {
            return fallback;
        }
        return materials.getOrDefault(name, fallback);
    }

    /**
     * 指定名の効果音を取得する。
     *
     * @param name 効果音名（例: {@code click}）
     * @return 効果音設定。未定義の場合は {@link GuiSound#NONE}
     */
    public GuiSound sound(String name) {
        if (name == null) {
            return GuiSound.NONE;
        }
        return sounds.getOrDefault(name, GuiSound.NONE);
    }
}
