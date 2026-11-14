package com.zeloria.player;

import com.zeloria.combat.CombatService;
import com.zeloria.combat.CombatStateService;
import com.zeloria.core.Zeloria;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.ClassTreeService;
import com.zeloria.skill.LevelService;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤーの RPG ステータスを Bukkit のプレイヤーへ反映するサービス。
 * <p>
 * 現時点でゲーム内へ反映するのは移動速度（{@code setWalkSpeed}）、満腹度ゲージ
 * （MP バー）、バニラのレベル／経験値バー（現在クラスのレベル／経験値）である。
 * HP / MP のモデル値は {@link PlayerStats} 内に保持し、バニラの体力バーへの
 * HP 同期は {@link CombatService#syncPlayerHealth(Player)} が担う。リスポーン時に
 * 本クラスから呼び出して全回復を表示へ反映する。最大 HP / 最大 MP は
 * 自然回復の上限としてのみ機能する。
 * <p>
 * 戦闘状態の判定（自然回復の待機時間）は {@link CombatStateService} に委譲し、
 * 本クラスは「いつ回復してよいか」の判定だけを提供する。
 */
public final class PlayerStatsService {

    /** Bukkit の {@code setWalkSpeed} が受け付ける下限。 */
    public static final double MIN_WALK_SPEED = -1.0;

    /** Bukkit の {@code setWalkSpeed} が受け付ける上限。 */
    public static final double MAX_WALK_SPEED = 1.0;

    /**
     * 戦闘終了とみなすまでの猶予（ミリ秒）。
     * <p>
     * 最後にダメージを与えた／受けた時刻から 5 秒間は自然回復を行わない。
     */
    public static final long REGEN_COMBAT_DELAY_MILLIS = 5_000L;

    private final Zeloria plugin;
    private final CombatStateService combatStateService;

    public PlayerStatsService(Zeloria plugin, CombatStateService combatStateService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatStateService = Objects.requireNonNull(combatStateService, "combatStateService");
    }

    /**
     * キャッシュ済みのプレイヤーデータから RPG ステータスを取得する。
     *
     * @param player 対象プレイヤー
     * @return RPG ステータス。データ未読み込みの場合は {@code null}
     */
    public PlayerStats getStats(Player player) {
        if (player == null) {
            return null;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        return data == null ? null : data.getStats();
    }

    /**
     * キャッシュ済みデータを用いてステータスを反映する。
     *
     * @param player 対象プレイヤー
     */
    public void apply(Player player) {
        if (player == null) {
            return;
        }
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data != null) {
            apply(player, data);
        }
    }

    /**
     * 指定したプレイヤーデータのステータスを反映する。
     * <p>
     * 反映前に現在のクラス定義の基礎ステータス（{@code baseStats}）と
     * レベルアップ成長率（{@code levelUpPercent}）を再計算し、ツリー効果
     * （永続化されない補正）も再適用する。
     *
     * @param player 対象プレイヤー
     * @param data   プレイヤーデータ
     */
    public void apply(Player player, PlayerData data) {
        if (player == null || data == null) {
            return;
        }
        // HP / MP が変更されたらバニラの体力バー・満腹度（MP バー）へ即時同期する。
        registerVitalsChangeListener(player, data);

        // refreshClassStats が基礎値の再計算とツリー効果の再適用まで行う。
        refreshClassStats(data);

        PlayerStats stats = data.getStats();
        // 初回参加（DB 新規作成）時は既定値の現在 MP が 0 のため、クラス基礎値の
        // 反映後に HP / MP を最大値へ揃える。既存プレイヤーは DB の値を維持する。
        if (data.consumeNewPlayer()) {
            stats.restoreVitals();
        }
        // 死亡状態で保存されたデータ（HP 0）を読み込んだ場合、バニラ側で生存していれば
        // 上限まで回復させる。死亡中・独自死亡の待機中（スペクテイター）には干渉しない。
        if (!player.isDead() && !isAwaitingRespawn(player) && stats.getCurrentHealth() <= 0.0) {
            stats.restoreVitals();
        }

        float walkSpeed = (float) clamp(stats.getMoveSpeed(), MIN_WALK_SPEED, MAX_WALK_SPEED);
        runOnMainThread(player, () -> {
            if (player.isOnline()) {
                player.setWalkSpeed(walkSpeed);
            }
        });

        syncVanillaStats(player, data);
    }

    /**
     * 解放済みツリーノードの {@code statEffects} を「永続化されない補正（{@code treeBonuses}）」として
     * 適用する。
     * <p>
     * 計算順序は Wynncraft 式の加算プールに合わせ、次のとおり。
     * <ol>
     *     <li>基礎値（クラス基礎値 × レベル成長）</li>
     *     <li>ツリー効果を加算プールとして適用
     *         （{@code 基礎値 + flat + 基礎値 × percent / 100}）</li>
     *     <li>装備補正を加算（{@link PlayerStats} の取得メソッドで合算）</li>
     * </ol>
     * 割合キーは {@code <項目名>Percent}（例: {@code attackPowerPercent}）で、複数の割合効果は
     * 合算してから基礎値へ 1 回だけ掛ける。ツリー効果は基礎値へ直接書き込まず
     * {@link PlayerStats#applyTreeBonuses(PlayerStats.Bonuses)} で別枠に保持するため、
     * DB に保存される基礎値（{@code getBase*}）には混入しない。
     *
     * @param data  対象のプレイヤーデータ
     * @param stats 対象のステータス（基礎値の再計算済みであること）
     */
    private void applyClassTreeStats(PlayerData data, PlayerStats stats) {
        ClassTreeService classTreeService = plugin.getClassTreeService();
        if (classTreeService == null) {
            stats.clearTreeBonuses();
            return;
        }
        String classId = data.getClassData().currentClassIdOrNull();
        if (classId == null || classId.isBlank()) {
            stats.clearTreeBonuses();
            return;
        }

        Map<String, Double> effects = classTreeService.getAggregatedStatEffects(data, classId);
        if (effects.isEmpty()) {
            stats.clearTreeBonuses();
            return;
        }

        // 加算プール: 固定値と割合を合算した「基礎値に対する差分」を項目ごとに算出する。
        stats.applyTreeBonuses(new PlayerStats.Bonuses(
                treeValue(effects, "maxHealth", stats.getBaseMaxHealth()),
                treeValue(effects, "maxMana", stats.getBaseMaxMana()),
                treeValue(effects, "attackPower", stats.getBaseAttackPower()),
                treeValue(effects, "defense1", stats.getBaseDefense1()),
                treeValue(effects, "defense2", stats.getBaseDefense2()),
                treeValue(effects, "criticalRate", stats.getBaseCriticalRate()),
                treeValue(effects, "criticalDamage", stats.getBaseCriticalDamage()),
                treeValue(effects, "accuracy", stats.getBaseAccuracy()),
                treeValue(effects, "evasion", stats.getBaseEvasion()),
                treeValue(effects, "dodgeCooldownReduction", stats.getBaseDodgeCooldownReduction()),
                treeValue(effects, "cooldownReduction", stats.getBaseCooldownReduction()),
                treeValue(effects, "moveSpeed", stats.getBaseMoveSpeed()),
                treeValue(effects, "healthRegenFlat", stats.getBaseHealthRegenFlat()),
                treeValue(effects, "healthRegenPercent", stats.getBaseHealthRegenPercent()),
                treeValue(effects, "manaRegenFlat", stats.getBaseManaRegenFlat()),
                treeValue(effects, "manaRegenPercent", stats.getBaseManaRegenPercent()),
                treeValue(effects, "armorPenetration", stats.getBaseArmorPenetration()),
                treeValue(effects, "lifesteal", stats.getBaseLifesteal())));
    }

    /**
     * 1 項目分のツリー効果（固定値 + 割合）を加算プールとして算出する。
     * <p>
     * Wynncraft 式に合わせ、複数の割合効果は合算済みの {@code percent} を使って
     * {@code flat + 基礎値 × percent / 100} を 1 回だけ計算する。装備補正は含めない。
     *
     * @param effects 集計済みのツリー効果
     * @param key     項目名
     * @param base    基礎値（クラス基礎値 × レベル成長）
     * @return 基礎値へ加算する差分（効果がない場合は 0）
     */
    private static double treeValue(Map<String, Double> effects, String key, double base) {
        double flat = effects.getOrDefault(key, 0.0);
        double percent = effects.getOrDefault(key + "Percent", 0.0);
        if (flat == 0.0 && percent == 0.0) {
            return 0.0;
        }
        return flat + base * percent / 100.0;
    }

    /**
     * 現在のクラスとレベルから基礎ステータスを再計算し、{@link PlayerStats} へ上書きしたうえで
     * ツリー効果（永続化されない補正）を再適用する。
     * <p>
     * 計算式は {@code baseStats × (1 + levelUpPercent / 100 × (level - 1))}。レベル 1 は
     * {@code baseStats} のみとなる。
     * <p>
     * ツリー効果の割合は基礎値を基準に計算するため、基礎値を上書きした直後に
     * {@link #applyClassTreeStats(PlayerData, PlayerStats)} で毎回再計算する。
     * 装備補正は {@link PlayerStats#getBonuses()}、ツリー補正は
     * {@link PlayerStats#getTreeBonuses()} に分離して保持されるため、ここで更新するのは
     * 基礎値のみである（DB 保存値にツリー効果は含まれない）。
     * <p>
     * クラス未選択、またはクラス定義が見つからない場合はツリー補正のみ解除する。
     * メインスレッドから呼び出すこと。
     *
     * @param data 対象のプレイヤーデータ（{@code null} は何もしない）
     */
    public void refreshClassStats(PlayerData data) {
        if (data == null) {
            return;
        }
        ClassManager classManager = plugin.getClassManager();
        if (classManager == null) {
            return;
        }

        PlayerStats stats = data.getStats();
        ZeloriaClass definition = classManager.current(data.getClassData()).orElse(null);
        if (definition == null) {
            // クラス定義が無い場合はツリー補正も無効化する（基礎値はそのまま維持）。
            stats.clearTreeBonuses();
            return;
        }

        ClassStats effective = definition.statsAtLevel(data.getCurrentClassLevel());
        effective.applyTo(stats);
        // 基礎値の上書き後にツリー効果（加算プール）を再適用する。
        applyClassTreeStats(data, stats);
    }

    /**
     * RPG ステータスをバニラの満腹度・レベル・経験値バーへ反映する。
     * <p>
     * <ul>
     *     <li>満腹度ゲージ: 現在 MP / 最大 MP の割合を 0〜20 に割り当てる（MP バーとして使用）</li>
     *     <li>満腹度の隠し値（saturation）: 常に 20（満腹度が自然減少しないように固定）</li>
     *     <li>レベル: 現在選択中クラスのレベル</li>
     *     <li>経験値バー: 次レベルまでの進捗（{@link LevelService#requiredExp(int)} を使用、カンスト時は 0%）</li>
     * </ul>
     * 非同期スレッドから呼ばれた場合はメインスレッドへ委譲する。
     *
     * @param player 対象プレイヤー
     * @param data   反映するプレイヤーデータ
     */
    public void syncVanillaStats(Player player, PlayerData data) {
        if (player == null || data == null) {
            return;
        }

        PlayerStats stats = data.getStats();
        int maxMana = (int) Math.round(stats.getMaxMana());
        int foodLevel = maxMana <= 0
                ? 0
                : (int) Math.round(clamp(stats.getCurrentMana() / maxMana, 0.0, 1.0) * 20.0);

        int level = Math.max(0, data.getCurrentClassLevel());
        long currentExp = data.getCurrentClassExp();
        LevelService levelService = plugin.getLevelService();
        // カンスト時は次レベルが存在しないため、経験値バーを空（0%）で固定する。
        // プレイヤーヘッドとメニュー側は EXP: MAX / (MAX) 表示に統一している。
        boolean maxLevel = levelService != null && level >= levelService.maxLevel();
        long requiredExp = level <= 0 || levelService == null || maxLevel
                ? 0L
                : levelService.requiredExp(level);
        float expProgress = maxLevel
                ? 0.0f
                : requiredExp <= 0L
                        ? 0.0f
                        : (float) clamp((double) currentExp / (double) requiredExp, 0.0, 1.0);

        runOnMainThread(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setFoodLevel(foodLevel);
            // 満腹度は MP バー表示専用のため、バニラの空腹処理で減少しないよう
            // 隠し満腹度を最大値で固定し、消耗値もリセットする。
            player.setSaturation(20.0f);
            player.setExhaustion(0.0f);
            player.setLevel(level);
            player.setExp(expProgress);
        });
    }

    /**
     * 戦闘終了から猶予が経過し、自然回復してよい状態か判定する。
     *
     * @param uuid プレイヤー UUID
     * @param now  現在時刻（ミリ秒）
     * @return 回復可能であれば {@code true}
     */
    public boolean canRegenerate(UUID uuid, long now) {
        return combatStateService.isOutOfCombat(uuid, now, REGEN_COMBAT_DELAY_MILLIS);
    }

    /**
     * 死亡時の処理。HP を 0 にし、その時点を戦闘中として記録する。
     * <p>
     * 死亡中は自然回復の対象外とし、リスポーン時に {@link #handleRespawn(Player)} で
     * 全回復する。
     *
     * @param player 死亡したプレイヤー
     */
    public void handleDeath(Player player) {
        if (player == null) {
            return;
        }
        PlayerStats stats = getStats(player);
        if (stats != null) {
            stats.setCurrentHealth(0.0);
        }
        combatStateService.markInCombat(player.getUniqueId());
    }

    /**
     * リスポーン時の処理。HP / MP を全回復し、戦闘記録を破棄して
     * 即座に自然回復を再開できる状態にする。
     *
     * @param player リスポーンしたプレイヤー
     */
    public void handleRespawn(Player player) {
        if (player == null) {
            return;
        }
        PlayerStats stats = getStats(player);
        if (stats != null) {
            stats.restoreVitals();
        }
        combatStateService.clear(player.getUniqueId());
        apply(player);
        // バニラの体力バーが死亡時の 1.0（ハート 0.5 個）で残らないよう最大まで同期する。
        CombatService combatService = plugin.getCombatService();
        if (combatService != null) {
            combatService.syncPlayerHealth(player);
        }
    }

    /**
     * 戦闘状態サービスを取得する。
     *
     * @return 戦闘状態サービス
     */
    public CombatStateService getCombatStateService() {
        return combatStateService;
    }

    /**
     * 独自死亡処理の待機中（スペクテイター）か判定する。
     */
    private boolean isAwaitingRespawn(Player player) {
        return player != null
                && plugin.getDeathListener() != null
                && plugin.getDeathListener().isAwaiting(player);
    }

    private void runOnMainThread(Player player, Runnable task) {
        if (plugin.isEnabled() && !plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } else {
            task.run();
        }
    }

    /**
     * 現在 HP / MP の変更をバニラ表示へ即時反映するリスナーを登録する。
     * <p>
     * 退出後に古いプレイヤー参照を掴まないよう、通知時点の UUID から
     * オンラインプレイヤーを引き直す。実際の同期は
     * {@link PlayerStatusDisplayTask#refreshFromVitals(Player)} に委譲する。
     *
     * @param player 対象プレイヤー
     * @param data   対象のプレイヤーデータ
     */
    private void registerVitalsChangeListener(Player player, PlayerData data) {
        UUID playerId = player.getUniqueId();
        // setVitalsChangeListener は既存のリスナーを上書きするため、
        // apply が複数回呼ばれてもリスナーが重複登録されることはない。
        data.getStats().setVitalsChangeListener(() -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Player online = plugin.getServer().getPlayer(playerId);
            if (online == null || !online.isOnline()) {
                return;
            }
            PlayerStatusDisplayTask statusDisplayTask = plugin.getPlayerStatusDisplayTask();
            if (statusDisplayTask != null) {
                statusDisplayTask.refreshFromVitals(online);
            }
        });
    }

    /**
     * プレイヤーの現在 HP / MP 変更リスナーを解除する。
     * <p>
     * 退出時に呼び出し、{@link PlayerStats} からプレイヤー由来の処理への
     * 参照を確実に破棄する。
     *
     * @param player 退出するプレイヤー
     */
    public void unregisterVitalsChangeListener(Player player) {
        if (player == null) {
            return;
        }
        unregisterVitalsChangeListener(player.getUniqueId());
    }

    /**
     * 指定 UUID の現在 HP / MP 変更リスナーを解除する。
     *
     * @param playerId 対象プレイヤーの UUID
     */
    public void unregisterVitalsChangeListener(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlayerData data = PlayerData.getCached(playerId);
        if (data != null && data.getStats() != null) {
            data.getStats().clearVitalsChangeListener();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
