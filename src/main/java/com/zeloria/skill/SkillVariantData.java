package com.zeloria.skill;

import java.util.List;
import java.util.Locale;

/**
 * 派生スキル（スキル置換）の定義。
 * <p>
 * {@code config/SkillData/<classId>/variant/<variantId>.yml} から読み込む。
 *
 * @param id          複合派生スキル ID（{@code <classId>:<variantId>}）
 * @param baseSkillId 置換対象の複合スキル ID（{@code <classId>:<base-skill-id>}）
 * @param displayName 表示名（未設定時は複合 ID）
 * @param description 説明文
 * @param actions     順番に実行するアクション
 */
public record SkillVariantData(
        String id,
        String baseSkillId,
        String displayName,
        String description,
        List<VariantAction> actions) {

    public SkillVariantData {
        id = normalizeId(id);
        baseSkillId = normalizeId(baseSkillId);
        displayName = displayName == null || displayName.isBlank()
                ? id
                : displayName;
        description = description == null ? "" : description;
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
