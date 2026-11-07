# Zeloria 設定テンプレート

このディレクトリ（`src/main/resources/config/`）の YAML は **テンプレート** です。
Zeloria は起動時に YAML を自動生成しません（`saveResource(...)` は使用しません）。

## 初回起動前に手動でコピーしてください

共有設定フォルダは **サーバールート（`paper.jar` がある場所）の親フォルダ** に置きます。
`paper.jar` が `ZeloriaServer/zeloria/` にある場合、共有設定フォルダは
`ZeloriaServer/config/`（サーバールートから見て `<server>/../config/`）です。

このディレクトリの内容を、サーバールートの親フォルダ（`<server>/../config/`）へ
そのままコピーしてください。
1 つでも必須 YAML が不足している場合、プラグインは次のログを出力して無効化されます。

```
必須YAMLが見つかりません: <不足しているディレクトリ>
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
# paper.jar が ZeloriaServer/zeloria/ にある場合の共有設定フォルダ
mkdir -p ZeloriaServer/config

# リポジトリのテンプレートをコピー（ビルド後の jar から取り出す場合は unzip を使用）
cp -R src/main/resources/config/. ZeloriaServer/config/

# DB 接続情報は sql.yml（既定では共有フォルダの親、ZeloriaServer/sql.yml）へコピー
cp src/main/resources/sql.yml ZeloriaServer/sql.yml
```

## ワールドテンプレートの配置（`WorldData/`）

ワールドテンプレートは設定 YAML とは別に、**サーバールートの親フォルダ直下** の
`WorldData/`（例: `ZeloriaServer/WorldData/`）へ配置します。
`WorldData/` は **全サーバーで共有** され、テンプレートは 1 箇所で管理します。

```bash
# paper.jar が ZeloriaServer/zeloria/ にある場合
mkdir -p ZeloriaServer/WorldData

# テンプレートを配置（例: hub）
cp -R /path/to/hub ZeloriaServer/WorldData/hub
```

- 各サーバーの `plugins/Zeloria/worlds/` は使用しません。
- テンプレートの保存先は `channel.yml` の `world-data-path` で指定します
  （既定: `"../WorldData"`）。
- パス区切りには `/` を使用してください（`File.separator` に依存しません）。
- 絶対パス（`/Users/...`、`C:\...`、`C:/...` など）は使用禁止です。

## channel.yml のパス設定

`shared-config-path` と `world-data-path`、`sql-config-path` は
**サーバールート（`paper.jar` がある場所）からの相対パス** で指定してください。
`paper.jar` が `ZeloriaServer/zeloria/` にある場合、`../config` は
`ZeloriaServer/config/`、`../WorldData` は `ZeloriaServer/WorldData/`、
`../sql.yml` は `ZeloriaServer/sql.yml` を指します。

```yaml
shared-config-path: "../config"
world-data-path: "../WorldData"
sql-config-path: "../sql.yml"
```

- パス区切りには `/` を使用してください（`File.separator` に依存しません）。
- 絶対パス（`/Users/...`、`C:\...`、`C:/...` など）は使用禁止です。
  使用した場合は次のログを出力してプラグインを無効化します。

```
shared-config-path には絶対パスを使用しないでください: <指定されたパス>
world-data-path には絶対パスを使用しないでください
sql-config-path には絶対パスを使用しないでください: <指定されたパス>
```

共有設定フォルダ・ワールドデータフォルダ・SQL 設定ファイルは次の式で解決されます。

```java
File serverRoot = Bukkit.getWorldContainer().getCanonicalFile();
File sharedFolder = new File(serverRoot, sharedConfigPath).getCanonicalFile();
File worldDataFolder = new File(serverRoot, worldDataPath).getCanonicalFile();
File sqlConfigFile = new File(serverRoot, sqlConfigPath).getCanonicalFile();
```

## データベース設定

`sql.yml` も自動生成されません。`src/main/resources/sql.yml` を
`channel.yml` の `sql-config-path` が指す場所へコピーして、DB 情報を記入してください。
既定の配置先は `<server>/../sql.yml`（`<server>` は `paper.jar` があるサーバールート。
実例: `ZeloriaServer/sql.yml`）です。別の場所に置く場合は `sql-config-path` を変更してください。

`players` テーブルのカラムが不足している場合は、プラグインは自動 `ALTER TABLE` を行わず、
次のログを出力して無効化されます。開発用のリセット SQL を実行してテーブルを作り直してください。

```
players テーブルに必要なカラムがありません。sql/reset.sql を実行してください。
```

- `src/main/resources/sql/reset.sql` … 開発用のリセット SQL（本番では実行しないこと）

## 既存環境からの移行（ワールドテンプレート）

旧バージョンで `plugins/Zeloria/worlds/` に置いていたテンプレートは、
全サーバー共有の `WorldData/` へ移動してください。

1. 全サーバーを停止します。
2. チャンネル 1 の `plugins/Zeloria/worlds/` の中身（`hub/` などのテンプレート
   ディレクトリ）を `ZeloriaServer/WorldData/` へ移動します。
3. 各サーバーの `plugins/Zeloria/channel.yml` に
   `world-data-path: "../WorldData"` を追加します。
4. 全サーバーを起動し、`/admin build info` などでテンプレートが認識されることを確認します。

```bash
# paper.jar が ZeloriaServer/zeloria/ にある場合
mkdir -p ZeloriaServer/WorldData
mv ZeloriaServer/zeloria/plugins/Zeloria/worlds/* ZeloriaServer/WorldData/
```

- `.tmp` / `.bak` など保存処理中の一時ディレクトリは移動しないでください。
- `WorldData/` は全サーバーで共有されるため、複数サーバーに同じテンプレートを
  個別に置く必要はありません。

## 環境非依存について

Mac / Ubuntu / Windows のいずれでも、次の構成であれば同じ設定のまま動作します。

```
ZeloriaServer/
├── zeloria/              ← サーバールート（paper.jar がある場所）
│   └── plugins/Zeloria/
│       └── channel.yml   （shared-config-path: "../config", world-data-path: "../WorldData"）
├── config/               ← 共有設定フォルダ（YAML 一式と sql.yml、全サーバー共有）
└── WorldData/            ← ワールドテンプレート（全サーバー共有）
    ├── hub/
    ├── field_1/
    └── ...
```

- パスはすべて `getDataFolder()` を基準に解決されるため、OS 固有のホームディレクトリ
  （`user.home` や `HOME`）には依存しません。
- `shared-config-path` と `world-data-path` に OS 固有の絶対パスを書かない限り、
  フォルダ構成を同じにすれば Mac / Ubuntu / Windows のどれでもそのまま動作します。

## ClassTree のファイル名

`ClassTree/` 配下のクラスツリーは **ファイル名がクラス ID** になります
（例: `ClassTree/knight.yml` → `knight`）。`ClassData/*.yml` と同じ `id` を書くと
「YAML の id が重複しています」の警告が出るため、`ClassTree/*.yml` には `id` を
定義しないでください（定義しても読み込み時は無視されます）。

