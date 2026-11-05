package com.zeloria.skill;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーごと・スキルごとのクールダウンをメモリ上で管理する。
 * <p>
 * 実行中の判定はメモリ上のマップを使い、退出・サーバー停止時は
 * {@link #serialize(UUID)} で文字列化してプレイヤーデータへ保存する。
 * ログイン時は {@link #restore(UUID, String)} で復元する。
 * メインスレッドからも非同期処理からも参照できるよう、内部マップには
 * {@link ConcurrentHashMap} を使用する。プレイヤー退出時は
 * {@link #clear(UUID)} でエントリを破棄し、メモリリークを防ぐ。
 */
public final class CooldownService {

    /** プレイヤー UUID → （スキル ID → 解除時刻ミリ秒）。 */
    private final Map<UUID, ConcurrentHashMap<String, Long>> expirations = new ConcurrentHashMap<>();

    /**
     * 指定スキルの残りクールダウンを取得する。
     * <p>
     * 解除済みのエントリは取得時に削除する。
     *
     * @param playerId プレイヤー UUID
     * @param skillId  スキル ID
     * @return 残りミリ秒。クールダウン中でない場合は {@code 0}
     */
    public long remainingMillis(UUID playerId, String skillId) {
        if (playerId == null || skillId == null || skillId.isBlank()) {
            return 0L;
        }

        ConcurrentHashMap<String, Long> perSkill = expirations.get(playerId);
        if (perSkill == null) {
            return 0L;
        }

        Long expiresAt = perSkill.get(skillId.toLowerCase(Locale.ROOT));
        if (expiresAt == null) {
            return 0L;
        }

        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            perSkill.remove(skillId, expiresAt);
            if (perSkill.isEmpty()) {
                expirations.remove(playerId, perSkill);
            }
            return 0L;
        }
        return remaining;
    }

    /**
     * 指定スキルがクールダウン中か判定する。
     */
    public boolean isOnCooldown(UUID playerId, String skillId) {
        return remainingMillis(playerId, skillId) > 0L;
    }

    /**
     * クールダウンを開始する。
     *
     * @param playerId       プレイヤー UUID
     * @param skillId        スキル ID
     * @param durationMillis クールダウン（ミリ秒）。{@code 0} 以下は無視する
     */
    public void set(UUID playerId, String skillId, long durationMillis) {
        if (playerId == null || skillId == null || skillId.isBlank() || durationMillis <= 0L) {
            return;
        }

        long expiresAt = System.currentTimeMillis() + durationMillis;
        expirations.computeIfAbsent(playerId, key -> new ConcurrentHashMap<>())
                .put(skillId.toLowerCase(Locale.ROOT), expiresAt);
    }

    /**
     * 指定プレイヤーのクールダウンをすべて破棄する（退出時用）。
     */
    public void clear(UUID playerId) {
        if (playerId != null) {
            expirations.remove(playerId);
        }
    }

    /**
     * 指定プレイヤーの有効なクールダウンを DB 保存用の文字列へ変換する。
     * <p>
     * 形式は {@code skillId=解除時刻ミリ秒} を {@code ,} で連結したもの。
     * 既に解除済みのエントリは含めない。
     *
     * @param playerId プレイヤー UUID
     * @return シリアライズ結果。有効なクールダウンが無い場合は空文字
     */
    public String serialize(UUID playerId) {
        Map<String, Long> snapshot = snapshot(playerId);
        if (snapshot.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        long now = System.currentTimeMillis();
        snapshot.forEach((skillId, expiresAt) -> {
            if (expiresAt == null || expiresAt <= now) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(skillId).append('=').append(expiresAt);
        });
        return builder.toString();
    }

    /**
     * {@link #serialize(UUID)} で生成した文字列を復元する。
     * <p>
     * 不正な要素・解除済みの要素は読み飛ばす。
     *
     * @param playerId プレイヤー UUID
     * @param raw      シリアライズ文字列
     */
    public void restore(UUID playerId, String raw) {
        if (playerId == null || raw == null || raw.isBlank()) {
            return;
        }

        for (String element : raw.split(",")) {
            int separator = element.indexOf('=');
            if (separator <= 0 || separator >= element.length() - 1) {
                continue;
            }
            String skillId = element.substring(0, separator).trim();
            String millis = element.substring(separator + 1).trim();
            if (skillId.isEmpty()) {
                continue;
            }
            try {
                restore(playerId, skillId, Long.parseLong(millis));
            } catch (NumberFormatException ignored) {
                // 破損したエントリは無視する。
            }
        }
    }

    /**
     * 指定スキルの解除時刻を直接復元する。
     *
     * @param playerId プレイヤー UUID
     * @param skillId  スキル ID
     * @param expiresAt 解除時刻ミリ秒
     */
    public void restore(UUID playerId, String skillId, long expiresAt) {
        if (playerId == null || skillId == null || skillId.isBlank()
                || expiresAt <= System.currentTimeMillis()) {
            return;
        }
        expirations.computeIfAbsent(playerId, key -> new ConcurrentHashMap<>())
                .put(skillId.toLowerCase(Locale.ROOT), expiresAt);
    }

    /**
     * 指定プレイヤーの有効なクールダウンをコピーして返す。
     *
     * @param playerId プレイヤー UUID
     * @return スキル ID → 解除時刻ミリ秒の不変コピー
     */
    public Map<String, Long> snapshot(UUID playerId) {
        if (playerId == null) {
            return Map.of();
        }
        ConcurrentHashMap<String, Long> perSkill = expirations.get(playerId);
        return perSkill == null ? Map.of() : Map.copyOf(perSkill);
    }

    /**
     * すべてのクールダウンを破棄する（プラグイン無効化時用）。
     */
    public void clearAll() {
        expirations.clear();
    }
}
