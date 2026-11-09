package com.zeloria.gui;

import com.zeloria.player.EquipSlot;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code config/GuiData/inventory.yml} を表す装備設定。
 * <p>
 * 仮想装備枠の配置、GUI 共通の効果音、表示用マテリアル、固定表示名を保持する。
 *
 * @param equipmentSlots 仮想装備スロットの GUI 上の位置
 * @param sounds         GUI 共通効果音（キー → 効果音設定）
 * @param materials      表示用マテリアル（キー → 解決済み Material）
 * @param names          固定の表示名（キー → 表示テキスト）
 */
public record InventorySettings(
        Map<EquipSlot, Integer> equipmentSlots,
        Map<String, GuiSound> sounds,
        Map<String, GuiMaterial> materials,
        Map<String, String> names
) {

    public InventorySettings {
        EnumMap<EquipSlot, Integer> equipmentCopy = new EnumMap<>(EquipSlot.class);
        if (equipmentSlots != null) {
            equipmentCopy.putAll(equipmentSlots);
        }
        equipmentSlots = Map.copyOf(equipmentCopy);

        sounds = sounds == null ? Map.of() : Map.copyOf(sounds);
        materials = materials == null ? Map.of() : Map.copyOf(materials);
        names = names == null ? Map.of() : Map.copyOf(names);
    }

    /**
     * 既定設定（{@code inventory.yml} が読み込めない場合のフォールバック）。
     */
    public static InventorySettings defaults() {
        Map<EquipSlot, Integer> equipment = new EnumMap<>(EquipSlot.class);
        equipment.put(EquipSlot.TOOL_1, 24);
        equipment.put(EquipSlot.TOOL_2, 6);
        equipment.put(EquipSlot.HELMET, 19);
        equipment.put(EquipSlot.CHESTPLATE, 20);
        equipment.put(EquipSlot.LEGGINGS, 21);
        equipment.put(EquipSlot.BOOTS, 22);
        equipment.put(EquipSlot.MAIN_WEAPON, 25);

        return new InventorySettings(
                equipment,
                Map.of(
                        "open", new GuiSound("block.ender_chest.open", 0.8f, 1.2f),
                        "click", new GuiSound("ui.button.click", 0.6f, 1.4f),
                        "error", new GuiSound("entity.villager.no", 0.7f, 1.0f)
                ),
                defaultMaterials(),
                defaultNames()
        );
    }

    /**
     * 既定の表示名。YAML の {@code names:} が無い場合に使用する。
     */
    public static Map<String, String> defaultNames() {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("tool_slot", "ツールスロット");
        values.put("free_slot", "フリースロット");
        values.put("menu_button", "メニュー");
        values.put("tab_equipment", "装備ストレージ");
        values.put("tab_storage", "ストレージ");
        values.put("tab_switch", "タブ切り替え");
        values.put("scroll_up", "▲ 上へスクロール");
        values.put("scroll_down", "▼ 下へスクロール");
        return Map.copyOf(values);
    }

    /**
     * 既定の表示用マテリアル。YAML の {@code materials:} が無い場合に使用する。
     */
    public static Map<String, GuiMaterial> defaultMaterials() {
        Map<String, GuiMaterial> values = new java.util.LinkedHashMap<>();
        values.put("empty_weapon",
                new GuiMaterial(org.bukkit.Material.BARRIER, 0));
        values.put("empty_armor",
                new GuiMaterial(org.bukkit.Material.OAK_BUTTON, 0));
        values.put("hotbar_tool",
                new GuiMaterial(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, 0));
        values.put("menu_button",
                new GuiMaterial(org.bukkit.Material.BOOK, 0));
        values.put("scroll",
                new GuiMaterial(org.bukkit.Material.ARROW, 0));
        values.put("tab_equipment",
                new GuiMaterial(org.bukkit.Material.CHEST, 0));
        values.put("tab_storage",
                new GuiMaterial(org.bukkit.Material.ENDER_CHEST, 0));
        values.put("skill_idle",
                new GuiMaterial(org.bukkit.Material.BLACK_STAINED_GLASS_PANE, 0));
        return Map.copyOf(values);
    }

    /**
     * 指定装備スロットの GUI 上の位置を取得する。
     *
     * @param slot 装備スロット
     * @return スロット番号。未定義の場合は {@code -1}
     */
    public int equipmentSlot(EquipSlot slot) {
        if (slot == null) {
            return -1;
        }
        return equipmentSlots.getOrDefault(slot, -1);
    }

    /**
     * 指定名の表示用マテリアルを取得する。
     *
     * @param name     マテリアル名（例: {@code menu_button}）
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
     * 指定名の表示テキストを取得する。
     *
     * @param name     表示名のキー（例: {@code menu_button}）
     * @param fallback 未定義の場合の代替値
     * @return 表示テキスト
     */
    public String name(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String value = names.get(name);
        return value == null || value.isBlank() ? fallback : value;
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
