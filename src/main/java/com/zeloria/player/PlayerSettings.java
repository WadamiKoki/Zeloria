package com.zeloria.player;

/**
 * プレイヤーごとの表示・通知設定。
 * <p>
 * DB の {@code players.player_settings_json} に JSON として保存する。
 * 現時点ではレベルアップ通知のチャットログに関する 2 つのフラグだけを持つが、
 * 設定を追加する場合は本レコードへ項目を追加し、{@link SettingsJson} の
 * エンコード／デコードへキーを追加する。
 *
 * @param chatLogSelf   自分のレベルアップログを表示する場合 {@code true}
 * @param chatLogOthers 他のプレイヤーのレベルアップログを表示する場合 {@code true}
 */
public record PlayerSettings(boolean chatLogSelf, boolean chatLogOthers) {

    /** 既定値（両方 ON）。 */
    public static final PlayerSettings DEFAULT = new PlayerSettings(true, true);

    /**
     * 自分のレベルアップログ設定だけを差し替えた設定を返す。
     *
     * @param enabled 新しい値
     * @return 差し替えた設定
     */
    public PlayerSettings withChatLogSelf(boolean enabled) {
        return new PlayerSettings(enabled, chatLogOthers);
    }

    /**
     * 他人のレベルアップログ設定だけを差し替えた設定を返す。
     *
     * @param enabled 新しい値
     * @return 差し替えた設定
     */
    public PlayerSettings withChatLogOthers(boolean enabled) {
        return new PlayerSettings(chatLogSelf, enabled);
    }
}
