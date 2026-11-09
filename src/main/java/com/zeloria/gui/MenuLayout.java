package com.zeloria.gui;

import com.zeloria.player.EquipSlot;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/GuiData/menu.yml} を表す {@code /menu} GUI のレイアウト設定。
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
 * @param playerHeadSlot プレイヤー情報ヘッドのスロット番号（未使用は {@code -1}）
 * @param materials      GUI 内で使うマテリアル（キー → 解決済み Material）
 * @param equipmentSlots 仮想装備枠のスロット番号（装備種別 → GUI スロット）
 * @param decorations    装飾専用アイテム（GUI スロット → 解決済み Material）
 * @param sounds         効果音（キー → 効果音設定）
 * @param icons          ボタンアイコン（キー → アイコン設定）
 * @param features       メニュー機能（スロット・アイコン・アクション）
 */
public record MenuLayout(
        String title,
        int rows,
        int closeSlot,
        int playerHeadSlot,
        Map<String, GuiMaterial> materials,
        Map<EquipSlot, Integer> equipmentSlots,
        Map<Integer, GuiMaterial> decorations,
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
        materials = materials == null ? Map.of() : Map.copyOf(materials);

        EnumMap<EquipSlot, Integer> equipmentCopy = new EnumMap<>(EquipSlot.class);
        if (equipmentSlots != null) {
            equipmentSlots.forEach((slot, guiSlot) -> {
                if (slot != null && guiSlot != null && guiSlot >= 0) {
                    equipmentCopy.put(slot, guiSlot);
                }
            });
        }
        equipmentSlots = Map.copyOf(equipmentCopy);

        Map<Integer, GuiMaterial> decorationCopy = new LinkedHashMap<>();
        if (decorations != null) {
            decorations.forEach((slot, material) -> {
                if (slot != null && slot >= 0 && material != null) {
                    decorationCopy.put(slot, material);
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
        // 下段のコンパス（閉じるボタン）の左隣 2 枠を自由枠 1・2 に割り当てる。
        equipment.put(EquipSlot.TOOL_1, 24);
        equipment.put(EquipSlot.TOOL_2, 6);
        equipment.put(EquipSlot.HELMET, 16);
        equipment.put(EquipSlot.CHESTPLATE, 25);
        equipment.put(EquipSlot.MAIN_WEAPON, 26);
        equipment.put(EquipSlot.LEGGINGS, 34);
        equipment.put(EquipSlot.BOOTS, 43);

        Map<Integer, GuiMaterial> decorations = new LinkedHashMap<>();
        GuiMaterial decorationMaterial =
                new GuiMaterial(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, 0);
        for (int slot : new int[]{5, 14, 23, 32, 41, 50}) {
            decorations.put(slot, decorationMaterial);
        }

        return new MenuLayout(
                DEFAULT_TITLE,
                DEFAULT_ROWS,
                53,
                8,
                defaultMaterials(),
                equipment,
                decorations,
                Map.of(
                        "open", new GuiSound("block.ender_chest.open", 0.8f, 1.2f),
                        "click", new GuiSound("ui.button.click", 0.6f, 1.4f),
                        "error", new GuiSound("entity.villager.no", 0.7f, 1.0f),
                        "equip", new GuiSound("item.armor.equip_generic", 0.9f, 1.1f)
                ),
                Map.of(),
                List.of(
                        new MenuFeature("class_select", 0, "class_select",
                                "class_select", "zeloria.player")
                )
        );
    }

    /**
     * 既定の GUI マテリアル。YAML の {@code materials:} が無い場合に使用する。
     */
    public static Map<String, GuiMaterial> defaultMaterials() {
        Map<String, GuiMaterial> values = new LinkedHashMap<>();
        values.put("decoration",
                new GuiMaterial(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, 0));
        values.put("empty_armor", new GuiMaterial(org.bukkit.Material.BARRIER, 0));
        values.put("empty_tool", new GuiMaterial(org.bukkit.Material.ITEM_FRAME, 0));
        values.put("player_head", new GuiMaterial(org.bukkit.Material.PLAYER_HEAD, 0));
        values.put("toggle_on", new GuiMaterial(org.bukkit.Material.LIME_DYE, 0));
        values.put("toggle_off", new GuiMaterial(org.bukkit.Material.GRAY_DYE, 0));
        values.put("feature_fallback", new GuiMaterial(org.bukkit.Material.PAPER, 0));
        values.put("close_fallback", new GuiMaterial(org.bukkit.Material.BARRIER, 0));
        return Map.copyOf(values);
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
     * 指定名の GUI マテリアルを取得する。
     *
     * @param name     マテリアル名（例: {@code empty_tool}）
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
     * 指定名のアイコンを取得する。
     *
     * @param name アイコン名（例: {@code close}）
     * @return アイコン設定。未定義の場合は {@code null}
     */
    public GuiIcon icon(String name) {
        return name == null ? null : icons.get(name);
    }
}
