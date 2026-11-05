package com.zeloria.player;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 仮想装備のアイテム列を Base64 文字列との間で相互変換するユーティリティ。
 * <p>
 * 変換には Paper の {@link ItemStack#serializeItemsAsBytes(java.util.Collection)} /
 * {@link ItemStack#deserializeItemsFromBytes(byte[])} を使用する。
 * これは NBT ベースの可逆シリアライズであり、次の情報を保持できる。
 * <ul>
 *     <li>表示名・説明文・エンチャント・PDC などの NBT メタ情報</li>
 *     <li>空スロット（{@code null}）の位置</li>
 * </ul>
 * 列の順序はそのまま維持されるため、復元時にスロット位置がずれることはない。
 */
public final class InventorySerializer {

    /** 保存データが空であることを表す文字列。 */
    public static final String EMPTY = "";

    private InventorySerializer() {
        throw new AssertionError("ユーティリティクラスはインスタンス化できません。");
    }

    /**
     * アイテム列を Base64 文字列へ変換する。
     * <p>
     * {@code null} 要素は空スロットとして位置を保ったまま保存される。
     *
     * @param items アイテム列（{@code null} 要素を含んでよい）
     * @return Base64 文字列。空列の場合は空文字
     * @throws IllegalStateException シリアライズに失敗した場合
     */
    public static String encodeList(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY;
        }
        try {
            // 呼び出し側のリストを変更しないようコピーしてから渡す。
            List<ItemStack> copy = new ArrayList<>(items.size());
            for (ItemStack item : items) {
                copy.add(item == null || item.isEmpty() ? null : item);
            }
            return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy));
        } catch (RuntimeException e) {
            throw new IllegalStateException("アイテム列のシリアライズに失敗しました。", e);
        }
    }

    /**
     * Base64 文字列をアイテム列へ復元する。
     * <p>
     * 空スロットは {@code null} として返す。
     *
     * @param base64 Base64 文字列（{@code null} / 空文字可）
     * @return 復元されたアイテム列。空文字の場合は空リスト
     * @throws IllegalArgumentException Base64 の形式が不正な場合
     * @throws IllegalStateException    デシリアライズに失敗した場合
     */
    public static List<ItemStack> decodeList(String base64) {
        List<ItemStack> result = new ArrayList<>();
        if (base64 == null || base64.isBlank()) {
            return result;
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base64 文字列の形式が不正です。", e);
        }
        if (bytes.length == 0) {
            return result;
        }

        try {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
            for (ItemStack item : items) {
                result.add(item == null || item.isEmpty() ? null : item);
            }
            return result;
        } catch (RuntimeException e) {
            throw new IllegalStateException("アイテム列のデシリアライズに失敗しました。", e);
        }
    }

    /**
     * 仮想装備を Base64 文字列へ変換する。
     * <p>
     * {@link EquipSlot#values()} の宣言順で固定長の列として保存するため、
     * 空枠があっても位置がずれない。
     *
     * @param equipment 装備スロットとアイテムの対応
     * @return Base64 文字列
     */
    public static String encodeEquipment(Map<EquipSlot, ItemStack> equipment) {
        EquipSlot[] slots = EquipSlot.values();
        List<ItemStack> ordered = new ArrayList<>(slots.length);
        for (EquipSlot slot : slots) {
            ordered.add(equipment == null ? null : equipment.get(slot));
        }
        return encodeList(ordered);
    }

    /**
     * Base64 文字列から仮想装備を復元する。
     *
     * @param base64 Base64 文字列
     * @return 装備スロットとアイテムの対応（空枠は含まない）
     */
    public static Map<EquipSlot, ItemStack> decodeEquipment(String base64) {
        Map<EquipSlot, ItemStack> result = new EnumMap<>(EquipSlot.class);
        List<ItemStack> items = decodeList(base64);

        EquipSlot[] slots = EquipSlot.values();
        int limit = Math.min(slots.length, items.size());
        for (int index = 0; index < limit; index++) {
            ItemStack item = items.get(index);
            if (item != null) {
                result.put(slots[index], item);
            }
        }
        return result;
    }
}
