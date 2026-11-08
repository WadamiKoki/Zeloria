package com.zeloria.player;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PlayerStats.Bonuses}（管理者補正）と JSON 文字列の相互変換ユーティリティ。
 * <p>
 * DB の {@code players.admin_bonuses_json} に 1 カラムの JSON として保存する。
 * 外部ライブラリを追加せず、18 個の数値だけを読み書きする。
 * 壊れた値や未定義のキーは 0 として扱う。
 * <p>
 * 旧バージョンとの互換性のため、{@code player_settings_json} に保存されていた
 * {@code {"adminBonuses":{...}}} 形式の JSON もそのまま読み取れる。
 */
public final class AdminBonusesJson {

    /** 未設定時の既定 JSON。 */
    public static final String DEFAULT_JSON = "{}";

    /**
     * 旧形式（{@code "adminBonuses":{...}}）のブロックを抽出する。
     * <p>
     * 新形式（カラム自体が補正オブジェクト）では一致しないため、
     * その場合は JSON 全体を補正オブジェクトとして解析する。
     */
    private static final Pattern ADMIN_BLOCK =
            Pattern.compile("\"adminBonuses\"\\s*:\\s*\\{([^{}]*)\\}", Pattern.CASE_INSENSITIVE);

    /** JSON オブジェクト内の数値エントリを抽出する。 */
    private static final Pattern NUMBER_ENTRY = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");

    private AdminBonusesJson() {
    }

    /**
     * 管理者補正を JSON 文字列へ変換する。
     *
     * @param bonuses 管理者補正（{@code null} の場合は補正なし）
     * @return JSON 文字列
     */
    public static String encode(PlayerStats.Bonuses bonuses) {
        PlayerStats.Bonuses value = bonuses == null ? PlayerStats.Bonuses.NONE : bonuses;
        return "{" + encodeBonuses(value) + "}";
    }

    /**
     * JSON 文字列から管理者補正を復元する。
     *
     * @param json JSON 文字列（{@code null} / 空可）
     * @return 復元された管理者補正。未指定の項目は 0
     */
    public static PlayerStats.Bonuses decode(String json) {
        if (json == null || json.isBlank()) {
            return PlayerStats.Bonuses.NONE;
        }

        // 旧形式の場合は adminBonuses ブロックの中身だけを解析する。
        Matcher block = ADMIN_BLOCK.matcher(json);
        String source = block.find() ? block.group(1) : json;

        double[] values = new double[18];
        Matcher entry = NUMBER_ENTRY.matcher(source);
        while (entry.find()) {
            int index = adminBonusIndex(entry.group(1));
            if (index < 0) {
                continue;
            }
            try {
                values[index] = Double.parseDouble(entry.group(2));
            } catch (NumberFormatException ignored) {
                // 壊れた数値は 0 のまま扱う。
            }
        }
        return new PlayerStats.Bonuses(
                values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11],
                values[12], values[13], values[14], values[15], values[16], values[17]);
    }

    private static int adminBonusIndex(String key) {
        return switch (key) {
            case "maxHealth" -> 0;
            case "maxMana" -> 1;
            case "attackPower" -> 2;
            case "defense1" -> 3;
            case "defense2" -> 4;
            case "criticalRate" -> 5;
            case "criticalDamage" -> 6;
            case "accuracy" -> 7;
            case "evasion" -> 8;
            case "dodgeCooldownReduction" -> 9;
            case "cooldownReduction" -> 10;
            case "moveSpeed" -> 11;
            case "healthRegenFlat" -> 12;
            case "healthRegenPercent" -> 13;
            case "manaRegenFlat" -> 14;
            case "manaRegenPercent" -> 15;
            case "armorPenetration" -> 16;
            case "lifesteal" -> 17;
            default -> -1;
        };
    }

    private static String encodeBonuses(PlayerStats.Bonuses bonuses) {
        return "\"maxHealth\":" + number(bonuses.maxHealth())
                + ",\"maxMana\":" + number(bonuses.maxMana())
                + ",\"attackPower\":" + number(bonuses.attackPower())
                + ",\"defense1\":" + number(bonuses.defense1())
                + ",\"defense2\":" + number(bonuses.defense2())
                + ",\"criticalRate\":" + number(bonuses.criticalRate())
                + ",\"criticalDamage\":" + number(bonuses.criticalDamage())
                + ",\"accuracy\":" + number(bonuses.accuracy())
                + ",\"evasion\":" + number(bonuses.evasion())
                + ",\"dodgeCooldownReduction\":" + number(bonuses.dodgeCooldownReduction())
                + ",\"cooldownReduction\":" + number(bonuses.cooldownReduction())
                + ",\"moveSpeed\":" + number(bonuses.moveSpeed())
                + ",\"healthRegenFlat\":" + number(bonuses.healthRegenFlat())
                + ",\"healthRegenPercent\":" + number(bonuses.healthRegenPercent())
                + ",\"manaRegenFlat\":" + number(bonuses.manaRegenFlat())
                + ",\"manaRegenPercent\":" + number(bonuses.manaRegenPercent())
                + ",\"armorPenetration\":" + number(bonuses.armorPenetration())
                + ",\"lifesteal\":" + number(bonuses.lifesteal());
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
