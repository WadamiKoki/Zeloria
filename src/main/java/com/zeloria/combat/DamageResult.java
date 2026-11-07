package com.zeloria.combat;

/**
 * カスタムダメージ計算の結果。
 * <p>
 * {@link #damage()} が 0 以下の場合は「無効（ダメージなし）」を表し、適用側は HP を減らさない。
 *
 * @param damage   最終ダメージ（回避・無効時は 0 以下）
 * @param critical クリティカルが発生したか
 * @param evaded   回避されたか
 */
public record DamageResult(double damage, boolean critical, boolean evaded) {

    /** 回避結果。 */
    public static final DamageResult EVADED = new DamageResult(0.0, false, true);
}
