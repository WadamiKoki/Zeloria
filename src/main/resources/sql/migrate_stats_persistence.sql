-- ============================================================================
-- Zeloria 既存環境向けマイグレーション
-- 防御貫通率 / 吸血率の永続化
-- ----------------------------------------------------------------------------
-- 既存の players テーブルへ armor_penetration / lifesteal を追加します。
-- 実行方法:
--   mysql -u <user> -p <database> < migrate_stats_persistence.sql
--   または MySQL クライアントで USE <database>; してから source migrate_stats_persistence.sql;
--
-- カラム定義は sql/reset.sql / DatabaseManager / PlayerRepository と一致させています。
-- ============================================================================

ALTER TABLE players
    ADD COLUMN armor_penetration DOUBLE NULL AFTER mana_regen_percent,
    ADD COLUMN lifesteal DOUBLE NULL AFTER armor_penetration;