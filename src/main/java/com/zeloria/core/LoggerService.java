package com.zeloria.core;

import java.util.Objects;

/**
 * プラグインのログ出力サービス。
 * <p>
 * サーバーのコンソールログ（{@code plugin.getLogger()}）へ統合して出力する。
 * プラグイン独自のログファイル（{@code plugins/Zeloria/logs/}）は生成しない。
 * <p>
 * 出力形式は {@code [カテゴリ] メッセージ} とし、レベルに応じて
 * {@code info} / {@code warning} / {@code severe} を使い分ける。
 */
public class LoggerService {

    /** ログの出力元カテゴリ。 */
    public enum Category {
        SYSTEM,
        LAYER,
        PORTAL,
        PLAYER,
        DATA,
        COMMAND
    }

    /** ログレベル。 */
    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    private final Zeloria plugin;

    public LoggerService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * INFO レベルのログをコンソールへ出力する。
     *
     * @param category カテゴリ
     * @param message  メッセージ
     */
    public void log(Category category, String message) {
        log(Level.INFO, category, message);
    }

    /**
     * 指定レベルのログをコンソールへ出力する。
     *
     * @param level    レベル
     * @param category カテゴリ
     * @param message  メッセージ
     */
    public void log(Level level, Category category, String message) {
        Level resolvedLevel = level == null ? Level.INFO : level;
        Category resolvedCategory = category == null ? Category.SYSTEM : category;
        String line = "[" + resolvedCategory.name() + "] " + sanitize(message);

        switch (resolvedLevel) {
            case WARN -> plugin.getLogger().warning(line);
            case ERROR -> plugin.getLogger().severe(line);
            default -> plugin.getLogger().info(line);
        }
    }

    /**
     * プレイヤー名を伴うイベントログをコンソールへ出力する。
     *
     * @param category カテゴリ
     * @param message  メッセージ
     */
    public void event(Category category, String message) {
        log(Level.INFO, category, message);
    }

    /**
     * サービスを停止する。
     * <p>
     * コンソール出力のみのため解放するリソースはない。互換性のためメソッドは残す。
     */
    public void shutdown() {
        // ファイル書き込み・専用スレッドを持たないため、何もしない。
    }

    /**
     * 1 イベントが 1 行になるよう、改行や制御文字を空白へ置換する。
     */
    private static String sanitize(String message) {
        return message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
    }
}
