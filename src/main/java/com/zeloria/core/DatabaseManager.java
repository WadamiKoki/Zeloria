package com.zeloria.core;

import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * データベースのスキーマ管理と非同期実行基盤を提供するクラス。
 * <p>
 * {@link DatabaseService} が管理する HikariCP のコネクションプールを利用し、
 * 起動時に {@code players} テーブルを自動生成する。DDL とリポジトリのクエリは
 * 専用のスレッドプールで実行されるため、メインスレッドをブロックしない。
 * <p>
 * {@link #initialize()} は何度呼び出してもテーブル生成は 1 度だけ実行され、
 * 同じ {@link CompletableFuture} を返す。プレイヤーデータの読み書きは
 * この future の完了を待ってから実行されるため、起動直後のログインでも
 * テーブル未作成エラーにならない。
 */
public class DatabaseManager {

    /** プレイヤーデータを格納するテーブル名。 */
    public static final String PLAYERS_TABLE = "players";

    /**
     * {@code players} テーブルの DDL。
     * <p>
     * アイテム列（仮想装備）は NBT を保持した
     * Base64 文字列を格納するため {@code LONGTEXT} を使用する。
     * {@code last_updated} は INSERT / UPDATE 時に自動更新される。
     * <p>
     * RPG ステータス関連のカラム（{@code max_health} 以降）は
     * {@code PlayerStats} の値をそのまま保持する。旧カラム（{@code hp} / {@code atk} /
     * {@code crit_dmg} / {@code cdr} / {@code move_speed} など）は既存インストールとの
     * 互換のために残しており、読み込み時に新カラムが NULL の場合のみ代替値として使う。
     */
    private static final String CREATE_PLAYERS_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                uuid CHAR(36) NOT NULL,
                player_name VARCHAR(64) NOT NULL,
                level INT NOT NULL DEFAULT 0,
                exp BIGINT NOT NULL DEFAULT 0,
                hp DOUBLE NOT NULL DEFAULT 20,
                mp DOUBLE NOT NULL DEFAULT 0,
                money BIGINT NOT NULL DEFAULT 0,
                atk DOUBLE NOT NULL DEFAULT 1,
                `def` DOUBLE NOT NULL DEFAULT 0,
                crit_rate DOUBLE NOT NULL DEFAULT 5,
                crit_dmg DOUBLE NOT NULL DEFAULT 150,
                cdr DOUBLE NOT NULL DEFAULT 0,
                move_speed DOUBLE NOT NULL DEFAULT 100,
                hp_regen DOUBLE NOT NULL DEFAULT 0,
                mp_regen DOUBLE NOT NULL DEFAULT 0,
                current_class_id VARCHAR(64) NULL,
                unlocked_classes TEXT NULL,
                virtual_equipment_base64 LONGTEXT NULL,
                max_health DOUBLE NULL,
                current_health DOUBLE NULL,
                max_mana DOUBLE NULL,
                current_mana DOUBLE NULL,
                attack_power DOUBLE NULL,
                defense DOUBLE NULL,
                defense1 DOUBLE NULL,
                defense2 DOUBLE NULL,
                critical_rate DOUBLE NULL,
                critical_damage DOUBLE NULL DEFAULT 50.0,
                accuracy DOUBLE NULL,
                evasion DOUBLE NULL,
                dodge_cooldown_reduction DOUBLE NULL,
                cooldown_reduction DOUBLE NULL,
                walk_speed DOUBLE NULL,
                health_regen_flat DOUBLE NULL,
                health_regen_percent DOUBLE NULL,
                mana_regen_flat DOUBLE NULL,
                mana_regen_percent DOUBLE NULL,
                cooldown_end_at TEXT NULL,
                last_instance VARCHAR(128) NULL,
                last_login TIMESTAMP NULL,
                last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.formatted(PLAYERS_TABLE);

    /**
     * 既存テーブルにカラムが存在するか確認するクエリ。
     * <p>
     * {@link java.sql.DatabaseMetaData} はスキーマ名の大文字小文字で挙動が変わる
     * 環境があるため、MySQL / MariaDB の {@code information_schema} を直接参照する。
     */
    private static final String COLUMN_EXISTS_SQL = """
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """;

    private final DatabaseService databaseService;
    private final ExecutorService executor;
    private final Object schemaLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** スキーマ初期化の共有 future（失敗時は次回呼び出しで再試行できるよう破棄する）。 */
    private volatile CompletableFuture<Void> schemaFuture;

    public DatabaseManager(DatabaseService databaseService) {
        this(databaseService, 2);
    }

    public DatabaseManager(DatabaseService databaseService, int threads) {
        this.databaseService = Objects.requireNonNull(databaseService, "databaseService");

        int poolSize = Math.max(1, threads);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Zeloria-Database");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(poolSize, factory);
    }

    /**
     * {@code players} テーブルを必要に応じて作成する。
     * <p>
     * 何度呼び出しても実際の DDL は 1 度だけ実行される（失敗時は再試行可能）。
     *
     * @return スキーマ初期化の完了を表す future
     */
    public CompletableFuture<Void> initialize() {
        CompletableFuture<Void> current = schemaFuture;
        if (current != null) {
            return current;
        }

        synchronized (schemaLock) {
            if (schemaFuture == null) {
                schemaFuture = CompletableFuture
                        .runAsync(this::createPlayersTable, executor)
                        .whenComplete((ignored, throwable) -> {
                            if (throwable != null) {
                                synchronized (schemaLock) {
                                    schemaFuture = null;
                                }
                            }
                        });
            }
            return schemaFuture;
        }
    }

    /**
     * {@code players} テーブルを同期的に作成する（非同期基盤用）。
     */
    private void createPlayersTable() {
        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE_PLAYERS_TABLE_SQL);
            }

            // 既存インストール向けの後方互換マイグレーション。
            // CREATE TABLE IF NOT EXISTS では既存テーブルにカラムが追加されないため、
            // クラス関連カラムが無い場合だけ ALTER TABLE で追加する。
            ensureColumn(connection, "current_class_id", "VARCHAR(64) NULL");
            ensureColumn(connection, "unlocked_classes", "TEXT NULL");

            // RPG ステータス（PlayerStats）用カラム。
            // 既存行は NULL のままにし、読み込み時に旧カラム（hp など）から補完する。
            ensureColumn(connection, "max_health", "DOUBLE NULL");
            ensureColumn(connection, "current_health", "DOUBLE NULL");
            ensureColumn(connection, "max_mana", "DOUBLE NULL");
            ensureColumn(connection, "current_mana", "DOUBLE NULL");
            ensureColumn(connection, "attack_power", "DOUBLE NULL");
            ensureColumn(connection, "defense", "DOUBLE NULL");
            ensureColumn(connection, "defense1", "DOUBLE NULL");
            ensureColumn(connection, "defense2", "DOUBLE NULL");
            ensureColumn(connection, "critical_rate", "DOUBLE NULL");
            ensureColumn(connection, "critical_damage", "DOUBLE NULL DEFAULT 50.0");
            ensureColumn(connection, "accuracy", "DOUBLE NULL");
            ensureColumn(connection, "evasion", "DOUBLE NULL");
            ensureColumn(connection, "dodge_cooldown_reduction", "DOUBLE NULL");
            ensureColumn(connection, "cooldown_reduction", "DOUBLE NULL");
            ensureColumn(connection, "walk_speed", "DOUBLE NULL");
            ensureColumn(connection, "health_regen_flat", "DOUBLE NULL");
            ensureColumn(connection, "health_regen_percent", "DOUBLE NULL");
            ensureColumn(connection, "mana_regen_flat", "DOUBLE NULL");
            ensureColumn(connection, "mana_regen_percent", "DOUBLE NULL");
            ensureColumn(connection, "cooldown_end_at", "TEXT NULL");

            // defense カラムは旧・新どちらのインストールにも存在する。
            // 新セッションの防具防御力（defense2）が未設定の行だけ旧 defense から移行する。
            migrateDefenseToDefense2(connection);

            // 接続履歴（/admin info 用）。INSERT では更新せず、ログイン時に専用クエリで記録する。
            ensureColumn(connection, "last_instance", "VARCHAR(128) NULL");
            ensureColumn(connection, "last_login", "TIMESTAMP NULL");
        } catch (SQLException e) {
            throw new CompletionException(
                    "テーブル " + PLAYERS_TABLE + " の作成に失敗しました。", e);
        }
    }

    /**
     * 旧 {@code defense} カラムの値を新 {@code defense2} カラムへ移行する。
     * <p>
     * 既に {@code defense2} が設定されている行は上書きしない。何度実行しても
     * 結果が変わらないため、起動のたびに呼び出しても安全。
     *
     * @param connection 対象コネクション
     * @throws SQLException 移行に失敗した場合
     */
    private void migrateDefenseToDefense2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE " + PLAYERS_TABLE
                            + " SET defense2 = defense"
                            + " WHERE defense2 IS NULL AND defense IS NOT NULL");
        }
    }

    /**
     * 指定カラムが {@code players} テーブルに存在しない場合のみ追加する。
     *
     * @param connection 対象コネクション
     * @param column     カラム名
     * @param definition カラム定義（型・NULL 可否）
     * @throws SQLException 確認・追加に失敗した場合
     */
    private void ensureColumn(Connection connection, String column, String definition)
            throws SQLException {
        try (PreparedStatement check = connection.prepareStatement(COLUMN_EXISTS_SQL)) {
            check.setString(1, PLAYERS_TABLE);
            check.setString(2, column);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }

        try (Statement alter = connection.createStatement()) {
            try {
                alter.executeUpdate("ALTER TABLE " + PLAYERS_TABLE
                        + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException e) {
                // 同時実行などで既に追加されていた場合は成功として扱う（MySQL: 1060 / SQLState: 42S21）。
                if (e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState())) {
                    return;
                }
                throw e;
            }
        }
    }

    /**
     * 接続プールからコネクションを取得する。
     *
     * @return コネクション
     * @throws SQLException 未接続の場合は SQLException
     */
    public Connection getConnection() throws SQLException {
        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            throw new SQLException(
                    "データベースが未接続です。先に DatabaseService#connect() を実行してください。");
        }
        return dataSource.getConnection();
    }

    /**
     * 接続プールを取得する。
     *
     * @return HikariCP の DataSource（未接続の場合は {@code null}）
     */
    public DataSource getDataSource() {
        return databaseService.getDataSource();
    }

    /**
     * データベースへ接続済みか判定する。
     */
    public boolean isConnected() {
        DataSource dataSource = getDataSource();
        if (!(dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari)) {
            return dataSource != null;
        }
        return !hikari.isClosed();
    }

    /**
     * 非同期 DB 処理用の executor を取得する。
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * executor を停止する。プラグイン無効化時に呼び出す。多重呼び出しは安全。
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
