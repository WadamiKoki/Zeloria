package com.zeloria.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zeloria.core.Zeloria;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * HikariCP を用いたデータベース接続管理クラス。
 * <p>
 * {@code channel.yml} の {@code shared-config-path} が指す共有設定フォルダ直下の
 * {@code sql.yml} を読み込み、MySQL へのコネクションプールを管理する。
 * 全サーバー（チャンネル）が同じ {@code sql.yml} を参照するため、DB 接続情報は
 * 1 箇所で管理できる。本クラスは {@code sql.yml} の作成・書き込みを行わない。
 */
public class DatabaseService {

    private static final String JDBC_URL_FORMAT =
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private final Zeloria plugin;
    private HikariDataSource dataSource;

    public DatabaseService(Zeloria plugin) {
        this.plugin = plugin;
    }

    /**
     * sql.yml を読み込み、HikariDataSource を初期化する。
     */
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            plugin.getLogger().warning("データベースは既に接続されています。");
            return;
        }

        File sharedFolder = plugin.getSharedConfigFolder();
        if (sharedFolder == null) {
            plugin.getLogger().severe(
                    "共有設定フォルダが見つかりません。channel.yml の shared-config-path を確認してください。");
            return;
        }

        File configFile = new File(sharedFolder, "sql.yml");
        if (!configFile.exists()) {
            // sql.yml は自動生成しない。初回起動前に共有設定フォルダへ手動で配置する運用とする。
            plugin.getLogger().severe("sql.yml が見つかりません: " + configFile.getAbsolutePath()
                    + "（共有設定フォルダへ手動で配置してください）");
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        String host = config.getString("host", "localhost");
        int port = config.getInt("port", 3306);
        String database = config.getString("database", "zeloria");
        String username = config.getString("username", "root");
        String password = config.getString("password", "");
        int maximumPoolSize = config.getInt("maximum-pool-size", 10);
        int minimumIdle = config.getInt("minimum-idle", 2);
        long connectionTimeout = config.getLong("connection-timeout", 30000L);

        String jdbcUrl = String.format(JDBC_URL_FORMAT, host, port, database);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maximumPoolSize);
        hikariConfig.setMinimumIdle(minimumIdle);
        hikariConfig.setConnectionTimeout(connectionTimeout);
        hikariConfig.setPoolName("Zeloria-Pool");

        try {
            dataSource = new HikariDataSource(hikariConfig);
            plugin.getLogger().info("データベース接続を確立しました: " + jdbcUrl);
        } catch (Exception e) {
            plugin.getLogger().severe("データベース接続の初期化に失敗しました: " + e.getMessage());
            dataSource = null;
        }
    }

    /**
     * プラグイン停止時にコネクションプールを破棄する。
     */
    public void disconnect() {
        if (dataSource == null) {
            return;
        }
        try {
            if (!dataSource.isClosed()) {
                dataSource.close();
                plugin.getLogger().info("データベース接続を切断しました。");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("データベース切断時にエラーが発生しました: " + e.getMessage());
        } finally {
            dataSource = null;
        }
    }

    /**
     * HikariDataSource を取得する。
     *
     * @return 接続済みの HikariDataSource（未接続の場合は null）
     */
    public HikariDataSource getDataSource() {
        return dataSource;
    }
}
