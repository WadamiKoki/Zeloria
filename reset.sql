-- ============================================================================
-- 開発用データリセット SQL
-- ----------------------------------------------------------------------------
-- このファイルは「開発環境でプレイヤーデータを初期化する」ためのものです。
-- 本番環境では実行しないでください（players テーブルのデータが全消去されます）。
-- 設計方針（シンプルさ優先）:
--   * 保存するのは「累積でしか得られない素材データ」のみ
--     （レベル・経験値・所持金・装備の中身＝バニラインベントリ）
--   * 戦闘ステータス（max_health / attack_power / defense / walk_speed）は
--     DB に保存しない。combat_level と装備から実行時に計算する（Hypixel 方式）
--   * 生活（ギャザリング）は農業 / 採掘 / 木こりの 3 種を JSON 内のキーで保持
--   * レベルと経験値はランキング・レベルアップ判定での参照頻度が高いため別カラム
--   * 仮想インベントリ・仮想装備は持たない（バニラのインベントリ・装備スロットを使う）
--   * 装備補正は item パッケージのマッピングから実行時に合算する
-- ============================================================================

DROP TABLE IF EXISTS players;

CREATE TABLE players (
    uuid CHAR(36) NOT NULL,
    player_name VARCHAR(16) NOT NULL,
    money BIGINT NOT NULL DEFAULT 0,

    -- 生活（ギャザリング）: 3 種を JSON 内のキーで格納
    --   gathering_levels_json: {"farming": 1, "mining": 1, "woodcutting": 1}
    --   gathering_exps_json:   {"farming": 0, "mining": 0, "woodcutting": 0}
    gathering_levels_json JSON NULL,
    gathering_exps_json JSON NULL,

    -- 戦闘（レベルと経験値のみ。ステータスは保存しない）
    combat_level INT NOT NULL DEFAULT 1,
    combat_exp BIGINT NOT NULL DEFAULT 0,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;