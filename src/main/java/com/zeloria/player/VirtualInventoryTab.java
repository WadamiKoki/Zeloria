package com.zeloria.player;

/**
 * メインインベントリ（スロット 9〜35）に表示する仮想インベントリのタブ。
 * <p>
 * タブ切り替えボタンを押すたびに {@link #STORAGE} → {@link #EQUIPMENT_STORAGE} →
 * {@link #STORAGE} の順で循環する。
 */
public enum VirtualInventoryTab {

    /** 通常の保管庫（素材・消耗品など）。 */
    STORAGE("保管庫"),

    /** 装備保管庫（武器・防具）。 */
    EQUIPMENT_STORAGE("装備保管庫");

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
     * 次のタブを取得する（装備保管庫の次は保管庫へ戻る）。
     */
    public VirtualInventoryTab next() {
        VirtualInventoryTab[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * 保存済み・表示用アイテムに記録されたタブ名を解決する。
     *
     * @param raw タブ名
     * @return 解決されたタブ。不明な場合は {@code null}
     */
    public static VirtualInventoryTab fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "STORAGE" -> STORAGE;
            case "EQUIPMENT_STORAGE" -> EQUIPMENT_STORAGE;
            default -> null;
        };
    }
}
