package com.zeloria.combat;

/**
 * カスタムダメージ計算の結果。
 *
 * @param damage   最終ダメージ（回避時は 0）
 * @param critical クリティカルが発生したか
 * @param evaded   回避されたか
 */
public record DamageResult(double damage, boolean critical, boolean evaded) {

    /** 回避結果。 */
    public static final DamageResult EVADED = new DamageResult(0.0, false, true);
}
