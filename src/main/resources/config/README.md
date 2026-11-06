# Zeloria 設定テンプレート

このディレクトリ（`src/main/resources/config/`）の YAML は **テンプレート** です。
Zeloria は起動時に YAML を自動生成しません（`saveResource(...)` は使用しません）。

## 初回起動前に手動でコピーしてください

サーバーの `plugins/Zeloria/config/` 配下へ、このディレクトリの内容をそのままコピーしてください。
1 つでも必須 YAML が不足している場合、プラグインは次のログを出力して無効化されます。

```
必須YAMLが見つかりません: config/<不足しているディレクトリ>
```

### 必須ディレクトリ

- `ClassData/`
- `SkillData/`
- `ArmorData/`
- `WeaponData/`
- `NpcData/`
- `SpawnerData/`
- `LayerData/`
- `LevelData/`
- `MessageData/`
- `MaterialData/`
- `MobData/`
- `gui/`

### コピー例

```bash
# プラグインデータフォルダを作成
mkdir -p plugins/Zeloria/config

# リポジトリのテンプレートをコピー（ビルド後の jar から取り出す場合は unzip を使用）
cp -R src/main/resources/config/. plugins/Zeloria/config/
```

## データベース設定

`sql.yml` も自動生成されません。`src/main/resources/sql.yml` を
`plugins/Zeloria/sql.yml` へ手動でコピーし、接続情報を設定してください。

`players` テーブルのカラムが不足している場合は、プラグインは自動 `ALTER TABLE` を行わず、
次のログを出力して無効化されます。開発用のリセット SQL を実行してテーブルを作り直してください。

```
players テーブルに必要なカラムがありません。sql/reset.sql を実行してください。
```

- `src/main/resources/sql/reset.sql` … 開発用のリセット SQL（本番では実行しないこと）
