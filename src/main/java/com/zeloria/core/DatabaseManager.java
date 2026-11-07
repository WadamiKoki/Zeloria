package com.zeloria.core;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
     * {@code PlayerStats} の値をそのまま保持する。
     */
    private static final String CREATE_PLAYERS_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                uuid CHAR(36) NOT NULL,
                player_name VARCHAR(64) NOT NULL,
                level INT NOT NULL DEFAULT 0,
                exp BIGINT NOT NULL DEFAULT 0,
                money BIGINT NOT NULL DEFAULT 0,
                current_class_id VARCHAR(64) NULL,
                unlocked_classes TEXT NULL,
                class_levels_json LONGTEXT NULL,
                class_exps_json LONGTEXT NULL,
                class_tree_points_json LONGTEXT NULL,
                allocated_class_nodes_json LONGTEXT NULL,
                virtual_equipment_base64 LONGTEXT NULL,
                max_health DOUBLE NULL,
                current_health DOUBLE NULL,
                max_mana DOUBLE NULL,
                current_mana DOUBLE NULL,
                attack_power DOUBLE NULL,
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
     * {@code players} テーブルに必須のカラム一覧。
     * <p>
     * {@link com.zeloria.player.PlayerRepository} の SELECT / INSERT が参照する
     * カラムと一致させている。不足がある場合は自動 ALTER せず起動を中止する
     * （{@code sql/reset.sql} で再作成する運用）。
     */
    public static final List<String> REQUIRED_PLAYERS_COLUMNS = List.of(
            "uuid", "player_name", "level", "exp", "money",
            "current_class_id", "unlocked_classes",
            "class_levels_json", "class_exps_json", "class_tree_points_json", "allocated_class_nodes_json",
            "virtual_equipment_base64",
            "max_health", "current_health", "max_mana", "current_mana",
            "attack_power", "defense1", "defense2", "critical_rate", "critical_damage",
            "accuracy", "evasion", "dodge_cooldown_reduction", "cooldown_reduction",
            "walk_speed", "health_regen_flat", "health_regen_percent",
            "mana_regen_flat", "mana_regen_percent", "cooldown_end_at",
            "last_instance", "last_login", "last_updated"
    );

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
     * {@code players} テーブルを同期的に作成し、必須カラムを検証する（非同期基盤用）。
     * <p>
     * {@code CREATE TABLE IF NOT EXISTS} のみを実行し、既存テーブルへの
     * 自動 {@code ALTER TABLE} は行わない。必須カラムが不足している場合は
     * {@link MissingColumnsException} を投げ、呼び出し元がプラグインを無効化する。
     */
    private void createPlayersTable() {
        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE_PLAYERS_TABLE_SQL);
            }

            List<String> missingColumns = findMissingColumns(connection);
            if (!missingColumns.isEmpty()) {
                throw new MissingColumnsException(missingColumns);
            }
        } catch (SQLException e) {
            throw new CompletionException(
                    "テーブル " + PLAYERS_TABLE + " の作成に失敗しました。", e);
        }
    }

    /**
     * {@code SHOW COLUMNS FROM players} を実行し、必須カラムのうち不足しているものを返す。
     *
     * @param connection 対象コネクション
     * @return 不足しているカラム名（すべて揃っている場合は空リスト）
     * @throws SQLException カラム一覧の取得に失敗した場合
     */
    private List<String> findMissingColumns(Connection connection) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW COLUMNS FROM " + PLAYERS_TABLE)) {
            while (rs.next()) {
                String field = rs.getString("Field");
                if (field != null && !field.isBlank()) {
                    existing.add(field.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String column : REQUIRED_PLAYERS_COLUMNS) {
            if (!existing.contains(column)) {
                missing.add(column);
            }
        }
        return missing;
    }

    /**
     * 必須カラムが不足していることを表す例外。
     * <p>
     * 自動 {@code ALTER TABLE} を行わない方針のため、不足時はこの例外を投げて
     * プラグイン側で無効化する。
     */
    public static final class MissingColumnsException extends RuntimeException {

        private final List<String> missingColumns;

        public MissingColumnsException(List<String> missingColumns) {
            super("players テーブルの必須カラムが不足しています: "
                    + String.join(", ", missingColumns));
            this.missingColumns = List.copyOf(missingColumns);
        }

        /**
         * 不足しているカラム名を取得する。
         */
        public List<String> missingColumns() {
            return missingColumns;
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
