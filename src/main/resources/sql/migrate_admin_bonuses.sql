-- 管理者補正（/admin stats など）を player_settings_json から
-- 専用カラム admin_bonuses_json へ移行する。
--
-- 既存の adminBonuses をそのまま新カラムへコピーするため、
-- 適用後もプレイヤーの補正値は失われない。
-- カラムが既に存在する場合（新規作成環境・再実行時）は ALTER をスキップするため、
-- 何度実行しても安全。
--
-- 適用方法:
--   mysql -u <user> -p <database> < migrate_admin_bonuses.sql

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'players'
      AND COLUMN_NAME = 'admin_bonuses_json'
);

SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE players ADD COLUMN admin_bonuses_json LONGTEXT NULL AFTER player_settings_json',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 旧形式 {"chatLogSelf":...,"chatLogOthers":...,"adminBonuses":{...}} から
-- adminBonuses オブジェクトだけを抽出して新カラムへ移す。
-- adminBonuses の中身は数値のみ（入れ子のオブジェクトなし）なので、
-- 最初の '{' から最初の '}' までを切り出す。
UPDATE players
SET admin_bonuses_json = SUBSTRING(
        player_settings_json,
        LOCATE('{', player_settings_json,
               LOCATE('"adminBonuses"', player_settings_json)),
        LOCATE('}', player_settings_json,
               LOCATE('"adminBonuses"', player_settings_json))
            - LOCATE('{', player_settings_json,
                     LOCATE('"adminBonuses"', player_settings_json)) + 1
    )
WHERE player_settings_json LIKE '%"adminBonuses"%'
  AND admin_bonuses_json IS NULL;

-- 旧カラムに残った adminBonuses を取り除き、設定だけの JSON に戻す。
UPDATE players
SET player_settings_json = CONCAT(
        '{"chatLogSelf":',
        IF(player_settings_json LIKE '%"chatLogSelf":false%', 'false', 'true'),
        ',"chatLogOthers":',
        IF(player_settings_json LIKE '%"chatLogOthers":false%', 'false', 'true'),
        '}'
    )
WHERE player_settings_json LIKE '%"adminBonuses"%';
