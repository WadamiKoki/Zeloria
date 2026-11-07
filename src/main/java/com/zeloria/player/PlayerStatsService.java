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
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * プレイヤーの RPG ステータスを Bukkit のプレイヤーへ反映するサービス。
 * <p>
 * 現時点でゲーム内へ反映するのは移動速度（{@code setWalkSpeed}）、満腹度ゲージ
 * （MP バー）、バニラのレベル／経験値バー（RPG レベル／経験値）である。
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
     * レベルアップ成長率（{@code levelUpPercent}）を再計算する。
     *
     * @param player 対象プレイヤー
     * @param data   プレイヤーデータ
     */
    public void apply(Player player, PlayerData data) {
        if (player == null || data == null) {
            return;
        }
        refreshClassStats(data);
        applyClassTreeStats(data);
        apply(player, data.getStats());
    }

    /**
     * 解放済みツリーノードの {@code statEffects} を基礎ステータスへ加算する。
     * <p>
     * 計算順序は「基礎値 → 装備補正 → ツリー固定値 → ツリー割合」。
     * 割合キーは {@code <項目名>Percent}（例: {@code attackPowerPercent}）。
     * 装備補正は {@link PlayerStats.Bonuses} に分離されているため、ここでは
     * 基礎値に対して割合を計算し、基礎値側へ上乗せする（基礎値が 0 の項目のみ
     * 装備補正込みの現在値を基準にする）。
     */
    private void applyClassTreeStats(PlayerData data) {
        ClassTreeService classTreeService = plugin.getClassTreeService();
        if (classTreeService == null) {
            return;
        }
        String classId = data.getClassData().currentClassIdOrNull();
        if (classId == null || classId.isBlank()) {
            return;
        }
        ClassManager classManager = plugin.getClassManager();
        if (classManager == null
                || classManager.current(data.getClassData()).isEmpty()) {
            // refreshClassStats が基礎値を更新できていない場合は、
            // ツリー効果を重複加算しないよう何もしない。
            return;
        }

        Map<String, Double> effects = classTreeService.getAggregatedStatEffects(data, classId);
        if (effects.isEmpty()) {
            return;
        }

        PlayerStats stats = data.getStats();
        applyTreeEffect(effects, "maxHealth",
                stats::getBaseMaxHealth, stats::getMaxHealth, stats::setMaxHealth);
        applyTreeEffect(effects, "maxMana",
                stats::getBaseMaxMana, stats::getMaxMana, stats::setMaxMana);
        applyTreeEffect(effects, "attackPower",
                stats::getBaseAttackPower, stats::getAttackPower, stats::setAttackPower);
        applyTreeEffect(effects, "defense1",
                stats::getBaseDefense1, stats::getDefense1, stats::setDefense1);
        applyTreeEffect(effects, "defense2",
                stats::getBaseDefense2, stats::getDefense2, stats::setDefense2);
        applyTreeEffect(effects, "criticalRate",
                stats::getBaseCriticalRate, stats::getCriticalRate, stats::setCriticalRate);
        applyTreeEffect(effects, "criticalDamage",
                stats::getBaseCriticalDamage, stats::getCriticalDamage, stats::setCriticalDamage);
        applyTreeEffect(effects, "accuracy",
                stats::getBaseAccuracy, stats::getAccuracy, stats::setAccuracy);
        applyTreeEffect(effects, "evasion",
                stats::getBaseEvasion, stats::getEvasion, stats::setEvasion);
        applyTreeEffect(effects, "dodgeCooldownReduction",
                stats::getBaseDodgeCooldownReduction, stats::getDodgeCooldownReduction,
                stats::setDodgeCooldownReduction);
        applyTreeEffect(effects, "cooldownReduction",
                stats::getBaseCooldownReduction, stats::getCooldownReduction,
                stats::setCooldownReduction);
        applyTreeEffect(effects, "moveSpeed",
                stats::getBaseMoveSpeed, stats::getMoveSpeed, stats::setMoveSpeed);
        applyTreeEffect(effects, "healthRegenFlat",
                stats::getBaseHealthRegenFlat, stats::getHealthRegenFlat,
                stats::setHealthRegenFlat);
        applyTreeEffect(effects, "healthRegenPercent",
                stats::getBaseHealthRegenPercent, stats::getHealthRegenPercent,
                stats::setHealthRegenPercent);
        applyTreeEffect(effects, "manaRegenFlat",
                stats::getBaseManaRegenFlat, stats::getManaRegenFlat,
                stats::setManaRegenFlat);
        applyTreeEffect(effects, "manaRegenPercent",
                stats::getBaseManaRegenPercent, stats::getManaRegenPercent,
                stats::setManaRegenPercent);
        applyTreeEffect(effects, "armorPenetration",
                stats::getBaseArmorPenetration, stats::getArmorPenetration,
                stats::setArmorPenetration);
        applyTreeEffect(effects, "lifesteal",
                stats::getBaseLifesteal, stats::getLifesteal, stats::setLifesteal);
    }

    /**
     * 1 項目分の固定値・割合効果を適用する。
     */
    private static void applyTreeEffect(Map<String, Double> effects,
                                        String key,
                                        DoubleSupplier baseValue,
                                        DoubleSupplier currentValue,
                                        DoubleConsumer setter) {
        double flat = effects.getOrDefault(key, 0.0);
        double percent = effects.getOrDefault(key + "Percent", 0.0);
        if (flat == 0.0 && percent == 0.0) {
            return;
        }
        double base = baseValue.getAsDouble();
        double percentBase = base != 0.0 ? base : currentValue.getAsDouble();
        setter.accept(base + flat + percentBase * percent / 100.0);
    }

    /**
     * 現在のクラスとレベルから基礎ステータスを再計算し、{@link PlayerStats} へ上書きする。
     * <p>
     * 計算式は {@code baseStats × (1 + levelUpPercent / 100 × (level - 1))}。レベル 0 と 1 は
     * {@code baseStats} のみとなる。装備補正は {@link PlayerStats#getBonuses()} に
     * 分離して保持されるため、ここでは基礎値だけを更新する。
     * <p>
     * クラス未選択、またはクラス定義が見つからない場合は何もしない。
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
        ZeloriaClass definition = classManager.current(data.getClassData()).orElse(null);
        if (definition == null) {
            return;
        }

        PlayerStats stats = data.getStats();
        ClassStats effective = definition.statsAtLevel(stats.getLevel());
        effective.applyTo(stats);
    }

    /**
     * ステータスを反映する。
     * <p>
     * 移動速度は Bukkit の許容範囲（-1.0〜1.0）へクランプしてから適用する。
     * 非同期スレッドから呼ばれた場合はメインスレッドへ委譲する
     * （{@code setWalkSpeed} はプレイヤー状態を変更するため）。
     *
     * @param player 対象プレイヤー
     * @param stats  反映するステータス
     */
    public void apply(Player player, PlayerStats stats) {
        if (player == null || stats == null) {
            return;
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

        syncVanillaStats(player, stats);
    }

    /**
     * RPG ステータスをバニラの満腹度・レベル・経験値バーへ反映する。
     * <p>
     * <ul>
     *     <li>満腹度ゲージ: 現在 MP / 最大 MP の割合を 0〜20 に割り当てる（MP バーとして使用）</li>
     *     <li>満腹度の隠し値（saturation）: 常に 0（満腹度ゲージの揺れを防ぐ）</li>
     *     <li>レベル: RPG レベル</li>
     *     <li>経験値バー: 次レベルまでの進捗（{@link LevelService#requiredExp(int)} を使用）</li>
     * </ul>
     * 非同期スレッドから呼ばれた場合はメインスレッドへ委譲する。
     *
     * @param player 対象プレイヤー
     * @param stats  反映するステータス
     */
    public void syncVanillaStats(Player player, PlayerStats stats) {
        if (player == null || stats == null) {
            return;
        }

        int maxMana = (int) Math.round(stats.getMaxMana());
        int foodLevel = maxMana <= 0
                ? 0
                : (int) Math.round(clamp(stats.getCurrentMana() / maxMana, 0.0, 1.0) * 20.0);

        int level = Math.max(0, stats.getLevel());
        LevelService levelService = plugin.getLevelService();
        long requiredExp = levelService == null ? 0L : levelService.requiredExp(level);
        float expProgress = requiredExp <= 0L
                ? 0.0f
                : (float) clamp((double) stats.getExp() / (double) requiredExp, 0.0, 1.0);

        runOnMainThread(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setFoodLevel(foodLevel);
            player.setSaturation(0.0f);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
