package com.zeloria.player;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * プレイヤーごとのクラス状態（現在のクラス・解放済みクラス）を管理する。
 * <p>
 * {@link com.zeloria.player.PlayerData} がインスタンスを保持し、
 * {@link com.zeloria.player.PlayerSnapshot} を経由して非同期保存される。
 * GUI 操作はメインスレッド、保存は非同期スレッドで行われるため、
 * 各操作を {@code synchronized} で保護する。
 * <p>
 * 解放済みクラスはカンマ区切りの文字列として DB に保存する。クラス ID は
 * 小文字へ正規化されるため、区切り文字と衝突しない。
 */
public final class PlayerClassData {

    /** 解放済みクラスの保存形式で使用する区切り文字。 */
    private static final String SEPARATOR = ",";

    /** 現在のクラス ID。未選択の場合は空文字。 */
    private String currentClassId = "";

    /** 解放済みクラス ID（定義順を維持する）。 */
    private final Set<String> unlockedClasses = new LinkedHashSet<>();

    /**
     * 現在のクラス ID を取得する。
     *
     * @return クラス ID。未選択の場合は空の {@link Optional}
     */
    public synchronized Optional<String> currentClassId() {
        return currentClassId.isEmpty() ? Optional.empty() : Optional.of(currentClassId);
    }

    /**
     * 現在のクラス ID を取得する（未選択時は {@code null}）。
     */
    public synchronized String currentClassIdOrNull() {
        return currentClassId.isEmpty() ? null : currentClassId;
    }

    /**
     * 現在のクラスを設定する。
     *
     * @param classId クラス ID（{@code null} または空文字で未選択に戻す）
     */
    public synchronized void setCurrentClassId(String classId) {
        this.currentClassId = normalize(classId);
    }

    /**
     * 指定クラスが解放済みか判定する。
     */
    public synchronized boolean isUnlocked(String classId) {
        String normalized = normalize(classId);
        return !normalized.isEmpty() && unlockedClasses.contains(normalized);
    }

    /**
     * 指定クラスを解放する。
     *
     * @param classId クラス ID
     * @return 新たに解放された場合は {@code true}（既に解放済みなら {@code false}）
     */
    public synchronized boolean unlock(String classId) {
        String normalized = normalize(classId);
        return !normalized.isEmpty() && unlockedClasses.add(normalized);
    }

    /**
     * 解放済みクラス ID の不変コピーを取得する。
     */
    public synchronized Set<String> unlockedClasses() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(unlockedClasses));
    }

    /**
     * 解放済みクラスを保存用のカンマ区切り文字列へ変換する。
     */
    public synchronized String serializedUnlocked() {
        return String.join(SEPARATOR, unlockedClasses);
    }

    /**
     * 保存データから状態を復元する。
     * <p>
     * 既存の状態はすべて破棄してから復元するため、スナップショットの内容が
     * そのまま反映される。
     *
     * @param classId           現在のクラス ID（{@code null} 可）
     * @param serializedUnlocked 解放済みクラスのカンマ区切り文字列（{@code null} 可）
     */
    public synchronized void restore(String classId, String serializedUnlocked) {
        unlockedClasses.clear();
        if (serializedUnlocked != null) {
            for (String part : serializedUnlocked.split(SEPARATOR)) {
                String normalized = normalize(part);
                if (!normalized.isEmpty()) {
                    unlockedClasses.add(normalized);
                }
            }
        }
        this.currentClassId = normalize(classId);
    }

    /**
     * クラス ID を小文字・前後空白なしへ正規化する。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
