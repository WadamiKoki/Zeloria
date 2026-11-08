package com.zeloria.gathering;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * ギャザリングのレベル設定（{@code config/SystemData/gathering_level_settings.yml}）。
 * <p>
 * 必要経験値は Wynncraft 方式の二次関数で算出する。
 * <ul>
 *     <li>次レベルまで: {@code base_exp * (2 * level - 1)}</li>
 *     <li>レベル 1 からの累計: {@code base_exp * (level - 1)^2}</li>
 * </ul>
 * 計算は {@code long} で行い、オーバーフロー時は {@link Long#MAX_VALUE} にクランプする。
 */
public final class GatheringSettings {

    /** 設定が読み込めなかった場合の採取 1 種類あたりの既定必要経験値。 */
    public static final long DEFAULT_BASE_EXP = 100L;

    /** 設定が読み込めなかった場合の既定最大レベル。 */
    public static final int DEFAULT_MAX_LEVEL = 100;

    /** 設定が読み込めなかった場合に使用する既定値。 */
    public static final GatheringSettings DEFAULT = new GatheringSettings(
            Map.of(
                    GatheringType.MINING, new Entry(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL),
                    GatheringType.WOODCUTTING, new Entry(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL),
                    GatheringType.FARMING, new Entry(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL)));

    /**
     * 採取 1 種類分の設定。
     *
     * @param baseExp  必要経験値の基準値
     * @param maxLevel 到達可能な最大レベル（1 以上）
     */
    public record Entry(long baseExp, int maxLevel) {

        public Entry {
            baseExp = Math.max(1L, baseExp);
            maxLevel = Math.max(1, maxLevel);
        }
    }

    private final Map<GatheringType, Entry> entries;

    /**
     * 種別ごとの設定からインスタンスを生成する。
     * <p>
     * 未指定の種別は {@link #DEFAULT_BASE_EXP} / {@link #DEFAULT_MAX_LEVEL} で補完する。
     *
     * @param entries 種別ごとの設定
     */
    public GatheringSettings(Map<GatheringType, Entry> entries) {
        EnumMap<GatheringType, Entry> copy = new EnumMap<>(GatheringType.class);
        for (GatheringType type : GatheringType.values()) {
            Entry entry = entries == null ? null : entries.get(type);
            copy.put(type, entry == null ? new Entry(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL) : entry);
        }
        this.entries = Map.copyOf(copy);
    }

    /**
     * YAML から設定を読み込む。
     *
     * @param config 読み込み元（{@code gathering_level_settings.yml}）
     * @return 読み込んだ設定
     */
    public static GatheringSettings fromConfig(FileConfiguration config) {
        if (config == null) {
            return DEFAULT;
        }
        EnumMap<GatheringType, Entry> parsed = new EnumMap<>(GatheringType.class);
        for (GatheringType type : GatheringType.values()) {
            ConfigurationSection section = config.getConfigurationSection(type.key());
            if (section == null) {
                continue;
            }
            parsed.put(type, new Entry(
                    section.getLong("base_exp", DEFAULT_BASE_EXP),
                    section.getInt("max_level", DEFAULT_MAX_LEVEL)));
        }
        return new GatheringSettings(parsed);
    }

    /**
     * 指定種別の設定を取得する。
     *
     * @param type ギャザリング種別
     * @return 設定（未指定時は既定値）
     */
    public Entry entry(GatheringType type) {
        Entry entry = entries.get(type);
        return entry == null ? new Entry(DEFAULT_BASE_EXP, DEFAULT_MAX_LEVEL) : entry;
    }

    /**
     * 指定種別・レベルの「次レベルまでに必要な経験値」を返す。
     * <p>
     * {@code base_exp * (2 * level - 1)} を {@code long} で計算し、
     * オーバーフローする場合は {@link Long#MAX_VALUE} を返す。
     *
     * @param type  ギャザリング種別
     * @param level 現在レベル（1 以上）
     * @return 必要経験値（最低 1）
     */
    public long requiredExp(GatheringType type, int level) {
        int clamped = Math.max(1, level);
        long factor = 2L * clamped - 1L;
        long base = entry(type).baseExp();
        if (base > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, base * factor);
    }

    /**
     * 指定種別の最大レベルを取得する。
     *
     * @param type ギャザリング種別
     * @return 最大レベル（1 以上）
     */
    public int maxLevel(GatheringType type) {
        return entry(type).maxLevel();
    }
}
