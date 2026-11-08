package com.zeloria.player;

import com.zeloria.item.VirtualItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * 仮想装備枠のスロット種別。
 * <p>
 * 統合メニュー上部（水色ガラスエリア）に配置される装備枠に対応する。
 * 各枠は受け入れ可能なアイテム種別を持ち、インベントリからの右クリック装着時に
 * {@link #match(ItemStack)} で装着先を自動判定する。
 * プレイヤーヘッド左隣の 2 枠（{@link #TOOL_1} / {@link #TOOL_2}）は
 * 採取ツール（{@code ItemCategory.TOOL}）専用の装備枠として扱う。
 * メニュー GUI では下段のコンパス（閉じるボタン）の左隣 2 枠に配置し、
 * 装備内容はホットバー slot 6 / 7 に表示する。
 */
public enum EquipSlot {

    /** 兜。 */
    HELMET("helmet", "兜"),

    /** 胴体。 */
    CHESTPLATE("chestplate", "胸当て"),

    /** 脚部。 */
    LEGGINGS("leggings", "脚甲"),

    /** 足元。 */
    BOOTS("boots", "靴"),

    /** メイン武器。 */
    MAIN_WEAPON("main_weapon", "メイン武器"),

    /** オフハンド。 */
    OFFHAND("offhand", "オフハンド"),

    /** 道具枠 1（採取ツール専用）。 */
    TOOL_1("tool_1", "道具1"),

    /** 道具枠 2（採取ツール専用）。 */
    TOOL_2("tool_2", "道具2");

    private final String key;
    private final String displayName;

    EquipSlot(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /**
     * 設定ファイル上のキー（例: {@code main_weapon}）を取得する。
     */
    public String getKey() {
        return key;
    }

    /**
     * 表示用の名前を取得する。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 採取ツール専用の道具枠か判定する。
     *
     * @return 道具枠の場合は {@code true}
     */
    public boolean isToolSlot() {
        return this == TOOL_1 || this == TOOL_2;
    }

    /**
     * 設定キーからスロット種別を解決する（大文字小文字は区別しない）。
     *
     * @param key 設定キー
     * @return 該当するスロット。見つからない場合は {@code null}
     */
    public static EquipSlot fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (EquipSlot slot : values()) {
            if (slot.key.equals(normalized)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * アイテムから装着先スロットを自動判定する。
     *
     * @param stack 判定対象のアイテム
     * @return 装着先スロット。装備品でない場合は {@code null}
     */
    public static EquipSlot match(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        // 仮想装備表示用アイテム（見た目専用）は装備対象にしない。
        if (VirtualItem.isVirtual(stack)) {
            return null;
        }

        Material type = stack.getType();
        String name = type.name();

        // 防具（プレイヤーの頭・モブの頭も兜として扱う）。
        if (name.endsWith("_HELMET")
                || name.endsWith("_HEAD")
                || name.endsWith("_SKULL")
                || type == Material.CARVED_PUMPKIN) {
            return HELMET;
        }
        if (name.endsWith("_CHESTPLATE") || type == Material.ELYTRA) {
            return CHESTPLATE;
        }
        if (name.endsWith("_LEGGINGS")) {
            return LEGGINGS;
        }
        if (name.endsWith("_BOOTS")) {
            return BOOTS;
        }

        // メイン武器（"PICKAXE" は "_AXE" で終わらないため誤検知しない）。
        // プラグイン製の短剣（HOE）・杖（STICK）は ItemData の WeaponData として
        // 装着先を解決するため、ここではバニラ素材から武器判定しない。
        // ツルハシ・クワ・棒は道具スロット専用とする。
        if (name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || type == Material.TRIDENT
                || type == Material.MACE
                || type == Material.BOW
                || type == Material.CROSSBOW) {
            return MAIN_WEAPON;
        }

        // オフハンド。
        if (type == Material.SHIELD
                || type == Material.TOTEM_OF_UNDYING
                || type == Material.ARROW
                || type == Material.TIPPED_ARROW
                || type == Material.FIREWORK_ROCKET) {
            return OFFHAND;
        }

        return null;
    }

    /**
     * このスロットが指定アイテムを受け入れられるか判定する。
     *
     * @param stack 判定対象のアイテム
     * @return 受け入れ可能な場合は {@code true}
     */
    public boolean accepts(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (isToolSlot()) {
            return isToolMaterial(stack.getType());
        }
        EquipSlot matched = match(stack);
        return matched == this;
    }

    /**
     * バニラ素材が採取ツールとして扱えるか判定する。
     * <p>
     * プラグイン製ツールは {@code ItemCategory.TOOL} の定義で判定するため、
     * ここではカーソル装着時のフォールバックとして素材名を照合する。
     */
    private static boolean isToolMaterial(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_AXE")
                || type == Material.SHEARS
                || type == Material.FISHING_ROD
                || type == Material.FLINT_AND_STEEL
                || type == Material.BRUSH
                || type == Material.CARROT_ON_A_STICK
                || type == Material.WARPED_FUNGUS_ON_A_STICK;
    }
}
