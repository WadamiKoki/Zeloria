package com.zeloria.gui;

import com.zeloria.player.EquipSlot;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code config/gui/inventory.yml} を表す装備設定。
 * <p>
 * 仮想装備枠の配置と、GUI 共通の効果音を保持する。
 *
 * @param equipmentSlots 仮想装備スロットの GUI 上の位置
 * @param sounds         GUI 共通効果音（キー → 効果音設定）
 */
public record InventorySettings(
        Map<EquipSlot, Integer> equipmentSlots,
        Map<String, GuiSound> sounds
) {

    public InventorySettings {
        EnumMap<EquipSlot, Integer> equipmentCopy = new EnumMap<>(EquipSlot.class);
        if (equipmentSlots != null) {
            equipmentCopy.putAll(equipmentSlots);
        }
        equipmentSlots = Map.copyOf(equipmentCopy);

        sounds = sounds == null ? Map.of() : Map.copyOf(sounds);
    }

    /**
     * 既定設定（{@code inventory.yml} が読み込めない場合のフォールバック）。
     */
    public static InventorySettings defaults() {
        Map<EquipSlot, Integer> equipment = new EnumMap<>(EquipSlot.class);
        equipment.put(EquipSlot.HELMET, 19);
        equipment.put(EquipSlot.CHESTPLATE, 20);
        equipment.put(EquipSlot.LEGGINGS, 21);
        equipment.put(EquipSlot.BOOTS, 22);
        equipment.put(EquipSlot.MAIN_WEAPON, 24);
        equipment.put(EquipSlot.OFFHAND, 25);

        return new InventorySettings(
                equipment,
                Map.of(
                        "open", new GuiSound("block.ender_chest.open", 0.8f, 1.2f),
                        "click", new GuiSound("ui.button.click", 0.6f, 1.4f),
                        "error", new GuiSound("entity.villager.no", 0.7f, 1.0f)
                )
        );
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
