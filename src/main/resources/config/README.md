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

# DB 接続情報も共有設定フォルダ直下へコピー
cp src/main/resources/sql.yml ZeloriaServer/config/sql.yml
```

## channel.yml の shared-config-path

`shared-config-path` は **サーバールート（`paper.jar` がある場所）からの相対パス**
で指定してください。`paper.jar` が `ZeloriaServer/zeloria/` にある場合、
`../config` は `ZeloriaServer/config/` を指します。

```yaml
shared-config-path: "../config"
```

- パス区切りには `/` を使用してください（`File.separator` に依存しません）。
- 絶対パス（`/Users/...`、`C:\...`、`C:/...` など）は使用禁止です。
  使用した場合は次のログを出力してプラグインを無効化します。

```
shared-config-path には絶対パスを使用しないでください: <指定されたパス>
```

共有設定フォルダは次の式で解決されます。

```java
File serverRoot = getDataFolder().getParentFile().getParentFile().getCanonicalFile();
File sharedFolder = new File(serverRoot, sharedConfigPath).getCanonicalFile();
```

## データベース設定

`sql.yml` も自動生成されません。`src/main/resources/sql.yml` を
共有設定フォルダ直下の `sql.yml` へコピーして、DB 情報を記入してください。
配置先は `<server>/config/sql.yml`（ここでの `<server>` は `ZeloriaServer` のような共有ルート。
実例: `ZeloriaServer/config/sql.yml`）です。

`players` テーブルのカラムが不足している場合は、プラグインは自動 `ALTER TABLE` を行わず、
次のログを出力して無効化されます。開発用のリセット SQL を実行してテーブルを作り直してください。

```
players テーブルに必要なカラムがありません。sql/reset.sql を実行してください。
```

- `src/main/resources/sql/reset.sql` … 開発用のリセット SQL（本番では実行しないこと）

## 環境非依存について

Mac / Ubuntu / Windows のいずれでも、次の構成であれば同じ設定のまま動作します。

```
ZeloriaServer/
├── zeloria/              ← サーバールート（paper.jar がある場所）
│   └── plugins/Zeloria/
│       └── channel.yml   （shared-config-path: "../config"）
└── config/               ← 共有設定フォルダ（YAML 一式と sql.yml）
```

- パスはすべて `getDataFolder()` を基準に解決されるため、OS 固有のホームディレクトリ
  （`user.home` や `HOME`）には依存しません。
- `shared-config-path` に OS 固有の絶対パスを書かない限り、フォルダ構成を同じにすれば
  Mac / Ubuntu のどちらでもそのまま動作します。
