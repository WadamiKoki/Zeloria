package com.zeloria.skill;

import java.util.List;
import java.util.Locale;

/**
 * 派生スキル（スキル置換）の定義。
 * <p>
 * {@code config/SkillVariant/<classId>/<variantId>.yml} から読み込む。
 *
 * @param variantId   派生スキル ID
 * @param baseSkill   置換対象の元スキル ID
 * @param displayName 表示名（未設定時は {@code variantId}）
 * @param description 説明文
 * @param actions     順番に実行するアクション
 */
public record SkillVariantData(
        String variantId,
        String baseSkill,
        String displayName,
        String description,
        List<VariantAction> actions) {

    public SkillVariantData {
        variantId = normalizeId(variantId);
        baseSkill = normalizeId(baseSkill);
        displayName = displayName == null || displayName.isBlank()
                ? variantId
                : displayName;
        description = description == null ? "" : description;
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
