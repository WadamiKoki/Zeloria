package com.zeloria.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Objects;

/**
 * サーバー固有のチャンネル設定（{@code plugins/Zeloria/channel.yml}）を保持するクラス。
 * <p>
 * Zeloria を複数サーバー（チャンネル）で運用する場合、設定 YAML と DB 接続情報は
 * 共有フォルダに、ワールドテンプレートは {@code WorldData/} に 1 箇所だけ置き、
 * 各サーバーはこのファイルで自身のチャンネル番号と各フォルダの位置を指定する。
 * <p>
 * 建築モードは {@link #BUILD_CHANNEL}（チャンネル 1）でのみ有効とする。
 * {@code channel.yml} は自動生成しない。初回起動前に手動で配置すること。
 */
public final class ChannelConfig {

    /** 設定ファイル名（プラグインデータフォルダ直下）。 */
    public static final String FILE_NAME = "channel.yml";

    /** チャンネル番号のキー。 */
    public static final String KEY_CHANNEL = "channel";

    /** 共有設定フォルダのパスのキー。 */
    public static final String KEY_SHARED_CONFIG_PATH = "shared-config-path";

    /** テンプレートワールドの保存先のキー。 */
    public static final String KEY_WORLD_DATA_PATH = "world-data-path";

    /** 建築モードを許可するチャンネル番号。 */
    public static final int BUILD_CHANNEL = 1;

    /** {@code channel} が未指定の場合に使用するチャンネル番号。 */
    public static final int DEFAULT_CHANNEL = 1;

    /**
     * {@code shared-config-path} が未指定の場合に使用する共有設定フォルダ。
     * <p>
     * サーバールート（{@code paper.jar} がある場所）からの相対パスで、
     * 既定の {@code "../config"} はサーバールートの親フォルダ直下の
     * {@code config}（例: {@code ZeloriaServer/config}）を指す。
     */
    public static final String DEFAULT_SHARED_CONFIG_PATH = "../config";

    /**
     * {@code world-data-path} が未指定の場合に使用するテンプレートワールドの保存先。
     * <p>
     * サーバールート（{@code paper.jar} がある場所）からの相対パスで、
     * 既定の {@code "../WorldData"} はサーバールートの親フォルダ直下の
     * {@code WorldData}（例: {@code ZeloriaServer/WorldData}）を指す。
     */
    public static final String DEFAULT_WORLD_DATA_PATH = "../WorldData";

    /** 共有設定フォルダ内の設定ディレクトリ名。 */
    public static final String SHARED_CONFIG_DIR_NAME = "config";

    private final int channel;
    private final String sharedConfigPath;
    private final String worldDataPath;

    /**
     * チャンネル設定を生成する（{@code world-data-path} は既定値を使用）。
     *
     * @param channel          チャンネル番号
     * @param sharedConfigPath 共有設定フォルダのパス（サーバールートからの相対パス）
     */
    public ChannelConfig(int channel, String sharedConfigPath) {
        this(channel, sharedConfigPath, DEFAULT_WORLD_DATA_PATH);
    }

    /**
     * チャンネル設定を生成する。
     *
     * @param channel          チャンネル番号
     * @param sharedConfigPath 共有設定フォルダのパス（サーバールートからの相対パス）
     * @param worldDataPath    テンプレートワールドの保存先（サーバールートからの相対パス）
     */
    public ChannelConfig(int channel, String sharedConfigPath, String worldDataPath) {
        this.channel = channel;
        this.sharedConfigPath = sharedConfigPath == null || sharedConfigPath.isBlank()
                ? DEFAULT_SHARED_CONFIG_PATH
                : sharedConfigPath.trim();
        this.worldDataPath = worldDataPath == null || worldDataPath.isBlank()
                ? DEFAULT_WORLD_DATA_PATH
                : worldDataPath.trim();
    }

    /**
     * {@code channel.yml} を読み込む。
     * <p>
     * キーが未指定または不正な場合は既定値を使用する。ファイルの存在確認は
     * 呼び出し元（{@link Zeloria#onEnable()}）が行う。
     *
     * @param file 読み込み対象の {@code channel.yml}
     * @return 読み込んだチャンネル設定
     */
    public static ChannelConfig load(File file) {
        Objects.requireNonNull(file, "file");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int channel = yaml.getInt(KEY_CHANNEL, DEFAULT_CHANNEL);
        String sharedConfigPath = yaml.getString(KEY_SHARED_CONFIG_PATH, DEFAULT_SHARED_CONFIG_PATH);
        String worldDataPath = yaml.getString(KEY_WORLD_DATA_PATH, DEFAULT_WORLD_DATA_PATH);
        return new ChannelConfig(channel, sharedConfigPath, worldDataPath);
    }

    /**
     * チャンネル番号を取得する。
     *
     * @return チャンネル番号
     */
    public int getChannel() {
        return channel;
    }

    /**
     * 共有設定フォルダのパス（サーバールートからの相対パス）を取得する。
     *
     * @return 共有設定フォルダのパス
     */
    public String getSharedConfigPath() {
        return sharedConfigPath;
    }

    /**
     * テンプレートワールドの保存先（サーバールートからの相対パス）を取得する。
     *
     * @return テンプレートワールドの保存先
     */
    public String getWorldDataPath() {
        return worldDataPath;
    }

    /**
     * 建築モードを許可するチャンネルか判定する。
     *
     * @return チャンネル 1 の場合は {@code true}
     */
    public boolean isBuildChannel() {
        return channel == BUILD_CHANNEL;
    }
}
