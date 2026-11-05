package com.zeloria.gui;

import com.zeloria.player.EquipSlot;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/gui/menu.yml} を表す {@code /menu} GUI のレイアウト設定。
 * <p>
 * {@code /menu} は「装備の着脱」と「今後追加される各種メニュー機能の呼び出し」だけを
 * 受け持つ。
 * <p>
 * 装備枠・装飾枠・閉じるボタン・機能ボタンのスロット番号はすべて YAML 側
 * （{@code equipment_slots} / {@code decorations} / {@code footer.close_slot} /
 * {@code features}）で定義し、コードには既定値以外のハードコードを持たない。
 *
 * @param title          GUI タイトル（MiniMessage）
 * @param rows           GUI の行数（1〜6）
 * @param closeSlot      閉じるボタンのスロット番号（未使用は {@code -1}）
 * @param fillers        背景ガラス（キー → Material 名）
 * @param equipmentSlots 仮想装備枠のスロット番号（装備種別 → GUI スロット）
 * @param decorations    装飾専用アイテム（GUI スロット → Material 名）
 * @param sounds         効果音（キー → 効果音設定）
 * @param icons          ボタンアイコン（キー → アイコン設定）
 * @param features       メニュー機能（スロット・アイコン・アクション）
 */
public record MenuLayout(
        String title,
        int rows,
        int closeSlot,
        Map<String, String> fillers,
        Map<EquipSlot, Integer> equipmentSlots,
        Map<Integer, String> decorations,
        Map<String, GuiSound> sounds,
        Map<String, GuiIcon> icons,
        List<MenuFeature> features
) {

    /** 既定のタイトル。 */
    public static final String DEFAULT_TITLE = "<gradient:#00c6ff:#0072ff><bold>Zeloria</bold></gradient> "
            + "<dark_gray>|</dark_gray> <white>装備メニュー</white>";

    /** 既定の行数。 */
    public static final int DEFAULT_ROWS = 6;

    /** 区切り線に使用する既定 Material。 */
    public static final String DEFAULT_DECORATION_MATERIAL = "GRAY_STAINED_GLASS_PANE";

    public MenuLayout {
        title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        rows = Math.max(1, Math.min(6, rows));
        fillers = fillers == null ? Map.of() : Map.copyOf(fillers);

        EnumMap<EquipSlot, Integer> equipmentCopy = new EnumMap<>(EquipSlot.class);
        if (equipmentSlots != null) {
            equipmentSlots.forEach((slot, guiSlot) -> {
                if (slot != null && guiSlot != null && guiSlot >= 0) {
                    equipmentCopy.put(slot, guiSlot);
                }
            });
        }
        equipmentSlots = Map.copyOf(equipmentCopy);

        Map<Integer, String> decorationCopy = new LinkedHashMap<>();
        if (decorations != null) {
            decorations.forEach((slot, material) -> {
                if (slot != null && slot >= 0 && material != null && !material.isBlank()) {
                    decorationCopy.put(slot, material.trim().toUpperCase(java.util.Locale.ROOT));
                }
            });
        }
        decorations = Map.copyOf(decorationCopy);

        sounds = sounds == null ? Map.of() : Map.copyOf(sounds);
        icons = icons == null ? Map.of() : Map.copyOf(icons);
        features = features == null ? List.of() : List.copyOf(features);
    }

    /**
     * 既定レイアウト（{@code menu.yml} が読み込めない場合のフォールバック）。
     * <p>
     * {@code menu.yml} の標準レイアウトと同じ配置を返す。
     */
    public static MenuLayout defaults() {
        Map<EquipSlot, Integer> equipment = new EnumMap<>(EquipSlot.class);
        equipment.put(EquipSlot.HELMET, 16);
        equipment.put(EquipSlot.OFFHAND, 24);
        equipment.put(EquipSlot.CHESTPLATE, 25);
        equipment.put(EquipSlot.MAIN_WEAPON, 26);
        equipment.put(EquipSlot.LEGGINGS, 34);
        equipment.put(EquipSlot.BOOTS, 43);

        Map<Integer, String> decorations = new LinkedHashMap<>();
        for (int slot : new int[]{5, 14, 23, 32, 41, 50}) {
            decorations.put(slot, DEFAULT_DECORATION_MATERIAL);
        }

        return new MenuLayout(
                DEFAULT_TITLE,
                DEFAULT_ROWS,
                53,
                Map.of("equipment", "LIGHT_BLUE_STAINED_GLASS_PANE"),
                equipment,
                decorations,
                Map.of(
                        "open", new GuiSound("block.ender_chest.open", 0.8f, 1.2f),
                        "click", new GuiSound("ui.button.click", 0.6f, 1.4f),
                        "error", new GuiSound("entity.villager.no", 0.7f, 1.0f),
                        "equip", new GuiSound("item.armor.equip_generic", 0.9f, 1.1f)
                ),
                Map.of(),
                List.of(new MenuFeature("class_select", 0, "class_select",
                        "class_select", "zeloria.player"))
        );
    }

    /**
     * GUI 全体のスロット数（行数 × 9）を取得する。
     */
    public int totalSlots() {
        return rows * 9;
    }

    /**
     * 指定装備種別の GUI スロット番号を取得する。
     *
     * @param slot 装備種別
     * @return スロット番号。未定義の場合は {@code -1}
     */
    public int equipmentSlot(EquipSlot slot) {
        if (slot == null) {
            return -1;
        }
        return equipmentSlots.getOrDefault(slot, -1);
    }

    /**
     * 指定スロットが装飾専用アイテムか判定する。
     */
    public boolean isDecorationSlot(int slot) {
        return decorations.containsKey(slot);
    }

    /**
     * 指定スロットの装飾専用アイテムの Material 名を取得する。
     *
     * @return Material 名。未定義の場合は {@code null}
     */
    public String decorationMaterial(int slot) {
        return decorations.get(slot);
    }

    /**
     * 指定スロットに配置されたメニュー機能を取得する。
     *
     * @param slot GUI スロット番号
     * @return 機能定義。未配置の場合は {@code null}
     */
    public MenuFeature featureAt(int slot) {
        for (MenuFeature feature : features) {
            if (feature.slot() == slot) {
                return feature;
            }
        }
        return null;
    }

    /**
     * スロット番号をキーとした機能マップを取得する（重複時は先勝ち）。
     */
    public Map<Integer, MenuFeature> featuresBySlot() {
        Map<Integer, MenuFeature> result = new LinkedHashMap<>();
        for (MenuFeature feature : features) {
            result.putIfAbsent(feature.slot(), feature);
        }
        return Map.copyOf(result);
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

    /**
     * 指定名のアイコンを取得する。
     *
     * @param name アイコン名（例: {@code close}）
     * @return アイコン設定。未定義の場合は {@code null}
     */
    public GuiIcon icon(String name) {
        return name == null ? null : icons.get(name);
    }
}
