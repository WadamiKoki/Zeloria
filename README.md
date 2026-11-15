# Zeloria

Paper 26.3 / Java 25 向けのRPGプラグイン。

## ビルド
./gradlew shadowJar

## 配置
以下は自動生成されない。手動で配置すること。

- plugins/Zeloria/channel.yml      … チャンネル設定
- <server>/../config/              … 設定YAML一式（plugins/Zeloria/config/ は使わない）
- <server>/../sql.yml              … DB接続情報
- <server>/../WorldData/           … ワールドテンプレート

## DB
- 初回セットアップ: src/main/resources/sql/reset.sql を流す（データ全消去）
- 既存DBへのカラム追加: src/main/resources/sql/migrate_*.sql を流す

## スキーマ変更時
reset.sql を同時に更新する。