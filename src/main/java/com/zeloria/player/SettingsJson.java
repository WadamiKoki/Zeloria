package com.zeloria.player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PlayerSettings} と JSON 文字列の相互変換ユーティリティ。
 * <p>
 * DB の {@code player_settings_json} に 1 カラムの JSON として保存する。
 * 外部ライブラリを追加せず、boolean だけを読み書きする。
 * 壊れた値や未定義のキーは既定値として扱う。
 * <p>
 * 管理者補正は {@link AdminBonusesJson}（{@code admin_bonuses_json}）へ分離している。
 */
public final class SettingsJson {

    /** 未設定時の既定 JSON。 */
    public static final String DEFAULT_JSON =
            "{\"chatLogSelf\":true,\"chatLogOthers\":true}";

    private static final Pattern BOOLEAN_ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private SettingsJson() {
    }

    /**
     * 設定を JSON 文字列へ変換する。
     *
     * @param settings 変換する設定（{@code null} の場合は既定値）
     * @return JSON 文字列
     */
    public static String encode(PlayerSettings settings) {
        PlayerSettings value = settings == null ? PlayerSettings.DEFAULT : settings;
        return "{\"chatLogSelf\":" + value.chatLogSelf()
                + ",\"chatLogOthers\":" + value.chatLogOthers() + "}";
    }

    /**
     * JSON 文字列から設定を復元する。
     *
     * @param json JSON 文字列（{@code null} / 空可）
     * @return 復元された設定。未指定のキーは既定値の ON
     */
    public static PlayerSettings decode(String json) {
        boolean chatLogSelf = true;
        boolean chatLogOthers = true;
        if (json == null || json.isBlank()) {
            return PlayerSettings.DEFAULT;
        }

        Matcher matcher = BOOLEAN_ENTRY.matcher(json);
        while (matcher.find()) {
            boolean value = Boolean.parseBoolean(matcher.group(2));
            switch (matcher.group(1)) {
                case "chatLogSelf" -> chatLogSelf = value;
                case "chatLogOthers" -> chatLogOthers = value;
                default -> {
                    // 将来追加されたキーは読み飛ばす。
                }
            }
        }
        return new PlayerSettings(chatLogSelf, chatLogOthers);
    }
}
