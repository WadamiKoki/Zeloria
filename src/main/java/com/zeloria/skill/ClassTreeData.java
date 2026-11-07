package com.zeloria.skill;

import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1 クラス分のツリー定義。
 * <p>
 * {@code config/ClassTree/<classId>.yml} を読み込んで生成する。共通形状
 * （{@link TreeData}）に存在するノードだけを保持する。
 *
 * @param classId クラス ID
 * @param title   このクラス専用の GUI タイトル（未指定は {@code null}）
 * @param nodes   ノード ID → 定義（YAML の読み込み順）
 */
public record ClassTreeData(
        String classId,
        Component title,
        Map<String, ClassTreeNode> nodes
) {

    public ClassTreeData {
        classId = DefinitionParser.normalizeId(classId);
        Map<String, ClassTreeNode> copy = new LinkedHashMap<>();
        if (nodes != null) {
            nodes.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) {
                    copy.put(DefinitionParser.normalizeId(key), value);
                }
            });
        }
        nodes = Collections.unmodifiableMap(copy);
    }

    /**
     * ノード ID から定義を取得する。
     *
     * @param id ノード ID
     * @return 見つからない場合は {@code null}
     */
    public ClassTreeNode node(String id) {
        return nodes.get(DefinitionParser.normalizeId(id));
    }

    /**
     * ツリーのルートノード ID を取得する。
     * <p>
     * {@code root} が定義されていればそれを優先し、無ければ前提条件を持たない
     * 最初のノードをルートとして扱う。
     *
     * @return ルートノード ID。ノードが無い場合は {@code null}
     */
    public String rootId() {
        if (nodes.containsKey("root")) {
            return "root";
        }
        for (ClassTreeNode node : nodes.values()) {
            if (!node.hasPrerequisites()) {
                return node.id();
            }
        }
        return null;
    }
}
