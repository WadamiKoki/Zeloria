package com.zeloria.core;

import com.zeloria.core.Zeloria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * プラグイン専用のファイルログサービス。
 * <p>
 * {@code plugins/Zeloria/logs/} 配下へ日付別ファイル（{@code YYYY-MM-DD.log}）を出力する。
 * レイヤー生成・アンロード、ポータル利用、プレイヤー入退場、データ保存などの
 * 主要イベントをサーバー運用・デバッグ用に記録する。
 * <p>
 * 書き込みは専用の単一スレッドで非同期に実行されるため、メインスレッドをブロックしない。
 * {@link #shutdown()} はプラグイン無効化時に呼び出し、キューに残ったログを
 * 可能な限り書き出してからスレッドを停止する。
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

    private static final String LOG_DIR_NAME = "logs";
    private static final String LOG_EXTENSION = ".log";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final Zeloria plugin;
    private final Path logFolder;
    private final ExecutorService executor;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public LoggerService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logFolder = plugin.getDataFolder().toPath()
                .resolve(LOG_DIR_NAME)
                .toAbsolutePath()
                .normalize();

        createLogFolder();

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Zeloria-Logger");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * INFO レベルのログを非同期で記録する。
     *
     * @param category カテゴリ
     * @param message  メッセージ
     */
    public void log(Category category, String message) {
        log(Level.INFO, category, message);
    }

    /**
     * 指定レベルのログを非同期で記録する。
     *
     * @param level    レベル
     * @param category カテゴリ
     * @param message  メッセージ
     */
    public void log(Level level, Category category, String message) {
        Level resolvedLevel = level == null ? Level.INFO : level;
        Category resolvedCategory = category == null ? Category.SYSTEM : category;
        String resolvedMessage = message == null ? "" : message;

        if (shuttingDown.get()) {
            // 停止処理中に呼ばれたログは取りこぼさないよう同期書き込みへフォールバックする。
            write(resolvedLevel, resolvedCategory, resolvedMessage);
            return;
        }

        try {
            executor.execute(() -> write(resolvedLevel, resolvedCategory, resolvedMessage));
        } catch (RejectedExecutionException e) {
            write(resolvedLevel, resolvedCategory, resolvedMessage);
        }
    }

    /**
     * プレイヤー名を伴うイベントログを記録する。
     *
     * @param category カテゴリ
     * @param message  メッセージ
     */
    public void event(Category category, String message) {
        log(Level.INFO, category, message);
    }

    /**
     * キューに残ったログを書き出してからサービスを停止する。
     * <p>
     * プラグイン無効化時に呼び出す。タイムアウトした場合は残りのタスクを破棄する。
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * ログ出力ディレクトリを取得する。
     */
    public Path getLogFolder() {
        return logFolder;
    }

    private void write(Level level, Category category, String message) {
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern(DATE_PATTERN));
        Path file = logFolder.resolve(date + LOG_EXTENSION);

        String line = "[" + now.format(DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN)) + "] "
                + "[" + level.name() + "] "
                + "[" + category.name() + "] "
                + sanitize(message)
                + System.lineSeparator();

        try {
            Files.createDirectories(logFolder);
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("ログファイルの書き込みに失敗しました: " + file + " (" + e.getMessage() + ")");
        }
    }

    private void createLogFolder() {
        try {
            Files.createDirectories(logFolder);
        } catch (IOException e) {
            plugin.getLogger().severe("ログフォルダの作成に失敗しました: " + logFolder + " (" + e.getMessage() + ")");
        }
    }

    /**
     * 1 イベントが 1 行になるよう、改行や制御文字を空白へ置換する。
     */
    private static String sanitize(String message) {
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * 日付別ログファイルのパスを取得する（表示・テスト用）。
     *
     * @param date 対象日
     * @return ログファイルパス
     */
    public Path resolveLogFile(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return logFolder.resolve(date.format(DateTimeFormatter.ofPattern(DATE_PATTERN)) + LOG_EXTENSION);
    }
}
