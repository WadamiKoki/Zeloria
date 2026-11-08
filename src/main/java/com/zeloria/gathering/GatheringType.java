package com.zeloria.gathering;

import java.util.Locale;

/**
 * ギャザリング（採取）の種類。
 * <p>
 * クラスレベルとは別に、プレイヤー UUID 単位のアカウント共通レベルとして管理する。
 * {@link #key()} は設定 YAML のセクション名・コマンド引数・JSON のキーに使用し、
 * {@link #displayName()} は UI 表示用の名称を返す。
 */
public enum GatheringType {

    /** 採掘。{@code config/GatheringData/level_settings.yml} の {@code mining}。 */
    MINING("mining", "Mining"),

    /** 伐採。{@code config/GatheringData/level_settings.yml} の {@code woodcutting}。 */
    WOODCUTTING("woodcutting", "Woodcutting"),

    /** 採集（表示名は Farming）。{@code config/GatheringData/level_settings.yml} の {@code farming}。 */
    FARMING("farming", "Farming");

    private final String key;
    private final String displayName;

    GatheringType(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /**
     * 設定・コマンド・JSON で使用するキーを取得する。
     */
    public String key() {
        return key;
    }

    /**
     * UI 表示用の名称を取得する（例: {@code Mining} / {@code Farming}）。
     */
    public String displayName() {
        return displayName;
    }

    /**
     * キーからギャザリング種別を解決する（大文字小文字は区別しない）。
     *
     * @param key キー（例: {@code mining}）
     * @return 解決された種別。解決できない場合は {@code null}
     */
    public static GatheringType fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (GatheringType type : values()) {
            if (type.key.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
