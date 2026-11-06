# Zeloria

Paper サーバー向けの RPG プラグインです。複数のサーバー（チャンネル）で運用する場合は、
設定 YAML・DB 接続情報・ワールドテンプレートを 1 箇所に集約し、各サーバーから共有します。

## チャンネル設定（`channel.yml`）

各サーバーの `plugins/Zeloria/channel.yml` で、そのサーバーのチャンネル番号と
共有設定フォルダ・ワールドデータフォルダの位置を指定します。`channel.yml` は自動生成されません。
起動前に必ず手動で配置してください（未配置の場合はプラグインが無効化されます）。

```yaml
# このサーバーのチャンネル番号（建築モードは1のみ有効）
channel: 1

# 共有設定フォルダのパス（サーバールートからの相対パス。絶対パスは使用禁止）
# 例: "../config" → <server>/../config = ZeloriaServer/config/
shared-config-path: "../config"

# テンプレートワールドの保存先（サーバールートからの相対パス。絶対パスは使用禁止）
# 例: "../WorldData" → <server>/../WorldData = ZeloriaServer/WorldData/
world-data-path: "../WorldData"
```

- `channel`: このサーバーのチャンネル番号。建築モードを許可するのは **チャンネル 1 のみ** です。
- `shared-config-path`: 共有設定フォルダのパス。**サーバールート（`paper.jar` がある場所）**
  からの相対パスで指定します。`paper.jar` が `ZeloriaServer/zeloria/` にある場合、
  `../config` は `ZeloriaServer/config/` を指します。
- `world-data-path`: テンプレートワールドの保存先。`shared-config-path` と同様に
  **サーバールートからの相対パス** で指定します。`../WorldData` は
  `ZeloriaServer/WorldData/` を指します。
- 絶対パス（`/Users/...`、`C:\...` など）は使用禁止です。指定した場合は次のログを
  出力して無効化されます。
  - `shared-config-path には絶対パスを使用しないでください: <パス>`
  - `world-data-path には絶対パスを使用しないでください`

### パスの解決

共有設定フォルダとワールドデータフォルダは、サーバールートを基準に次の式で解決されます。

```java
File serverRoot = getDataFolder().getParentFile().getParentFile().getCanonicalFile();
File sharedFolder = new File(serverRoot, channelConfig.getSharedConfigPath()).getCanonicalFile();
File worldDataFolder = new File(serverRoot, channelConfig.getWorldDataPath()).getCanonicalFile();
```

`getDataFolder()` は `<server>/plugins/Zeloria/` を返すため、2 つ上がサーバールートになります。
パス区切りは `/` で記述してください（`File.separator` には依存しません）。

指定した共有設定フォルダが存在しない場合、プラグインは次のログを出力して無効化されます。

```
共有設定フォルダが見つかりません: <解決後のパス>
```

`channel.yml` 自体が無い場合は次のログを出力して無効化されます。

```
channel.yml が見つかりません。plugins/Zeloria/channel.yml を作成してください。
```

## 共有フォルダの配置場所

共有設定フォルダと `WorldData/` は **サーバールートの親フォルダ**（複数サーバーを束ねるフォルダ）に置きます。

```
ZeloriaServer/
├── velocity/
├── zeloria/                ← チャンネル 1（建築可能、paper.jar があるサーバールート）
│   └── plugins/Zeloria/
│       └── channel.yml     （channel: 1, shared-config-path: "../config", world-data-path: "../WorldData"）
├── zeloria2/               ← チャンネル 2（将来）
│   └── plugins/Zeloria/
│       └── channel.yml     （channel: 2, shared-config-path: "../config", world-data-path: "../WorldData"）
├── config/                 ← 全サーバー共有（YAML 設定と sql.yml）
│   ├── ClassData/
│   ├── SkillData/
│   ├── ...
│   └── sql.yml             ← DB 接続情報（共有設定フォルダ直下）
└── WorldData/              ← 全サーバー共有（ワールドテンプレート）
    ├── hub/
    ├── field_1/
    └── ...
```

- `config/` 配下の YAML と `config/sql.yml` は **全サーバーで共有** します。設定変更は 1 箇所の編集で
  全サーバーに反映されます（反映には各サーバーで `/admin reload` を実行してください）。
- `WorldData/` 配下のテンプレートは **全サーバーで共有** します。テンプレートの追加・更新は
  `WorldData/` の 1 箇所で行えば、全サーバーから同じテンプレートを利用できます。
  各サーバーの `plugins/Zeloria/worlds/` は使用しません。
- DB 接続情報も共有設定フォルダ直下の `sql.yml`（`<server>/config/sql.yml`、例:
  `ZeloriaServer/config/sql.yml`）1 箇所で管理します。ここでの `<server>` は
  `ZeloriaServer` のような共有ルートを指します。
- 全サーバーが同じ DB を参照するため、プレイヤーデータは自動的に一元管理されます。
  サーバー A からサーバー B へ移動しても、同じプレイヤーデータが引き継がれます。

### 既存環境からの移行（ワールドテンプレート）

旧バージョンで `plugins/Zeloria/worlds/` に置いていたテンプレートは、
全サーバー共有の `WorldData/` へ移動してください。

1. 全サーバーを停止します。
2. チャンネル 1 の `plugins/Zeloria/worlds/` の中身（`hub/` などのテンプレート
   ディレクトリ）を `ZeloriaServer/WorldData/` へ移動します。
3. 各サーバーの `plugins/Zeloria/channel.yml` に
   `world-data-path: "../WorldData"` を追加します（`shared-config-path` と同じ相対パス基準）。
4. 全サーバーを起動し、`/admin build info` などでテンプレートが認識されることを確認します。

```bash
# 例: paper.jar が ZeloriaServer/zeloria/ にある場合
mkdir -p ZeloriaServer/WorldData
mv ZeloriaServer/zeloria/plugins/Zeloria/worlds/* ZeloriaServer/WorldData/
```

- `.tmp` / `.bak` など保存処理中の一時ディレクトリは移動しないでください。
- `WorldData/` は全サーバーで共有されるため、複数サーバーに同じテンプレートを
  個別に置く必要はありません。テンプレートの更新も 1 箇所で完結します。

### 共有 YAML の読み込みについて

- `config/` 配下の YAML は `YamlLoader` が起動時に読み込みます。
  `/admin reload`、`SpawnerManager.reload()`、`NpcManager.reload()` など、
  再読み込み処理もすべて共有設定フォルダから読み込みます。
- `sql.yml` は `DatabaseService` が起動時に共有設定フォルダ直下（`new File(sharedConfigFolder, "sql.yml")`）
  から読み込みます。
- プラグインは共有設定フォルダへの **書き込みを行いません**（ファイル作成・`saveResource` なし）。
  必要な YAML は `src/main/resources/config/` のテンプレートを手動でコピーしてください。
- Mac / Ubuntu のどちらでも、`ZeloriaServer/config/` という同じフォルダ構成であれば
  同じ `channel.yml` のまま動作します（OS 固有のホームディレクトリは参照しません）。

### コネクションプールの注意

全サーバーが同じ DB に接続するため、`sql.yml` の `maximum-pool-size`（既定値 10）と
サーバー数の積が MySQL の `max_connections`（既定値 151）を超えないようにしてください。
例: 5 サーバー × プール 10 = 最大 50 接続。

## 建築モード

建築モードは **チャンネル 1 でのみ実行可能** です。
`channel: 1` 以外のサーバーで `/admin build ...` を実行すると、次のメッセージを表示して
キャンセルされます。

```
建築モードはチャンネル1でのみ使用できます
```

### 使い方

```text
/admin build <templateId>   # テンプレートを複製して建築ワールドを開く／既存ワールドへ参戦
/admin build info           # 建築モードの状態（テンプレート・ワールド・プレイヤー）を表示
/admin build save [force]   # 保存して Hub へ戻る（force で他プレイヤーごと退出）
/admin build unsave [force] # 保存せずに破棄して Hub へ戻る（force で他プレイヤーごと退出）
```

1. `WorldData/<templateId>`（既定: `ZeloriaServer/WorldData/<templateId>`）に
   建築用のテンプレートワールドを配置します。
2. 建築権限（`zeloria.admin` と `zeloria.admin.build`）を持つプレイヤーが
   `/admin build <templateId>` を実行します。テンプレートが
   `<メインワールド>/dimensions/zeloria_build/<templateId>`（例:
   `world/dimensions/zeloria_build/<templateId>`）へ複製され、建築ワールドがロードされます。
   実行者はクリエイティブモードで建築ワールドへ転送されます。
3. 建築を終えたら `/admin build save` を実行します。建築ワールドをセーブしてアンロードし、
   内容をテンプレートへ上書き保存したうえで、複製先のディレクトリを削除します。
   建築ワールドにいた全プレイヤーは Hub のスポーンへ戻り、入場前のゲームモード
   （記録が無い場合はサバイバル）へ復元されます。

- 建築ワールドは常に最大 1 つです。同じテンプレートで `/admin build <templateId>` を
  再実行すると、実行者は既存の建築ワールドへ参戦します。
- 別のテンプレートが開かれている場合は自動保存せず、先に `/admin build save` を
  実行するよう促してキャンセルします。
- 建築ワールドに他のプレイヤーがいる場合、`save` / `unsave` はキャンセルされます。
  `save force` / `unsave force` を指定すると、他プレイヤーも Hub へ退避させて実行します。
- 建築ワールドにプレイヤーがいなくなってから 1 分経過すると、自動的に保存して
  アンロードします（`建築用ワールド <templateId> を自動保存してアンロードしました`）。
- `/admin build unsave` はテンプレートへ保存せず、建築ワールドのディレクトリを削除します。
- 通常のレイヤーインスタンスは、プレイヤーがいなくなってから 1 分経過すると
  自動的にアンロード・削除されます。建築ワールド（`zeloria_build` 名前空間）は
  この通常削除の対象外です。
- プラグイン停止時（`onDisable`）も、建築中のワールドは自動的に保存されます。
- 保存処理中は対象テンプレートがロックされ、新規レイヤーの生成が拒否されます。
  また、保存待ち・アンロード待ちの間は次の建築コマンドを受け付けません。
- 建築ワールドへ入場するとクリエイティブモードになります。`save`・テレポート・停止時は
  入場前のゲームモード（記録が無い場合はサバイバル）へ戻り、`unsave` 時はサバイバルへ戻ります。
- `session.lock` と `uid.dat` は複製対象外です。
- 建築ワールドは管理者専用です。`zeloria.admin.build` を持たないプレイヤーは
  テレポート・ポータル経由で入場できません。

## インストール

1. `channel.yml` を作成し、`plugins/Zeloria/channel.yml` に配置します
   （`shared-config-path: "../config"` と `world-data-path: "../WorldData"` を指定）。
2. サーバールートの親フォルダ（`<server>/../config/`、例: `ZeloriaServer/config/`）へ
   `src/main/resources/config/` の内容をコピーします。
3. `src/main/resources/sql.yml` を共有設定フォルダ直下（`<server>/config/sql.yml`、例:
   `ZeloriaServer/config/sql.yml`。ここでの `<server>` は共有ルート）へコピーして
   DB 情報を記入します。
4. サーバールートの親フォルダに `WorldData/` を作成し、`WorldData/<templateId>/` へ
   ワールドテンプレートを配置します（`WorldData/` は全サーバーで共有）。
   旧バージョンから移行する場合は「既存環境からの移行（ワールドテンプレート）」を参照してください。
5. 各サーバーを起動します。必須 YAML が不足している場合は次のログを出力して無効化されます。

```
必須YAMLが見つかりません: <不足しているディレクトリ>
```
