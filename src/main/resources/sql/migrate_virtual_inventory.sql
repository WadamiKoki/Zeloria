-- ============================================================================
-- 仮想インベントリ（通常・装備・素材タブ）用カラムの追加
-- ----------------------------------------------------------------------------
-- 既存の players テーブルを残したままマイグレーションする場合に実行します。
-- sql/reset.sql は開発用にテーブルを再作成するため、本番データがある環境では
-- こちらを使用してください。
-- ============================================================================

ALTER TABLE players
    ADD COLUMN virtual_inventory_json LONGTEXT NULL AFTER virtual_equipment_base64,
    ADD COLUMN virtual_equipment_json LONGTEXT NULL AFTER virtual_inventory_json,
    ADD COLUMN virtual_material_json LONGTEXT NULL AFTER virtual_equipment_json;
