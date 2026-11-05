package com.zeloria.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーごとの最終戦闘時刻を保持するサービス。
 * <p>
 * 戦闘中（ダメージを与えた／受けた）かどうかを判定するためだけの軽量な状態管理で、
 * 実際のダメージ計算には関与しない。{@code PlayerRegenTask} は本サービスの
 * 最終戦闘時刻を参照し、戦闘終了から一定時間が経過するまで自然回復を行わない。
 * <p>
 * ダメージイベントと回復タスクはどちらもメインスレッドで動作するが、
 * 参照が非同期にずれる可能性を考慮して {@link ConcurrentHashMap} で保持する。
 */
public final class CombatStateService {

    private final Map<UUID, Long> lastCombatAt = new ConcurrentHashMap<>();

    /**
     * 現在時刻を最終戦闘時刻として記録する。
     *
     * @param uuid プレイヤー UUID（{@code null} は無視する）
     */
    public void markInCombat(UUID uuid) {
        markInCombat(uuid, System.currentTimeMillis());
    }

    /**
     * 指定時刻を最終戦闘時刻として記録する。
     *
     * @param uuid プレイヤー UUID（{@code null} は無視する）
     * @param now  現在時刻（ミリ秒）
     */
    public void markInCombat(UUID uuid, long now) {
        if (uuid != null) {
            lastCombatAt.put(uuid, now);
        }
    }

    /**
     * 最終戦闘時刻を取得する。
     *
     * @param uuid プレイヤー UUID
     * @return 最終戦闘時刻（ミリ秒）。記録が無い場合は {@code null}
     */
    public Long lastCombatMillis(UUID uuid) {
        return uuid == null ? null : lastCombatAt.get(uuid);
    }

    /**
     * 最終戦闘から {@code delayMillis} が経過しているか判定する。
     * <p>
     * 戦闘記録が無いプレイヤー（ログイン直後など）は「経過済み」とみなす。
     *
     * @param uuid        プレイヤー UUID
     * @param now         現在時刻（ミリ秒）
     * @param delayMillis 戦闘終了とみなすまでの猶予（ミリ秒）
     * @return 猶予が経過していれば {@code true}
     */
    public boolean isOutOfCombat(UUID uuid, long now, long delayMillis) {
        Long lastCombat = lastCombatMillis(uuid);
        if (lastCombat == null) {
            return true;
        }
        return now - lastCombat >= Math.max(0L, delayMillis);
    }

    /**
     * 最終戦闘からの経過時間（ミリ秒）を返す。
     *
     * @param uuid プレイヤー UUID
     * @param now  現在時刻（ミリ秒）
     * @return 経過時間。戦闘記録が無い場合は {@link Long#MAX_VALUE}
     */
    public long millisSinceLastCombat(UUID uuid, long now) {
        Long lastCombat = lastCombatMillis(uuid);
        return lastCombat == null ? Long.MAX_VALUE : Math.max(0L, now - lastCombat);
    }

    /**
     * プレイヤーの戦闘記録を破棄する（退出・リスポーン時など）。
     *
     * @param uuid プレイヤー UUID（{@code null} は無視する）
     */
    public void clear(UUID uuid) {
        if (uuid != null) {
            lastCombatAt.remove(uuid);
        }
    }

    /**
     * すべての戦闘記録を破棄する（プラグイン停止時など）。
     */
    public void clearAll() {
        lastCombatAt.clear();
    }

    /**
     * 記録済みプレイヤー数を返す（デバッグ・テスト用）。
     *
     * @return 記録済みプレイヤー数
     */
    public int size() {
        return lastCombatAt.size();
    }

    @Override
    public String toString() {
        return "CombatStateService{tracked=" + size() + "}";
    }
}
