package com.zeloria.player;

import java.sql.Timestamp;

/**
 * 管理コマンドの {@code /admin info} 用に、プレイヤーデータと接続履歴をまとめた値。
 *
 * @param snapshot     プレイヤーデータのスナップショット
 * @param lastInstance 最後に接続したサーバーインスタンス名（未記録は空文字）
 * @param lastLogin    最終ログイン日時（未記録は {@code null}）
 * @param lastUpdated  最終保存日時（未記録は {@code null}）
 */
public record PlayerStatus(
        PlayerSnapshot snapshot,
        String lastInstance,
        Timestamp lastLogin,
        Timestamp lastUpdated
) {

    public PlayerStatus {
        lastInstance = lastInstance == null ? "" : lastInstance;
    }
}
