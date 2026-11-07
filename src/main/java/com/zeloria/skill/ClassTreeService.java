package com.zeloria.skill;

import com.zeloria.player.PlayerData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * クラスツリーの解放判定・ポイント消費・リセットを担うサービス。
 * <p>
 * プレイヤーの進行状況（クラス別レベル・経験値・ツリーポイント・解放済みノード）は
 * {@link PlayerData} が保持する。このサービスは判定と更新だけを行い、
 * GUI の描画は {@code ClassTreeGUI} が担当する。
 */
public final class ClassTreeService {

    private final ClassTreeManager classTreeManager;

    public ClassTreeService(ClassTreeManager classTreeManager) {
        this.classTreeManager = classTreeManager;
    }

    /**
     * クラス ID からツリー定義を取得する。
     */
    public Optional<ClassTreeData> findTree(String classId) {
        return classTreeManager.find(classId);
    }

    /**
     * ノードの解放状態を判定する。
     *
     * @param data    対象プレイヤーデータ
     * @param classId クラス ID
     * @param nodeId  ノード ID
     * @return 判定結果
     */
    public AllocationStatus status(PlayerData data, String classId, String nodeId) {
        if (data == null) {
            return AllocationStatus.NO_CLASS;
        }

        ClassTreeData tree = classTreeManager.find(classId).orElse(null);
        if (tree == null) {
            return AllocationStatus.NO_CLASS;
        }

        ClassTreeNode node = tree.node(nodeId);
        if (node == null) {
            return AllocationStatus.UNKNOWN;
        }

        if (data.isClassNodeAllocated(classId, node.id())) {
            return AllocationStatus.ALLOCATED;
        }

        if (!hasRequiredNodes(data, classId, node) || !hasAnyRequiredNode(data, classId, node)) {
            return AllocationStatus.PREREQUISITE;
        }

        if (data.getClassTreePoints(classId) < node.cost()) {
            return AllocationStatus.INSUFFICIENT_POINTS;
        }

        return AllocationStatus.UNLOCKABLE;
    }

    /**
     * ノードを解放できるか判定する。
     */
    public boolean canAllocate(PlayerData data, String classId, String nodeId) {
        return status(data, classId, nodeId) == AllocationStatus.UNLOCKABLE;
    }

    /**
     * ノードを解放し、必要ポイントを消費する。
     *
     * @param data    対象プレイヤーデータ
     * @param classId クラス ID
     * @param nodeId  ノード ID
     * @return 解放結果
     */
    public AllocationResult allocate(PlayerData data, String classId, String nodeId) {
        AllocationStatus current = status(data, classId, nodeId);
        switch (current) {
            case NO_CLASS:
                return AllocationResult.NO_CLASS;
            case UNKNOWN:
                return AllocationResult.UNKNOWN;
            case ALLOCATED:
                return AllocationResult.ALREADY_ALLOCATED;
            case PREREQUISITE:
                return AllocationResult.PREREQUISITE_NOT_MET;
            case INSUFFICIENT_POINTS:
                return AllocationResult.NOT_ENOUGH_POINTS;
            case UNLOCKABLE:
            default:
                break;
        }

        ClassTreeData tree = classTreeManager.find(classId).orElse(null);
        if (tree == null) {
            return AllocationResult.NO_CLASS;
        }
        ClassTreeNode node = tree.node(nodeId);
        if (node == null) {
            return AllocationResult.UNKNOWN;
        }

        int points = data.getClassTreePoints(classId);
        if (points < node.cost() || !data.allocateClassNode(classId, node.id())) {
            return AllocationResult.NOT_ENOUGH_POINTS;
        }
        data.setClassTreePoints(classId, points - node.cost());
        return AllocationResult.SUCCESS;
    }

    /**
     * 指定クラスの解放済みノードをすべて取り消し、消費したポイントを返還する。
     *
     * @param data    対象プレイヤーデータ
     * @param classId クラス ID
     * @return 返還したポイント
     */
    public int reset(PlayerData data, String classId) {
        if (data == null) {
            return 0;
        }

        ClassTreeData tree = classTreeManager.find(classId).orElse(null);
        if (tree == null) {
            return 0;
        }

        int refund = 0;
        for (String nodeId : data.getAllocatedClassNodes(classId)) {
            ClassTreeNode node = tree.node(nodeId);
            if (node != null) {
                refund += node.cost();
            }
        }

        data.clearAllocatedClassNodes(classId);
        data.addClassTreePoints(classId, refund);
        return refund;
    }

    /**
     * 解放済みノードの {@code statEffects} を合算して返す。
     *
     * @param data    対象プレイヤーデータ
     * @param classId クラス ID
     * @return ステータス項目 → 加算値（該当なしの場合は空マップ）
     */
    public Map<String, Double> getAggregatedStatEffects(PlayerData data, String classId) {
        return aggregateEffects(data, classId, true);
    }

    /**
     * 解放済みノードの {@code skillEffects} を合算して返す。
     *
     * @param data    対象プレイヤーデータ
     * @param classId クラス ID
     * @return スキル効果キー → 加算値（該当なしの場合は空マップ）
     */
    public Map<String, Double> getAggregatedSkillEffects(PlayerData data, String classId) {
        return aggregateEffects(data, classId, false);
    }

    /**
     * 指定スキルに対応する派生スキル ID を返す。
     * <p>
     * 解放済みの赤ノードのうち、{@code replaceSkill} が
     * {@code <skillId>} または {@code <skillId>_} で始まるものを探す。
     *
     * @param data    対象プレイヤーデータ
     * @param classId クラス ID
     * @param skillId 元スキル ID
     * @return 派生スキル ID（なければ {@code null}）
     */
    public String getReplacementForSkill(PlayerData data, String classId, String skillId) {
        if (data == null) {
            return null;
        }
        ClassTreeData tree = classTreeManager.find(classId).orElse(null);
        if (tree == null) {
            return null;
        }

        String normalizedSkill = DefinitionParser.normalizeId(skillId);
        for (String nodeId : data.getAllocatedClassNodes(classId)) {
            ClassTreeNode node = tree.node(nodeId);
            if (node == null) {
                continue;
            }
            String replacement = node.replaceSkill();
            if (replacement == null) {
                continue;
            }
            if (normalizedSkill.isEmpty()
                    || replacement.equals(normalizedSkill)
                    || replacement.startsWith(normalizedSkill + "_")) {
                return replacement;
            }
        }
        return null;
    }

    private Map<String, Double> aggregateEffects(PlayerData data,
                                                 String classId,
                                                 boolean statEffects) {
        if (data == null) {
            return Map.of();
        }
        ClassTreeData tree = classTreeManager.find(classId).orElse(null);
        if (tree == null) {
            return Map.of();
        }

        Map<String, Double> aggregated = new LinkedHashMap<>();
        for (String nodeId : data.getAllocatedClassNodes(classId)) {
            ClassTreeNode node = tree.node(nodeId);
            if (node == null) {
                continue;
            }
            Map<String, Double> effects = statEffects
                    ? node.statEffects()
                    : node.skillEffects();
            for (Map.Entry<String, Double> entry : effects.entrySet()) {
                aggregated.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        return aggregated.isEmpty() ? Map.of() : Map.copyOf(aggregated);
    }

    /**
     * 前提条件のうち、まだ解放されていないノード ID を取得する（AND 条件）。
     */
    public List<String> missingRequirements(PlayerData data, String classId, ClassTreeNode node) {
        List<String> missing = new ArrayList<>();
        if (data == null || node == null) {
            return missing;
        }
        for (String prerequisite : node.requires()) {
            if (!data.isClassNodeAllocated(classId, prerequisite)) {
                missing.add(prerequisite);
            }
        }
        return missing;
    }

    /**
     * OR 条件のうち、まだ解放されていないノード ID を取得する。
     * <p>
     * {@code requiresAny} が空の場合は空リストを返す。いずれか 1 つでも解放済みなら空リスト。
     */
    public List<String> missingAnyRequirements(PlayerData data, String classId, ClassTreeNode node) {
        List<String> missing = new ArrayList<>();
        if (data == null || node == null || node.requiresAny().isEmpty()) {
            return missing;
        }
        for (String prerequisite : node.requiresAny()) {
            if (data.isClassNodeAllocated(classId, prerequisite)) {
                return List.of();
            }
            missing.add(prerequisite);
        }
        return missing;
    }

    private boolean hasRequiredNodes(PlayerData data, String classId, ClassTreeNode node) {
        for (String prerequisite : node.requires()) {
            if (!data.isClassNodeAllocated(classId, prerequisite)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAnyRequiredNode(PlayerData data, String classId, ClassTreeNode node) {
        Set<String> prerequisites = node.requiresAny();
        if (prerequisites.isEmpty()) {
            return true;
        }
        for (String prerequisite : prerequisites) {
            if (data.isClassNodeAllocated(classId, prerequisite)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ノードの解放状態。
     */
    public enum AllocationStatus {
        /** 解放済み。 */
        ALLOCATED,
        /** 前提条件を満たし、ポイントも足りている。 */
        UNLOCKABLE,
        /** 前提条件を満たしていない。 */
        PREREQUISITE,
        /** ツリーポイントが足りない。 */
        INSUFFICIENT_POINTS,
        /** クラスツリーに存在しないノード。 */
        UNKNOWN,
        /** クラスまたはツリー定義が無い。 */
        NO_CLASS
    }

    /**
     * ノード解放の結果。
     */
    public enum AllocationResult {
        /** 解放に成功した。 */
        SUCCESS,
        /** すでに解放済み。 */
        ALREADY_ALLOCATED,
        /** 前提条件を満たしていない。 */
        PREREQUISITE_NOT_MET,
        /** ツリーポイントが足りない。 */
        NOT_ENOUGH_POINTS,
        /** クラスツリーに存在しないノード。 */
        UNKNOWN,
        /** クラスまたはツリー定義が無い。 */
        NO_CLASS
    }
}
