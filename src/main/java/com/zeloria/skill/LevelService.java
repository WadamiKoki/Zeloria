package com.zeloria.skill;

import com.zeloria.core.Zeloria;
import com.zeloria.player.LevelUpNotifier;
import com.zeloria.core.YamlLoader;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerStatsService;
import org.bukkit.Bukkit;
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
                        + ", max_level=" + settings.maxLevel()
                        + ", tree_points_per_level=" + settings.treePointsPerLevel() + ").");
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
     * @return 最大レベル
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
     * 「現在のクラス」のレベル・経験値を進め、レベルアップ 1 回につき
     * {@link LevelSettings#treePointsPerLevel()} ポイントのクラスツリーポイントを
     * 付与する。クラス未選択の場合は経験値を付与しない。
     * <p>
     * 1 回の付与で複数レベル上がる場合にも対応する。最大レベル到達時は
     * 余剰経験値を切り捨て、到達時点の満タン値（1 つ前のレベルに必要な経験値）を
     * 保持する。既に最大レベルへ到達している場合は経験値を加算しないが、
     * 保存値が 0 の場合は満タン値へ補正する。
     * 負の値が指定された場合は現在の経験値のみを減算し、レベルは下げない
     * （0 未満にはしない）。
     *
     * @param data   対象データ（{@code null} の場合は何もしない）
     * @param amount 付与量
     * @return 付与結果
     */
    public LevelResult grant(PlayerData data, long amount) {
        if (data == null || amount == 0L) {
            return LevelResult.NONE;
        }

        String classId = data.getClassData().currentClassIdOrNull();
        if (classId == null || classId.isBlank()) {
            // クラス未選択時は経験値を付与しない。
            return LevelResult.NONE;
        }

        int oldLevel = data.getClassLevel(classId);
        if (amount < 0L) {
            // 負の値は現在の経験値のみを減算し、レベルは下げない（0 未満にはしない）。
            long updated = Math.max(0L, data.getClassExp(classId) + amount);
            data.setClassExp(classId, updated);
            PlayerStatsService statsService = plugin.getPlayerStatsService();
            Player player = data.getUuid() == null ? null : Bukkit.getPlayer(data.getUuid());
            if (statsService != null && player != null) {
                statsService.syncVanillaStats(player, data);
            }
            return new LevelResult(oldLevel, oldLevel, 0, updated, settings.requiredExp(oldLevel));
        }

        int maxLevel = settings.maxLevel();
        long maxLevelExp = settings.requiredExp(Math.max(1, maxLevel - 1));
        if (oldLevel >= maxLevel) {
            // カンスト時はそれ以上経験値を加算しない。過去に 0 で保存されていた場合は
            // 経験値バーが空のままにならないよう満タン値へ補正する。
            long currentExp = data.getClassExp(classId);
            if (currentExp != maxLevelExp) {
                currentExp = maxLevelExp;
                data.setClassExp(classId, currentExp);
            }
            return new LevelResult(oldLevel, oldLevel, 0, currentExp, 0L);
        }

        int level = oldLevel;
        long exp = data.getClassExp(classId);
        exp = exp > Long.MAX_VALUE - amount ? Long.MAX_VALUE : exp + amount;
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
            // 上限到達時は次レベルが存在しないため、余剰分は切り捨てて満タン表示にする。
            level = maxLevel;
            exp = maxLevelExp;
        }

        data.setClassLevel(classId, level);
        data.setClassExp(classId, exp);
        if (levelsGained > 0 && settings.treePointsPerLevel() > 0) {
            data.addClassTreePoints(classId, levelsGained * settings.treePointsPerLevel());
        }

        // レベルアップ時はクラス基礎ステータス（baseStats × レベル成長率）を再計算する。
        if (levelsGained > 0 && plugin.getPlayerStatsService() != null) {
            plugin.getPlayerStatsService().refreshClassStats(data);
        }

        // バニラのレベル／経験値バー表示を最新値へ追従させる。
        PlayerStatsService statsService = plugin.getPlayerStatsService();
        Player player = data.getUuid() == null ? null : Bukkit.getPlayer(data.getUuid());
        if (statsService != null && player != null) {
            statsService.syncVanillaStats(player, data);
        }

        // レベルアップ通知（チャット・効果音・パーティクル）は各プレイヤーの設定に従う。
        if (levelsGained > 0) {
            LevelUpNotifier.notifyClass(plugin, data, level);
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
