-- ============================================================================
-- Zeloria 開発用データリセット SQL
-- ----------------------------------------------------------------------------
-- このファイルは「開発環境でプレイヤーデータを初期化する」ためのものです。
-- 本番環境では実行しないでください（players テーブルのデータが全消去されます）。
--
-- 実行方法:
--   mysql -u <user> -p <database> < reset.sql
--   または MySQL クライアントで USE <database>; してから source reset.sql;
--
-- カラム定義は PlayerRepository / DatabaseManager の CREATE TABLE と完全に一致
-- させています。カラムを変更する場合は以下の 3 箇所を同時に更新してください。
--   * src/main/resources/sql/reset.sql
--   * src/main/java/com/zeloria/core/DatabaseManager.java
--   * src/main/java/com/zeloria/player/PlayerRepository.java
-- ============================================================================

DROP TABLE IF EXISTS players;

CREATE TABLE players (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
