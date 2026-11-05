package com.zeloria.player;

import com.zeloria.core.Zeloria;
import com.zeloria.core.LoggerService;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.CooldownService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * プレイヤーデータの読み込み・キャッシュ・保存を統括するサービス。
 * <p>
 * {@link PlayerRepository} の非同期 API と {@link PlayerData} のオンメモリキャッシュを橋渡しする。
 * <ul>
 *     <li>ログイン時: DB から {@link PlayerSnapshot} を読み込み、{@link PlayerData} へ復元してキャッシュする</li>
 *     <li>退出時: メインスレッドで {@link PlayerData#snapshot()} を生成し、非同期で
 *         {@code INSERT ... ON DUPLICATE KEY UPDATE} を実行する。キャッシュ破棄は保存成功時のみ行う</li>
 * </ul>
 * <p>
 * 保存と読み込みが同一 UUID で交錯しないよう、UUID 単位で処理順序を保証する
 * （{@link #pendingSaves}）。また、保存完了時にキャッシュを破棄してよいかを
 * データ世代（{@link #dataGenerations}）とインスタンス同一性で検証するため、
 * 保存中にプレイヤーが再接続しても新しいセッションのキャッシュを巻き込まない。
 * <p>
 * 保存に失敗した場合はキャッシュを破棄せず保持し、後続の保存（{@link #saveAllAsync()}）で
 * 再試行できる状態にしたうえでログへ記録する。
 */
public class PlayerService {

    private final Zeloria plugin;
    private final PlayerRepository repository;

    /** DB から正常に読み込めたプレイヤー。読み込み失敗時の既定値で上書き保存しないために使う。 */
    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * 保存に失敗し、未保存のデータがキャッシュに残っているプレイヤー。
     * <p>
     * 退出時の保存が失敗したプレイヤーを保持し、{@link #saveAllAsync()} で再試行する。
     * 再ログイン時はこのキャッシュを DB より優先して復元し、未保存の進行状況を失わない。
     */
    private final Set<UUID> unsavedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * UUID ごとの進行中の保存。読み込みは同一 UUID の保存完了を待ってから実行する。
     */
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();

    /**
     * UUID ごとのデータ世代。読み込み（キャッシュ登録）のたびに更新する。
     * 保存完了時のキャッシュ破棄が、別セッションのデータを誤って消さないための照合に使う。
     */
    private final Map<UUID, Long> dataGenerations = new ConcurrentHashMap<>();

    /** データ世代の採番器。 */
    private final AtomicLong generationSequence = new AtomicLong();

    public PlayerService(Zeloria plugin, PlayerRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /* ------------------------------------------------------------------
     * 読み込み
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーデータを非同期で読み込み、キャッシュへ登録する。
     * <p>
     * 同一 UUID の保存が進行中の場合は、その完了を待ってから読み込む。退出時の保存が
     * 再ログイン後のキャッシュを破棄してしまう競合を防ぐため、UUID 単位で順序を保証する。
     * <p>
     * 直前の保存に失敗してキャッシュに未保存データが残っている場合は、DB の内容で
     * 上書きせずキャッシュを復元する（保存されていない進行状況を失わないため）。
     *
     * @param uuid プレイヤー UUID
     * @param name プレイヤー名
     * @return 復元されたプレイヤーデータ
     */
    public CompletableFuture<PlayerData> load(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        String playerName = name == null ? "" : name;

        return afterPendingSave(uuid).thenCompose(ignored -> {
            PlayerData retained = unsavedPlayers.contains(uuid) ? PlayerData.getCached(uuid) : null;
            if (retained != null) {
                retained.setName(playerName);
                applyClassDefaults(retained);
                PlayerData.cache(retained);
                loadedPlayers.add(uuid);
                markGeneration(uuid);
                plugin.getLogger().warning(
                        "未保存のプレイヤーデータをキャッシュから復元しました: " + playerName);
                log(LoggerService.Level.WARN,
                        "プレイヤーデータ復元（未保存キャッシュ）: player=" + playerName);
                return CompletableFuture.completedFuture(retained);
            }

            return repository.load(uuid, playerName)
                    .thenApply(snapshot -> fromSnapshot(snapshot))
                    .thenApply(data -> {
                        PlayerData.cache(data);
                        loadedPlayers.add(uuid);
                        // 新しいセッションの世代を採番し、古い保存による破棄を無効化する。
                        markGeneration(uuid);
                        unsavedPlayers.remove(uuid);
                        log(LoggerService.Level.INFO, "プレイヤーデータ読み込み: player=" + playerName);
                        return data;
                    });
        });
    }

    /**
     * 非同期読み込みを同期的に待つ（{@link org.bukkit.event.player.AsyncPlayerPreLoginEvent} 用）。
     * <p>
     * DB 未接続などで読み込めなかった場合は既定値の {@link PlayerData} を返す。
     * この場合 {@code loadedPlayers} には登録しないため、退出時に既定値で
     * 既存データを上書きすることはない。
     *
     * @param uuid プレイヤー UUID
     * @param name プレイヤー名
     * @return プレイヤーデータ（読み込み失敗時は既定値）
     */
    public PlayerData loadBlocking(UUID uuid, String name) {
        try {
            return load(uuid, name).join();
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            plugin.getLogger().warning(
                    "プレイヤーデータの読み込みに失敗しました: " + name + " (" + message + ")");
            log(LoggerService.Level.ERROR,
                    "プレイヤーデータ読み込み失敗: player=" + name + ", error=" + message);

            PlayerData fallback = createDefault(uuid, name);
            PlayerData.cache(fallback);
            // 既定値でも世代を進め、進行中だった保存による破棄の対象にならないようにする。
            markGeneration(uuid);
            return fallback;
        }
    }

    /**
     * オンラインプレイヤーのデータが未キャッシュの場合に読み込みを開始する。
     *
     * @param player 対象プレイヤー
     * @return 読み込み完了を表す future（既にキャッシュ済みの場合は完了済み future）
     */
    public CompletableFuture<PlayerData> loadIfAbsent(Player player) {
        Objects.requireNonNull(player, "player");

        PlayerData cached = PlayerData.getCached(player.getUniqueId());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return load(player.getUniqueId(), player.getName());
    }

    /**
     * スナップショットから {@link PlayerData} を復元する。
     */
    private PlayerData fromSnapshot(PlayerSnapshot snapshot) {
        return applyClassDefaults(PlayerData.fromSnapshot(snapshot));
    }

    private PlayerData createDefault(UUID uuid, String name) {
        return applyClassDefaults(new PlayerData(uuid, name));
    }

    /**
     * 設定で {@code unlockedByDefault: true} になっているクラスを解放する。
     * <p>
     * 既存プレイヤーのデータにはクラス情報が無い（空文字）ため、ログイン時に
     * 初期解放クラスを補完する。クラス定義が未初期化の場合は何もしない。
     *
     * @param data 対象プレイヤーデータ
     * @return 引数の {@code data}（そのまま返す）
     */
    private PlayerData applyClassDefaults(PlayerData data) {
        ClassManager classManager = plugin.getClassManager();
        if (classManager != null) {
            classManager.applyDefaultUnlocks(data.getClassData());
        }
        return data;
    }

    /* ------------------------------------------------------------------
     * 保存
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーデータを非同期で保存し、保存成功時にキャッシュを破棄する（退出処理用）。
     * <p>
     * {@link PlayerData#snapshot()} は呼び出し元スレッド（メインスレッド）で実行されるため、
     * GUI 操作と競合しない一貫した内容が保存される。保存に失敗した場合はキャッシュを破棄せず、
     * 後続の保存（{@link #saveAllAsync()} など）で再試行できる状態のまま保持する。
     *
     * @param uuid プレイヤー UUID
     * @param name プレイヤー名（名前変更を反映する）
     * @return 保存の完了を表す future
     */
    public CompletableFuture<Void> saveAndEvict(UUID uuid, String name) {
        return persistCached(uuid, name, true);
    }

    /**
     * キャッシュ済みのプレイヤーデータを非同期で保存する（キャッシュは保持）。
     *
     * @param uuid プレイヤー UUID
     * @param name プレイヤー名
     * @return 保存の完了を表す future
     */
    public CompletableFuture<Void> save(UUID uuid, String name) {
        return persistCached(uuid, name, false);
    }

    private CompletableFuture<Void> persistCached(UUID uuid, String name, boolean evict) {
        PlayerData data = PlayerData.getCached(uuid);
        if (data == null) {
            // 保存対象が無いため、再試行待ちの印も解除する。
            // （世代は別セッションの可能性があるため触らない）
            unsavedPlayers.remove(uuid);
            return CompletableFuture.completedFuture(null);
        }

        String playerName = name == null ? data.getName() : name;
        data.setName(playerName);

        // 破棄可否の判定に使う世代とインスタンスを、保存開始時点で控える。
        final long generation = currentGeneration(uuid);

        if (!loadedPlayers.contains(uuid)) {
            // 読み込みに失敗した既定値で既存レコードを上書きしない。
            plugin.getLogger().warning(
                    "DB から読み込めていないプレイヤーデータの保存をスキップしました: " + playerName);
            if (evict) {
                // 同時に別セッションのデータがキャッシュされた場合は破棄しない。
                evictIfCurrent(uuid, data, generation, playerName);
            }
            return CompletableFuture.completedFuture(null);
        }

        final PlayerSnapshot snapshot;
        try {
            // ここは呼び出し元（メイン）スレッド。以降は不変スナップショットのみを扱う。
            // クールダウンはセッション内のマップにあるため、保存直前に文字列へ反映する。
            CooldownService cooldownService = plugin.getCooldownService();
            if (cooldownService != null) {
                String serialized = cooldownService.serialize(uuid);
                // オフライン再保存（失敗後の再試行）では既に保存待ちの値を保持する。
                if (!serialized.isEmpty() || Bukkit.getPlayer(uuid) != null) {
                    data.setCooldownEndAt(serialized);
                }
            }
            snapshot = data.snapshot();
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            plugin.getLogger().warning(
                    "プレイヤーデータのシリアライズに失敗しました: " + playerName + " (" + message + ")");
            log(LoggerService.Level.ERROR,
                    "プレイヤーデータのシリアライズ失敗: player=" + playerName + ", error=" + message);
            // 復旧の余地を残すため、キャッシュは破棄せず保持して再試行可能な状態にする。
            unsavedPlayers.add(uuid);
            return CompletableFuture.failedFuture(e);
        }

        // 破棄可否の判定に使う世代とインスタンスは保存開始時点で控え済み。
        CompletableFuture<Void> saveFuture = repository.save(snapshot).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                String message = rootMessage(throwable);
                plugin.getLogger().warning(
                        "プレイヤーデータの保存に失敗しました: " + playerName + " (" + message + ")"
                                + " - キャッシュを保持し、後続の保存で再試行します。");
                log(LoggerService.Level.ERROR,
                        "プレイヤーデータ保存失敗: player=" + playerName + ", error=" + message
                                + ", cacheKept=true");
                // キャッシュ・loadedPlayers を保持し、saveAllAsync() で再試行できるようにする。
                unsavedPlayers.add(uuid);
                return;
            }

            log(LoggerService.Level.INFO,
                    "プレイヤーデータ保存: player=" + playerName + ", uuid=" + uuid);
            unsavedPlayers.remove(uuid);
            if (evict) {
                evictIfCurrent(uuid, data, generation, playerName);
            }
        });

        // 同一 UUID の読み込みを保存完了後に直列化するため、進行中の保存として登録する。
        pendingSaves.put(uuid, saveFuture);
        saveFuture.whenComplete((ignored, throwable) -> pendingSaves.remove(uuid, saveFuture));
        return saveFuture;
    }

    /**
     * 保存完了後にキャッシュを破棄してよいか検証したうえで破棄する。
     * <p>
     * 保存中にプレイヤーが再接続した場合、キャッシュは新しいセッションのデータに
     * 置き換わっている（世代も進んでいる）。その場合は破棄せず、新セッションの
     * データと {@code loadedPlayers} をそのまま残す。
     *
     * @param uuid       プレイヤー UUID
     * @param data       保存時点のキャッシュインスタンス
     * @param generation 保存開始時点のデータ世代
     * @param playerName プレイヤー名（ログ用）
     */
    private void evictIfCurrent(UUID uuid, PlayerData data, long generation, String playerName) {
        boolean sameGeneration = currentGeneration(uuid) == generation;
        boolean sameInstance = PlayerData.getCached(uuid) == data;
        if (!sameGeneration || !sameInstance) {
            plugin.getLogger().info(
                    "保存中に別セッションのデータがキャッシュされたため、キャッシュの破棄をスキップしました: "
                            + playerName);
            log(LoggerService.Level.INFO,
                    "プレイヤーデータ破棄スキップ（世代不一致）: player=" + playerName
                            + ", generation=" + generation);
            return;
        }

        loadedPlayers.remove(uuid);
        PlayerData.removeCached(uuid);
        dataGenerations.remove(uuid, generation);
        clearCooldowns(uuid);
    }

    /**
     * 退出したプレイヤーのクールダウンを破棄する。
     * <p>
     * クールダウンはセッション内の一時状態のため、DB 保存の成否とは独立して破棄する。
     * 新しいセッションが既に開始されている場合は、呼び出し側の世代チェックで
     * このメソッド自体が実行されない。
     *
     * @param uuid プレイヤー UUID
     */
    private void clearCooldowns(UUID uuid) {
        if (plugin.getCooldownService() != null) {
            plugin.getCooldownService().clear(uuid);
        }
    }

    /**
     * オンライン全員のデータを保存し、DB の Flush まで完了させる（安全停止用）。
     * <p>
     * 退出時の保存に失敗してキャッシュへ残っているプレイヤー（オフライン）のデータも
     * 併せて再試行する。個々の保存失敗はログに記録したうえで処理を継続する。
     *
     * @return すべての保存と Flush の完了を表す future
     */
    public CompletableFuture<Void> saveAllAsync() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        Set<UUID> scheduled = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduled.add(player.getUniqueId());
            saves.add(save(player.getUniqueId(), player.getName())
                    .exceptionally(throwable -> {
                        plugin.getLogger().warning(
                                "プレイヤーデータの保存に失敗しました: " + player.getName()
                                        + " (" + rootMessage(throwable) + ")");
                        return null;
                    }));
        }

        // 退出時の保存に失敗したまま残っているキャッシュを再試行する。
        for (UUID uuid : new ArrayList<>(unsavedPlayers)) {
            if (!scheduled.add(uuid)) {
                continue;
            }
            PlayerData cached = PlayerData.getCached(uuid);
            if (cached == null) {
                unsavedPlayers.remove(uuid);
                continue;
            }
            String name = cached.getName();
            plugin.getLogger().warning(
                    "未保存のプレイヤーデータを再保存します: " + name);
            saves.add(save(uuid, name)
                    .exceptionally(throwable -> {
                        plugin.getLogger().warning(
                                "未保存プレイヤーデータの再保存に失敗しました: " + name
                                        + " (" + rootMessage(throwable) + ")");
                        return null;
                    }));
        }

        CompletableFuture<Void> allSaved = CompletableFuture.allOf(
                saves.toArray(new CompletableFuture[0]));

        return allSaved.thenCompose(ignored -> repository.flush()
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "データベースの Flush に失敗しました: " + rootMessage(throwable));
                    return null;
                }));
    }

    /**
     * オンライン全員のデータ保存を同期的に待つ（プラグイン無効化時用）。
     * <p>
     * タイムアウトした場合は警告を出して処理を継続する。
     *
     * @param timeoutSeconds 待機する最大秒数
     */
    public void saveAllBlocking(long timeoutSeconds) {
        try {
            saveAllAsync()
                    .orTimeout(Math.max(1, timeoutSeconds), java.util.concurrent.TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException e) {
            plugin.getLogger().warning("プレイヤーデータの一括保存が完了しませんでした: " + rootMessage(e));
        } catch (java.util.concurrent.CancellationException e) {
            plugin.getLogger().warning("プレイヤーデータの一括保存がキャンセルされました。");
        }
    }

    /**
     * 指定プレイヤーのキャッシュを破棄する（保存は行わない）。
     */
    public void evict(UUID uuid) {
        if (uuid == null) {
            return;
        }
        loadedPlayers.remove(uuid);
        PlayerData.removeCached(uuid);
        dataGenerations.remove(uuid);
        unsavedPlayers.remove(uuid);
        clearCooldowns(uuid);
    }

    /* ------------------------------------------------------------------
     * 世代・順序制御
     * ------------------------------------------------------------------ */

    /**
     * 同一 UUID の保存が進行中の場合、その完了を待つ future を返す。
     * <p>
     * 保存が失敗していても読み込みは継続させる（失敗時はキャッシュが保持されている）。
     *
     * @param uuid プレイヤー UUID
     * @return 保存完了（または保存なし）を表す future
     */
    private CompletableFuture<Void> afterPendingSave(UUID uuid) {
        CompletableFuture<Void> pending = pendingSaves.get(uuid);
        if (pending == null) {
            return CompletableFuture.completedFuture(null);
        }
        return pending.handle((ignored, throwable) -> null);
    }

    /**
     * プレイヤーデータの世代を進め、新しい値を返す。
     * <p>
     * ログイン（キャッシュ登録）のたびに呼び出し、進行中だった保存が古い世代を根拠に
     * 新しいセッションのキャッシュを破棄してしまうのを防ぐ。
     *
     * @param uuid プレイヤー UUID
     * @return 採番された新しい世代
     */
    private long markGeneration(UUID uuid) {
        long generation = generationSequence.incrementAndGet();
        dataGenerations.put(uuid, generation);
        return generation;
    }

    /**
     * 現在のデータ世代を取得する。未採番の場合は {@code 0}。
     */
    private long currentGeneration(UUID uuid) {
        Long generation = dataGenerations.get(uuid);
        return generation == null ? 0L : generation;
    }

    /**
     * 指定プレイヤーが DB から正常に読み込まれているか判定する。
     */
    public boolean isLoaded(UUID uuid) {
        return uuid != null && loadedPlayers.contains(uuid);
    }

    /**
     * プレイヤーデータのリポジトリを取得する。
     */
    public PlayerRepository getRepository() {
        return repository;
    }

    private void log(LoggerService.Level level, String message) {
        if (plugin.getLoggerService() != null) {
            plugin.getLoggerService().log(level, LoggerService.Category.DATA, message);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
