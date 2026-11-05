package com.zeloria.combat;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 攻撃力・会心・命中・2 段階防御・防御貫通をまとめて処理するダメージ計算。
 * <p>
 * 計算順序は次のとおり。
 * <ol>
 *     <li>命中判定（命中率 - 回避率）</li>
 *     <li>クリティカル判定（ダメージへ {@code criticalDamage%} を加算）</li>
 *     <li>第 1 段階防御力による軽減（{@code 100 / (100 + defense1)}）</li>
 *     <li>防御貫通を適用した第 2 段階防御力による軽減</li>
 *     <li>最低 1.0 ダメージを保証</li>
 * </ol>
 * 防御軽減は {@code d / (d + 100)} の曲線を使うため、防御力が高いほど
 * 1 点あたりの効果は逓減する。
 */
public final class DamageCalculator {

    private DamageCalculator() {
    }

    /**
     * ダメージを計算する。
     *
     * @param attackPower       攻撃力
     * @param multiplier        スキル倍率（通常攻撃は 1.0）
     * @param criticalRate      クリティカル率（%）
     * @param criticalDamage    クリティカルダメージ（% 加算）
     * @param accuracy          命中率（%）
     * @param evasion           回避率（%）
     * @param defense1          第 1 段階の防御力
     * @param defense2          第 2 段階の防御力
     * @param armorPenetration  防御貫通率（0.0〜1.0。第 2 段階にのみ適用）
     * @param allowCritical     クリティカルを許可するか
     * @return 計算結果
     */
    public static DamageResult calculate(
            double attackPower,
            double multiplier,
            double criticalRate,
            double criticalDamage,
            double accuracy,
            double evasion,
            double defense1,
            double defense2,
            double armorPenetration,
            boolean allowCritical) {

        double hitChance = clamp(accuracy, 0.0, 100.0) - clamp(evasion, 0.0, 100.0);
        if (randomPercent() >= Math.max(0.0, hitChance)) {
            return DamageResult.EVADED;
        }

        double damage = Math.max(0.0, attackPower) * Math.max(0.0, multiplier);
        boolean critical = false;
        if (allowCritical && randomPercent() < clamp(criticalRate, 0.0, 100.0)) {
            critical = true;
            damage *= 1.0 + Math.max(0.0, criticalDamage) / 100.0;
        }

        damage *= reductionFactor(defense1);

        double penetration = clamp(armorPenetration, 0.0, 1.0);
        double effectiveDefense2 = Math.max(0.0, defense2) * (1.0 - penetration);
        damage *= reductionFactor(effectiveDefense2);

        if (damage <= 0.0) {
            return new DamageResult(0.0, critical, false);
        }
        return new DamageResult(Math.max(1.0, damage), critical, false);
    }

    /**
     * 防御力 1 点あたりの軽減係数を返す。
     *
     * @param defense 防御力
     * @return 軽減係数（0.0〜1.0）
     */
    public static double reductionFactor(double defense) {
        double value = Math.max(0.0, defense);
        return 100.0 / (100.0 + value);
    }

    private static double randomPercent() {
        return ThreadLocalRandom.current().nextDouble() * 100.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
