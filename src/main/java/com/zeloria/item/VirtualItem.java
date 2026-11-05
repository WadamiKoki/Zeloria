package com.zeloria.item;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 仮想装備表示用アイテムの識別情報を扱うユーティリティ。
 * <p>
 * ホットバーやバニラ防具スロットへ「見た目だけ」配置するアイテムには、
 * PDC の {@code zeloria:virtual_equipment}（BYTE = 1）を付与する。
 * このマーカーがあるアイテムはプレイヤーの実アイテムではないため、
 * 装備判定・ドロップ・移動の対象から除外する。
 * <p>
 * 戦闘モード中のスキルスロット表示アイテムは、仮想装備マーカーに加えて
 * {@code zeloria:virtual_skill_slot}（BYTE = 1）も付与する。スキル表示だけを
 * 識別したい場合（表示更新時の保護判定など）は {@link #isSkillSlot(ItemStack)} を使う。
 */
public final class VirtualItem {

    /** PDC キー: 仮想装備表示アイテムであることを示すマーカー。 */
    public static final String KEY_VIRTUAL_EQUIPMENT = "virtual_equipment";

    /** PDC キー: スキルスロット表示アイテムであることを示すマーカー。 */
    public static final String KEY_SKILL_SLOT = "virtual_skill_slot";

    /** PDC キー: 空きスロット表示（バリア・色付きガラス）であることを示すマーカー。 */
    public static final String KEY_PLACEHOLDER = "virtual_placeholder";

    /** 仮想装備表示アイテムの名前空間（既存のアイテム PDC と揃える）。 */
    private static final String NAMESPACE = ItemService.MODEL_NAMESPACE;

    /** 仮想装備表示アイテムのマーカーキー。 */
    private static final NamespacedKey VIRTUAL_EQUIPMENT_KEY =
            new NamespacedKey(NAMESPACE, KEY_VIRTUAL_EQUIPMENT);

    /** スキルスロット表示アイテムのマーカーキー。 */
    private static final NamespacedKey SKILL_SLOT_KEY =
            new NamespacedKey(NAMESPACE, KEY_SKILL_SLOT);

    /** 空きスロット表示アイテムのマーカーキー。 */
    private static final NamespacedKey PLACEHOLDER_KEY =
            new NamespacedKey(NAMESPACE, KEY_PLACEHOLDER);

    /** マーカー値（BYTE）。 */
    private static final byte VIRTUAL_VALUE = 1;

    private VirtualItem() {
    }

    /**
     * 指定アイテムが仮想装備表示用か判定する。
     *
     * @param stack 判定対象
     * @return 仮想装備表示用の場合は {@code true}
     */
    public static boolean isVirtual(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer()
                .get(VIRTUAL_EQUIPMENT_KEY, PersistentDataType.BYTE);
        return marker != null && marker == VIRTUAL_VALUE;
    }

    /**
     * アイテムがスキルスロット表示用の仮想アイテムか判定する。
     *
     * @param stack 判定対象
     * @return スキルスロット表示アイテムの場合は {@code true}
     */
    public static boolean isSkillSlot(ItemStack stack) {
        if (!isVirtual(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer()
                .get(SKILL_SLOT_KEY, PersistentDataType.BYTE);
        return marker != null && marker == VIRTUAL_VALUE;
    }

    /**
     * アイテムが空きスロット表示（バリア・色付きガラス）か判定する。
     * <p>
     * 空きスロット表示は仮想アイテムではあるが装備ではないため、
     * {@link #isVirtualEquipment(ItemStack)} からは除外される。
     *
     * @param stack 判定対象
     * @return 空きスロット表示の場合は {@code true}
     */
    public static boolean isPlaceholder(ItemStack stack) {
        if (!isVirtual(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer()
                .get(PLACEHOLDER_KEY, PersistentDataType.BYTE);
        return marker != null && marker == VIRTUAL_VALUE;
    }

    /**
     * アイテムがスキルスロット表示ではない仮想装備アイテムか判定する。
     * <p>
     * 仮想武器・仮想防具の判定に使用する。空きスロット表示（バリア・ガラス）は
     * 装備ではないため {@code false} を返す。
     *
     * @param stack 判定対象
     * @return 仮想装備アイテムの場合は {@code true}
     */
    public static boolean isVirtualEquipment(ItemStack stack) {
        return isVirtual(stack) && !isSkillSlot(stack) && !isPlaceholder(stack);
    }

    /**
     * アイテムを複製し、仮想装備表示用のマーカーを付与する。
     * <p>
     * 元のアイテムは変更しない。装備データそのもの（DB 保存対象）に
     * マーカーが混入しないよう、必ず複製に対して付与する。
     *
     * @param source 元アイテム
     * @return マーカー付きの複製。空アイテムの場合は {@code null}
     */
    public static ItemStack mark(ItemStack source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        meta.getPersistentDataContainer().set(
                VIRTUAL_EQUIPMENT_KEY, PersistentDataType.BYTE, VIRTUAL_VALUE);
        copy.setItemMeta(meta);
        return copy;
    }

    /**
     * アイテムを複製し、仮想装備マーカーとスキルスロットマーカーを付与する。
     *
     * @param source 元アイテム（スキルアイコン）
     * @return マーカー付きの複製。空アイテムの場合は {@code null}
     */
    public static ItemStack markSkillSlot(ItemStack source) {
        ItemStack marked = mark(source);
        if (marked == null) {
            return null;
        }
        ItemMeta meta = marked.getItemMeta();
        if (meta == null) {
            return marked;
        }
        meta.getPersistentDataContainer().set(
                SKILL_SLOT_KEY, PersistentDataType.BYTE, VIRTUAL_VALUE);
        marked.setItemMeta(meta);
        return marked;
    }

    /**
     * アイテムを複製し、空きスロット表示用のマーカーを付与する。
     * <p>
     * バリアブロックや色付きガラス板など、装備が無いスロットへ配置する
     * 見た目専用アイテムに使用する。仮想装備マーカーも付与されるため、
     * ドロップ・移動の対象からは除外される。
     *
     * @param source 元アイテム（空きスロット表示に使う Material）
     * @return マーカー付きの複製。空アイテムの場合は {@code null}
     */
    public static ItemStack markPlaceholder(ItemStack source) {
        ItemStack marked = mark(source);
        if (marked == null) {
            return null;
        }
        ItemMeta meta = marked.getItemMeta();
        if (meta == null) {
            return marked;
        }
        meta.getPersistentDataContainer().set(
                PLACEHOLDER_KEY, PersistentDataType.BYTE, VIRTUAL_VALUE);
        marked.setItemMeta(meta);
        return marked;
    }
}
