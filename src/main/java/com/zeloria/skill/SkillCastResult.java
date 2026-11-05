package com.zeloria.skill;

import com.zeloria.combat.DamageResult;
import com.zeloria.skill.Skill;

/**
 * スキル発動判定の結果。
 * <p>
 * {@code sealed interface} と record パターンにより、呼び出し側（{@code CombatListener}）は
 * if 文の連鎖ではなく網羅的な {@code switch} で結果ごとの通知を切り替えられる。
 */
public sealed interface SkillCastResult
        permits SkillCastResult.Success,
                SkillCastResult.OnCooldown,
                SkillCastResult.InsufficientMana,
                SkillCastResult.InsufficientHealth,
                SkillCastResult.NoTarget,
                SkillCastResult.NotOwned,
                SkillCastResult.UnknownSkill {

    /**
     * 発動に成功した。
     *
     * @param skill          発動したスキル
     * @param cooldownMillis 設定されたクールダウン
     * @param damage         与えたダメージ。攻撃効果が無い場合は {@code null}
     * @param targetName     対象名。攻撃効果が無い場合は空文字
     */
    record Success(Skill skill, long cooldownMillis, DamageResult damage, String targetName)
            implements SkillCastResult {
    }

    /**
     * クールダウン中のため発動できなかった。
     *
     * @param skill           対象スキル
     * @param remainingMillis 残りクールダウン
     */
    record OnCooldown(Skill skill, long remainingMillis) implements SkillCastResult {
    }

    /**
     * マナ不足のため発動できなかった。
     *
     * @param skill    対象スキル
     * @param required 必要マナ
     * @param current  現在マナ
     */
    record InsufficientMana(Skill skill, double required, double current)
            implements SkillCastResult {
    }

    /**
     * HP 不足のため発動できなかった。
     *
     * @param skill    対象スキル
     * @param required 必要 HP
     * @param current  現在 HP
     */
    record InsufficientHealth(Skill skill, double required, double current)
            implements SkillCastResult {
    }

    /**
     * スキルの対象が見つからなかった。
     *
     * @param skill 対象スキル
     */
    record NoTarget(Skill skill) implements SkillCastResult {
    }

    /**
     * 現在のクラスでは使用できないスキルだった。
     *
     * @param skill 対象スキル
     */
    record NotOwned(Skill skill) implements SkillCastResult {
    }

    /**
     * スキル ID に対応する定義が存在しなかった。
     *
     * @param skillId スキル ID
     */
    record UnknownSkill(String skillId) implements SkillCastResult {
    }
}
