package com.zeloria.player;

import com.zeloria.core.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@code players} テーブルへのアクセスを担うリポジトリ。
 * <p>
 * すべての DB アクセスは {@link DatabaseManager} の専用 executor 上で実行され、
 * {@link CompletableFuture} として結果を返すため、呼び出し元（メインスレッド）を
 * ブロックしない。保存は {@link PlayerSnapshot} を受け取り、
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} で新規・更新を 1 クエリに統一する。
 */
public class PlayerRepository {

    private static final String TABLE_NAME = DatabaseManager.PLAYERS_TABLE;

    /** 取得・保存対象のカラム（{@code last_updated} は DB 側で自動更新する）。 */
    private static final String COLUMNS = """
            uuid, player_name, level, exp, money,
            current_class_id, unlocked_classes,
            class_levels_json, class_exps_json, class_tree_points_json, allocated_class_nodes_json,
            virtual_equipment_base64,
            max_health, current_health, max_mana, current_mana,
            attack_power, defense1, defense2, critical_rate, critical_damage,
            accuracy, evasion, dodge_cooldown_reduction, cooldown_reduction,
            walk_speed,
            health_regen_flat, health_regen_percent, mana_regen_flat, mana_regen_percent,
            cooldown_end_at
            """;

    private static final String SELECT_SQL =
            "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE uuid = ?";

    /** {@code /admin info} 用。接続履歴カラムも併せて取得する。 */
    private static final String SELECT_STATUS_SQL =
            "SELECT " + COLUMNS
                    + ", last_instance, last_login, last_updated FROM " + TABLE_NAME
                    + " WHERE uuid = ? OR player_name = ?"
                    + " ORDER BY last_login DESC, last_updated DESC LIMIT 1";

    /** ログイン時に接続履歴を記録する（データ本体の INSERT では更新しない）。 */
    private static final String UPDATE_LAST_LOGIN_SQL =
            "UPDATE " + TABLE_NAME
                    + " SET last_instance = ?, last_login = CURRENT_TIMESTAMP WHERE uuid = ?";

    private static final String INSERT_SQL = """
            INSERT INTO %s (
                uuid, player_name, level, exp, money,
                current_class_id, unlocked_classes,
                class_levels_json, class_exps_json, class_tree_points_json, allocated_class_nodes_json,
                virtual_equipment_base64,
                max_health, current_health, max_mana, current_mana,
                attack_power, defense1, defense2, critical_rate, critical_damage,
                accuracy, evasion, dodge_cooldown_reduction, cooldown_reduction,
                walk_speed,
                health_regen_flat, health_regen_percent, mana_regen_flat, mana_regen_percent,
                cooldown_end_at,
                last_updated
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                level = VALUES(level),
                exp = VALUES(exp),
                money = VALUES(money),
                current_class_id = VALUES(current_class_id),
                unlocked_classes = VALUES(unlocked_classes),
                class_levels_json = VALUES(class_levels_json),
                class_exps_json = VALUES(class_exps_json),
                class_tree_points_json = VALUES(class_tree_points_json),
                allocated_class_nodes_json = VALUES(allocated_class_nodes_json),
                virtual_equipment_base64 = VALUES(virtual_equipment_base64),
                max_health = VALUES(max_health),
                current_health = VALUES(current_health),
                max_mana = VALUES(max_mana),
                current_mana = VALUES(current_mana),
                attack_power = VALUES(attack_power),
                defense1 = VALUES(defense1),
                defense2 = VALUES(defense2),
                critical_rate = VALUES(critical_rate),
                critical_damage = VALUES(critical_damage),
                accuracy = VALUES(accuracy),
                evasion = VALUES(evasion),
                dodge_cooldown_reduction = VALUES(dodge_cooldown_reduction),
                cooldown_reduction = VALUES(cooldown_reduction),
                walk_speed = VALUES(walk_speed),
                health_regen_flat = VALUES(health_regen_flat),
                health_regen_percent = VALUES(health_regen_percent),
                mana_regen_flat = VALUES(mana_regen_flat),
                mana_regen_percent = VALUES(mana_regen_percent),
                cooldown_end_at = VALUES(cooldown_end_at),
                last_updated = CURRENT_TIMESTAMP
            """.formatted(TABLE_NAME);

    private final DatabaseManager databaseManager;

    public PlayerRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager");
    }

    /**
     * プレイヤーデータを読み込む。
     * <p>
     * レコードが存在しない場合は既定値のスナップショットを生成し、新規レコードとして
     * INSERT したうえで返す。テーブルが未作成の場合は {@link DatabaseManager#initialize()}
     * の完了を待ってからアクセスする。
     *
     * @param uuid プレイヤー UUID
     * @param name プレイヤー名（新規作成時の初期値）
     * @return 読み込んだスナップショット
     */
    public CompletableFuture<PlayerSnapshot> load(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        String playerName = name == null ? "" : name;

        return databaseManager.initialize()
                .thenApplyAsync(ignored -> {
                    try (Connection connection = databaseManager.getConnection()) {
                        PlayerSnapshot existing = findById(connection, uuid);
                        if (existing != null) {
                            return existing;
                        }

                        PlayerSnapshot created = PlayerSnapshot.defaults(uuid, playerName);
                        insert(connection, created);
                        return created;
                    } catch (SQLException e) {
                        throw new CompletionException(
                                "プレイヤーデータの読み込みに失敗しました: " + uuid, e);
                    }
                }, databaseManager.getExecutor());
    }

    /**
     * プレイヤー名または UUID から、接続履歴を含むプレイヤー情報を取得する。
     * <p>
     * オフラインプレイヤーも検索できる。複数行が一致した場合は最終ログインが
     * 新しい行を優先する。
     *
     * @param input プレイヤー名または UUID 文字列
     * @return 見つかった場合はプレイヤー情報
     */
    public CompletableFuture<Optional<PlayerStatus>> findStatus(String input) {
        String query = input == null ? "" : input.trim();
        if (query.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        UUID uuid = parseUuid(query);
        String uuidValue = uuid == null ? "" : uuid.toString();

        return databaseManager.initialize()
                .thenApplyAsync(ignored -> {
                    try (Connection connection = databaseManager.getConnection();
                         PreparedStatement statement =
                                 connection.prepareStatement(SELECT_STATUS_SQL)) {
                        statement.setString(1, uuidValue);
                        statement.setString(2, query);

                        try (ResultSet rs = statement.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                            PlayerStatus status = new PlayerStatus(
                                    mapRow(rs),
                                    rs.getString("last_instance"),
                                    rs.getTimestamp("last_login"),
                                    rs.getTimestamp("last_updated"));
                            return Optional.of(status);
                        }
                    } catch (SQLException e) {
                        throw new CompletionException(
                                "プレイヤー情報の取得に失敗しました: " + query, e);
                    }
                }, databaseManager.getExecutor());
    }

    /**
     * 最終ログイン日時と接続インスタンスを記録する。
     * <p>
     * データ本体の INSERT とは分離しているため、保存処理と競合しない。
     * レコードがまだ存在しない場合は何もしない（直後の保存で作成される）。
     *
     * @param uuid         プレイヤー UUID
     * @param instanceName 接続インスタンス名（ワールド名など）
     * @return 更新の完了を表す future
     */
    public CompletableFuture<Void> updateLastLogin(UUID uuid, String instanceName) {
        Objects.requireNonNull(uuid, "uuid");
        String instance = instanceName == null ? "" : instanceName;

        return databaseManager.initialize()
                .thenRunAsync(() -> {
                    try (Connection connection = databaseManager.getConnection();
                         PreparedStatement statement =
                                 connection.prepareStatement(UPDATE_LAST_LOGIN_SQL)) {
                        statement.setString(1, instance);
                        statement.setString(2, uuid.toString());
                        statement.executeUpdate();
                    } catch (SQLException e) {
                        throw new CompletionException(
                                "最終ログイン情報の更新に失敗しました: " + uuid, e);
                    }
                }, databaseManager.getExecutor());
    }

    /**
     * プレイヤーデータを保存する（新規は INSERT、既存は UPDATE）。
     *
     * @param snapshot 保存するスナップショット
     * @return 保存の完了を表す future
     */
    public CompletableFuture<Void> save(PlayerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        return databaseManager.initialize()
                .thenRunAsync(() -> {
                    try (Connection connection = databaseManager.getConnection()) {
                        insert(connection, snapshot);
                    } catch (SQLException e) {
                        throw new CompletionException(
                                "プレイヤーデータの保存に失敗しました: " + snapshot.uuid(), e);
                    }
                }, databaseManager.getExecutor());
    }

    /**
     * すべての書き込みが確定したことを確認する（停止処理用のバリア）。
     * <p>
     * HikariCP は自動コミットで運用しているため通常は commit 不要だが、
     * 未コミットトランザクションがあればコミットし、疎通確認クエリを実行する。
     * DB 未接続の場合は何もせず正常終了する。
     *
     * @return 完了を表す future
     */
    public CompletableFuture<Void> flush() {
        if (!databaseManager.isConnected()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
                statement.execute();
            } catch (SQLException e) {
                throw new CompletionException("データベースのフラッシュに失敗しました。", e);
            }
        }, databaseManager.getExecutor());
    }

    /**
     * {@code players} テーブルの存在を保証する（{@code /admin test db} 用）。
     *
     * @return テーブル作成の完了を表す future
     */
    public CompletableFuture<Void> createTableIfNotExists() {
        return databaseManager.initialize();
    }

    private PlayerSnapshot findById(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, uuid.toString());

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRow(rs);
            }
        }
    }

    private void insert(Connection connection, PlayerSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            // INSERT_SQL: 31 columns（last_updated を除く）, 31 placeholders,
            // max bind index 31。カラム順と以下のバインド順は完全に一致している。
            int index = 1;
            statement.setString(index++, snapshot.uuid().toString());
            statement.setString(index++, snapshot.playerName());
            statement.setInt(index++, snapshot.level());
            statement.setLong(index++, snapshot.exp());
            statement.setLong(index++, snapshot.money());
            statement.setString(index++, snapshot.currentClassId());
            statement.setString(index++, snapshot.unlockedClasses());
            statement.setString(index++, snapshot.classLevelsJson());
            statement.setString(index++, snapshot.classExpsJson());
            statement.setString(index++, snapshot.classTreePointsJson());
            statement.setString(index++, snapshot.allocatedClassNodesJson());
            statement.setString(index++, snapshot.virtualEquipmentBase64());
            statement.setDouble(index++, snapshot.maxHealth());
            statement.setDouble(index++, snapshot.currentHealth());
            statement.setDouble(index++, snapshot.maxMana());
            statement.setDouble(index++, snapshot.currentMana());
            statement.setDouble(index++, snapshot.attackPower());
            statement.setDouble(index++, snapshot.defense1());
            statement.setDouble(index++, snapshot.defense2());
            statement.setDouble(index++, snapshot.criticalRate());
            statement.setDouble(index++, snapshot.criticalDamage());
            statement.setDouble(index++, snapshot.accuracy());
            statement.setDouble(index++, snapshot.evasion());
            statement.setDouble(index++, snapshot.dodgeCooldownReduction());
            statement.setDouble(index++, snapshot.cooldownReduction());
            statement.setDouble(index++, snapshot.walkSpeed());
            statement.setDouble(index++, snapshot.healthRegenFlat());
            statement.setDouble(index++, snapshot.healthRegenPercent());
            statement.setDouble(index++, snapshot.manaRegenFlat());
            statement.setDouble(index++, snapshot.manaRegenPercent());
            statement.setString(index++, snapshot.cooldownEndAt());

            statement.executeUpdate();
        }
    }

    private PlayerSnapshot mapRow(ResultSet rs) throws SQLException {
        return new PlayerSnapshot(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getInt("level"),
                rs.getLong("exp"),
                rs.getLong("money"),
                rs.getString("current_class_id"),
                rs.getString("unlocked_classes"),
                rs.getString("class_levels_json"),
                rs.getString("class_exps_json"),
                rs.getString("class_tree_points_json"),
                rs.getString("allocated_class_nodes_json"),
                rs.getString("virtual_equipment_base64"),
                statOr(rs, "max_health", PlayerStats.DEFAULT_MAX_HEALTH),
                statOr(rs, "current_health", PlayerStats.DEFAULT_MAX_HEALTH),
                statOr(rs, "max_mana", PlayerStats.DEFAULT_MAX_MANA),
                statOr(rs, "current_mana", PlayerStats.DEFAULT_MAX_MANA),
                statOr(rs, "attack_power", PlayerStats.DEFAULT_ATTACK_POWER),
                statOr(rs, "defense1", PlayerStats.DEFAULT_DEFENSE1),
                statOr(rs, "defense2", PlayerStats.DEFAULT_DEFENSE2),
                statOr(rs, "critical_rate", PlayerStats.DEFAULT_CRITICAL_RATE),
                statOr(rs, "critical_damage", PlayerStats.DEFAULT_CRITICAL_DAMAGE),
                statOr(rs, "accuracy", PlayerStats.DEFAULT_ACCURACY),
                statOr(rs, "evasion", PlayerStats.DEFAULT_EVASION),
                statOr(rs, "dodge_cooldown_reduction", PlayerStats.DEFAULT_DODGE_COOLDOWN_REDUCTION),
                statOr(rs, "cooldown_reduction", PlayerStats.DEFAULT_COOLDOWN_REDUCTION),
                statOr(rs, "walk_speed", PlayerStats.DEFAULT_MOVE_SPEED),
                statOr(rs, "health_regen_flat", PlayerStats.DEFAULT_HEALTH_REGEN_FLAT),
                statOr(rs, "health_regen_percent", PlayerStats.DEFAULT_HEALTH_REGEN_PERCENT),
                statOr(rs, "mana_regen_flat", PlayerStats.DEFAULT_MANA_REGEN_FLAT),
                statOr(rs, "mana_regen_percent", PlayerStats.DEFAULT_MANA_REGEN_PERCENT),
                rs.getString("cooldown_end_at")
        );
    }

    /**
     * UUID 文字列を解析する。UUID 形式でない場合は {@code null} を返す。
     *
     * @param value 入力文字列
     * @return 解析結果。UUID 形式でない場合は {@code null}
     */
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * DOUBLE カラムを読み取り、NULL の場合は代替値を返す。
     *
     * @param rs       対象行
     * @param column   カラム名
     * @param fallback NULL 時の代替値
     * @return 読み取った値（NULL 時は {@code fallback}）
     * @throws SQLException 読み取りに失敗した場合
     */
    private static double statOr(ResultSet rs, String column, double fallback)
            throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? fallback : value;
    }
}
