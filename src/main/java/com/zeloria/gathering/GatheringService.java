package com.zeloria.gathering;

import com.zeloria.core.YamlLoader;
import com.zeloria.core.Zeloria;
import com.zeloria.player.LevelUpNotifier;
import com.zeloria.player.PlayerData;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * ギャザリングの経験値付与・レベルアップを管理するサービス。
 * <p>
 * レベル・経験値はプレイヤー UUID 単位で {@link PlayerData} に保持され、
 * クラスに関係なく共通となる。設定は
 * {@code config/GatheringData/level_settings.yml} から読み込む。
 */
public class GatheringService {

    /** ギャザリング設定の YAML キー。 */
    public static final String SETTINGS_KEY = "GatheringData/level_settings.yml";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private volatile GatheringSettings settings;

    public GatheringService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * ギャザリング設定を再読み込みする。
     */
    public synchronized void reload() {
        FileConfiguration config = yamlLoader.getConfig(SETTINGS_KEY);
        if (config == null) {
            config = yamlLoader.getConfig("level_settings");
        }
        this.settings = GatheringSettings.fromConfig(config);
        StringBuilder detail = new StringBuilder();
        for (GatheringType type : GatheringType.values()) {
            if (!detail.isEmpty()) {
                detail.append(", ");
            }
            detail.append(type.key())
                    .append("(base_exp=").append(settings.entry(type).baseExp())
                    .append(", max_level=").append(settings.maxLevel(type)).append(')');
        }
        plugin.getLogger().info("ギャザリング設定を読み込みました (" + detail + ").");
    }

    /**
     * 現在のギャザリング設定を取得する。
     *
     * @return ギャザリング設定
     */
    public GatheringSettings getSettings() {
        return settings;
    }

    /**
     * 指定種別・レベルの必要経験値を取得する。
     *
     * @param type  ギャザリング種別
     * @param level 現在レベル
     * @return 必要経験値
     */
    public long requiredExp(GatheringType type, int level) {
        return settings.requiredExp(type, level);
    }

    /**
     * 指定種別の最大レベルを取得する。
     *
     * @param type ギャザリング種別
     * @return 最大レベル
     */
    public int maxLevel(GatheringType type) {
        return settings.maxLevel(type);
    }

    /**
     * 次レベルまでの進捗率（0〜100）を取得する。
     * <p>
     * 最大レベル到達済みの場合は常に 100 を返す。
     *
     * @param player 対象プレイヤー
     * @param type   ギャザリング種別
     * @return 進捗率（%）
     */
    public int getProgressPercent(Player player, GatheringType type) {
        if (player == null || type == null) {
            return 0;
        }
        return getProgressPercent(PlayerData.getCached(player.getUniqueId()), type);
    }

    /**
     * 次レベルまでの進捗率（0〜100）を取得する。
     *
     * @param data 対象データ（{@code null} の場合は 0）
     * @param type ギャザリング種別
     * @return 進捗率（%）
     */
    public int getProgressPercent(PlayerData data, GatheringType type) {
        if (data == null || type == null) {
            return 0;
        }
        int level = data.getGatheringLevel(type);
        if (level >= maxLevel(type)) {
            return 100;
        }
        long required = requiredExp(type, level);
        if (required <= 0L) {
            return 100;
        }
        double ratio = (double) data.getGatheringExp(type) / (double) required;
        long percent = Math.round(ratio * 100.0);
        return (int) Math.max(0L, Math.min(100L, percent));
    }

    /**
     * プレイヤーへギャザリング経験値を付与する。
     *
     * @param player 対象プレイヤー
     * @param type   ギャザリング種別
     * @param amount 付与量（負の値は経験値の減算）
     * @return 付与結果。データ未読み込みの場合は {@link GatheringResult#NONE}
     */
    public GatheringResult grant(Player player, GatheringType type, long amount) {
        if (player == null) {
            return GatheringResult.NONE;
        }
        return grant(PlayerData.getCached(player.getUniqueId()), type, amount);
    }

    /**
     * ギャザリング経験値を付与し、必要に応じてレベルアップさせる。
     * <p>
     * 1 回の付与で複数レベル上がる場合にも対応する。最大レベル到達時は
     * 余剰経験値を切り捨て、到達時点の満タン値（1 つ前のレベルに必要な経験値）を
     * 保持する。負の値が指定された場合は現在の経験値のみを減算し、
     * レベルは下げない（0 未満にはしない）。
     *
     * @param data   対象データ（{@code null} の場合は何もしない）
     * @param type   ギャザリング種別
     * @param amount 付与量
     * @return 付与結果
     */
    public GatheringResult grant(PlayerData data, GatheringType type, long amount) {
        if (data == null || type == null || amount == 0L) {
            return GatheringResult.NONE;
        }

        int oldLevel = data.getGatheringLevel(type);
        int level = oldLevel;
        long exp = data.getGatheringExp(type);
        int max = maxLevel(type);

        if (amount < 0L) {
            long updated = exp + amount;
            if (updated < 0L) {
                updated = 0L;
            }
            data.setGatheringExp(type, updated);
            return new GatheringResult(type, oldLevel, oldLevel, 0, updated, requiredExp(type, oldLevel));
        }

        if (level >= max) {
            // カンスト時は加算しない。経験値バーは常に満タン表示に揃える。
            long maxExp = requiredExp(type, Math.max(1, max - 1));
            if (exp != maxExp) {
                exp = maxExp;
                data.setGatheringExp(type, exp);
            }
            return new GatheringResult(type, oldLevel, oldLevel, 0, exp, 0L);
        }

        if (exp > Long.MAX_VALUE - amount) {
            exp = Long.MAX_VALUE;
        } else {
            exp += amount;
        }

        int levelsGained = 0;
        while (level < max) {
            long required = requiredExp(type, level);
            if (exp < required) {
                break;
            }
            exp -= required;
            level++;
            levelsGained++;
        }
        if (level >= max) {
            // 上限到達時は余剰分を切り捨て、満タン表示にする。
            exp = requiredExp(type, Math.max(1, max - 1));
        }

        data.setGatheringLevel(type, level);
        data.setGatheringExp(type, exp);

        if (levelsGained > 0) {
            plugin.getLogger().info("ギャザリングレベルアップ: " + data.getName()
                    + " " + type.displayName() + " Lv." + oldLevel + " -> Lv." + level);
            LevelUpNotifier.notifyGathering(plugin, data, type, level);
        }

        return new GatheringResult(
                type, oldLevel, level, levelsGained, exp, requiredExp(type, level));
    }

    /**
     * ギャザリング経験値付与の結果。
     *
     * @param type         対象種別
     * @param oldLevel     付与前レベル
     * @param newLevel     付与後レベル
     * @param levelsGained 上昇したレベル数
     * @param currentExp   付与後の経験値
     * @param requiredExp  次レベルまでの必要経験値（最大レベル時は 0）
     */
    public record GatheringResult(
            GatheringType type,
            int oldLevel,
            int newLevel,
            int levelsGained,
            long currentExp,
            long requiredExp) {

        /** 経験値が付与されなかったことを表す結果。 */
        public static final GatheringResult NONE =
                new GatheringResult(null, 1, 1, 0, 0L, 0L);
    }
}
