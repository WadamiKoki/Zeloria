package com.zeloria.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Objects;

/**
 * サーバー固有のチャンネル設定（{@code plugins/Zeloria/channel.yml}）を保持するクラス。
 * <p>
 * Zeloria を複数サーバー（チャンネル）で運用する場合、設定 YAML と DB 接続情報は
 * 共有フォルダに 1 箇所だけ置き、各サーバーはこのファイルで自身のチャンネル番号と
 * 共有フォルダの位置を指定する。
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

    /** 建築モードを許可するチャンネル番号。 */
    public static final int BUILD_CHANNEL = 1;

    /** {@code channel} が未指定の場合に使用するチャンネル番号。 */
    public static final int DEFAULT_CHANNEL = 1;

    /** {@code shared-config-path} が未指定の場合に使用する共有設定フォルダ。 */
    public static final String DEFAULT_SHARED_CONFIG_PATH = "../../../config";

    /** 共有設定フォルダ内の設定ディレクトリ名。 */
    public static final String SHARED_CONFIG_DIR_NAME = "config";

    private final int channel;
    private final String sharedConfigPath;

    /**
     * チャンネル設定を生成する。
     *
     * @param channel          チャンネル番号
     * @param sharedConfigPath 共有設定フォルダのパス（プラグインデータフォルダからの相対パス）
     */
    public ChannelConfig(int channel, String sharedConfigPath) {
        this.channel = channel;
        this.sharedConfigPath = sharedConfigPath == null || sharedConfigPath.isBlank()
                ? DEFAULT_SHARED_CONFIG_PATH
                : sharedConfigPath.trim();
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
        return new ChannelConfig(channel, sharedConfigPath);
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
     * 共有設定フォルダのパス（プラグインデータフォルダからの相対パス）を取得する。
     *
     * @return 共有設定フォルダのパス
     */
    public String getSharedConfigPath() {
        return sharedConfigPath;
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
