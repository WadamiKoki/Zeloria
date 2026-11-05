package com.zeloria.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * {@code /menu} に配置する「メニュー機能」の定義。
 * <p>
 * 独自メニュー GUI の責務を「装備の着脱」と「各種メニュー機能呼び出しの基盤」に
 * 限定するため、個々の機能は {@code gui/menu.yml} の {@code features} セクションで
 * スロット・アイコン・アクション名を宣言する。実際の処理は {@link MenuService} へ
 * 登録されたアクションハンドラ（{@link MenuService#registerFeatureAction}）が担う。
 * <p>
 * 例:
 * <pre>
 * features:
 *   status:
 *     slot: 20
 *     icon: status
 *     action: status
 *     permission: zeloria.menu.status
 * </pre>
 *
 * @param id         機能 ID（設定キー）
 * @param slot       GUI スロット番号
 * @param icon       {@code icons} セクションのアイコンキー
 * @param action     クリック時に実行するアクション名
 * @param permission 表示・実行に必要な権限（空の場合は権限不要）
 */
public record MenuFeature(String id, int slot, String icon, String action, String permission) {

    public MenuFeature {
        id = id == null ? "" : id.trim();
        icon = icon == null || icon.isBlank() ? id : icon.trim();
        action = action == null || action.isBlank() ? id : action.trim();
        permission = permission == null ? "" : permission.trim();
    }

    /**
     * 設定セクションから機能定義を生成する。
     *
     * @param id      設定キー
     * @param section 機能セクション
     * @return 機能定義。スロットが不正な場合は {@code null}
     */
    public static MenuFeature fromConfig(String id, ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        int slot = section.getInt("slot", -1);
        if (slot < 0) {
            return null;
        }
        return new MenuFeature(
                id,
                slot,
                section.getString("icon", id),
                section.getString("action", id),
                section.getString("permission", "")
        );
    }

    /**
     * 指定プレイヤーがこの機能を利用できるか判定する。
     */
    public boolean isAvailableTo(Player player) {
        return player != null && (permission.isBlank() || player.hasPermission(permission));
    }
}
