package com.zeloria.player;

/**
 * メインインベントリ（スロット 9〜35）に表示する仮想インベントリのタブ。
 * <p>
 * タブ切り替えボタンを押すたびに {@link #INVENTORY} → {@link #EQUIPMENT} →
 * {@link #MATERIAL} → {@link #INVENTORY} の順で循環する。
 */
public enum VirtualInventoryTab {

    /** 通常インベントリ。 */
    INVENTORY("通常インベントリ"),

    /** 装備インベントリ（武器・防具）。 */
    EQUIPMENT("装備インベントリ"),

    /** 素材インベントリ（素材・消耗品）。 */
    MATERIAL("素材インベントリ");

    private final String displayName;

    VirtualInventoryTab(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 表示用のタブ名を取得する。
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 次のタブを取得する（素材の次は通常へ戻る）。
     */
    public VirtualInventoryTab next() {
        VirtualInventoryTab[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
