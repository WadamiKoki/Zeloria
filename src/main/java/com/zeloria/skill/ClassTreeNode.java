package com.zeloria.skill;

import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * クラスごとのツリーノード定義。
 * <p>
 * 位置とアイコンは {@link TreeShapeNode}（{@code TreeData/tree.yml}）が持ち、
 * このレコードは表示名・説明・必要ポイント・解放条件・効果を保持する。
 *
 * @param id           ノード ID（共通形状と一致させる）
 * @param displayName  表示名（MiniMessage 解決済み）
 * @param description  説明（MiniMessage 解決済み）
 * @param cost         解放に必要なツリーポイント
 * @param requires     すべて満たす必要がある前提ノード ID
 * @param requiresAny  1 つ以上満たす必要がある前提ノード ID
 * @param effects      効果説明（MiniMessage 解決済み）
 */
public record ClassTreeNode(
        String id,
        Component displayName,
        List<Component> description,
        int cost,
        Set<String> requires,
        Set<String> requiresAny,
        List<Component> effects
) {

    public ClassTreeNode {
        id = DefinitionParser.normalizeId(id);
        displayName = displayName == null ? Component.text(id) : displayName;
        description = description == null ? List.of() : List.copyOf(description);
        cost = Math.max(0, cost);
        requires = normalizeIds(requires);
        requiresAny = normalizeIds(requiresAny);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    /**
     * 前提条件が設定されているか判定する。
     */
    public boolean hasPrerequisites() {
        return !requires.isEmpty() || !requiresAny.isEmpty();
    }

    private static Set<String> normalizeIds(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String id = DefinitionParser.normalizeId(value);
            if (!id.isEmpty()) {
                normalized.add(id);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
