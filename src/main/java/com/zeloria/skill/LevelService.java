package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStats;
import com.zeloria.player.PlayerStatsService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * レベル・経験値の進行を管理するサービス。
 * <p>
 * 必要経験値の算出とレベルアップ処理を担当し、値の保持は {@link PlayerStats} に委譲する。
 * 設定は {@code config/LevelData/level_settings.yml} から読み込み、
 * {@code /admin reload} 時に {@link #reload()} を呼ぶことで反映できる。
 */
public class LevelService {

    /** レベル設定の YAML キー。 */
    public static final String SETTINGS_KEY = "LevelData/level_settings.yml";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private volatile LevelSettings settings;

    public LevelService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * レベル設定を再読み込みする。
     */
    public synchronized void reload() {
        FileConfiguration config = yamlLoader.getConfig(SETTINGS_KEY);
        if (config == null) {
            config = yamlLoader.getConfig("level_settings");
        }
        this.settings = LevelSettings.fromConfig(config);
        plugin.getLogger().info(
                "レベル設定を読み込みました (base_exp=" + settings.baseExp()
                        + ", growth=" + settings.expGrowth()
                        + ", stages=" + settings.stages().size() + ").");
    }

    /**
     * 現在のレベル設定を取得する。
     *
     * @return レベル設定
     */
    public LevelSettings getSettings() {
        return settings;
    }

    /**
     * 指定レベルの必要経験値を取得する。
     *
     * @param level レベル
     * @return 必要経験値
     */
    public long requiredExp(int level) {
        return settings.requiredExp(level);
    }

    /**
     * 到達可能な最大レベルを取得する。
     *
     * @return 最大レベル（上限なしの場合は {@link Integer#MAX_VALUE}）
     */
    public int maxLevel() {
        return settings.maxLevel();
    }

    /**
     * プレイヤーへ経験値を付与する。
     *
     * @param player 対象プレイヤー
     * @param amount 付与量
     * @return 付与結果。データ未読み込みの場合は空
     */
    public LevelResult grant(Player player, long amount) {
        if (player == null) {
            return LevelResult.NONE;
        }
        return grant(PlayerData.getCached(player.getUniqueId()), amount);
    }

    /**
     * プレイヤーデータへ経験値を付与し、必要に応じてレベルアップさせる。
     * <p>
     * 1 回の付与で複数レベル上がる場合にも対応する。最大レベルに到達した場合は
     * 余剰経験値を保持せず、必要経験値ちょうどで停止する。
     *
     * @param data   対象データ（{@code null} の場合は何もしない）
     * @param amount 付与量
     * @return 付与結果
     */
    public LevelResult grant(PlayerData data, long amount) {
        if (data == null || amount <= 0L) {
            return LevelResult.NONE;
        }

        PlayerStats stats = data.getStats();
        int oldLevel = stats.getLevel();
        int level = oldLevel;
        long exp = stats.getExp() + amount;
        int maxLevel = settings.maxLevel();
        int levelsGained = 0;

        while (level < maxLevel) {
            long required = settings.requiredExp(level);
            if (exp < required) {
                break;
            }
            exp -= required;
            level++;
            levelsGained++;
        }

        if (level >= maxLevel) {
            // 上限到達時は次レベルが存在しないため、端数を切り捨てる。
            level = maxLevel;
            exp = 0L;
        }

        stats.setLevel(level);
        stats.setExp(exp);

        // レベルアップ時はクラス基礎ステータス（baseStats + levelUpStats）を再計算する。
        if (levelsGained > 0 && plugin.getPlayerStatsService() != null) {
            plugin.getPlayerStatsService().refreshClassStats(data);
        }

        return new LevelResult(oldLevel, level, levelsGained, exp, settings.requiredExp(level));
    }

    /**
     * 経験値付与の結果。
     *
     * @param oldLevel     付与前のレベル
     * @param newLevel     付与後のレベル
     * @param levelsGained 上昇したレベル数
     * @param currentExp   付与後の現在経験値
     * @param requiredExp  次レベルに必要な経験値
     */
    public record LevelResult(
            int oldLevel,
            int newLevel,
            int levelsGained,
            long currentExp,
            long requiredExp
    ) {

        /** 変化なし。 */
        public static final LevelResult NONE = new LevelResult(0, 0, 0, 0L, 0L);

        /**
         * レベルアップが発生したか判定する。
         *
         * @return 1 以上上昇した場合は {@code true}
         */
        public boolean leveledUp() {
            return levelsGained > 0;
        }
    }
}
