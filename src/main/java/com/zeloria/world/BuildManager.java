package com.zeloria.world;

import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * チャンネル 1 専用の建築モードを管理するクラス。
 * <p>
 * 建築ワールドはテンプレート（{@code WorldData/<templateId>}、既定:
 * {@code ZeloriaServer/WorldData/<templateId>}）を
 * {@code <worldContainer>/<mainWorldLevel>/dimensions/minecraft/<templateId>_build}
 * へ複製し、{@code WorldCreator.ofKey(new NamespacedKey("minecraft", templateId + "_build"))}
 * を {@link World.Environment#NORMAL} でロードする。
 * テンプレートごとに 1 つの建築ワールドを複数人で共有でき、異なるテンプレートの
 * 建築ワールドは同時に開くことができる。
 * <p>
 * テンプレートの {@code level.dat} は {@code minecraft:overworld} のディメンション定義しか
 * 持たないため、独自名前空間のカスタムディメンション（{@code CUSTOM} +
 * {@code customEnvironmentKey}）としてはロードできない。標準の {@code minecraft}
 * 名前空間の通常ディメンションとして扱うことで、コピー済みの {@code level.dat} を利用する。
 * <p>
 * 保存時は建築ワールドを {@code Bukkit.unloadWorld(world, true)} でセーブしたうえで、
 * ディメンション側の内容をテンプレートへ上書きコピーし、ディメンション側の
 * ディレクトリを削除する。
 * <p>
 * ファイルコピーは専用スレッドで行い、ワールド操作はすべてメインスレッドで行う。
 * 建築モードは本クラスが {@code null}（チャンネル 2 以降）の場合は利用できない。
 */
public class BuildManager {

    /**
     * 建築ワールドのネームスペース。
     * <p>
     * テンプレートの {@code level.dat} が {@code minecraft:overworld} しか定義していないため、
     * レイヤーインスタンス・建築ワールドはいずれも {@code minecraft} 名前空間でロードする。
     * ディレクトリも {@code dimensions/minecraft/<key>} となる。
     */
    public static final String BUILD_NAMESPACE = "minecraft";

    /**
     * 建築ワールドのキーに付与するサフィックス。
     * <p>
     * 一般レイヤー（{@code <templateId>_instance_N}）と区別するため、
     * 建築ワールドは {@code minecraft:<templateId>_build} でロードする。
     */
    public static final String BUILD_WORLD_SUFFIX = "_build";

    /** 建築モードの実行権限。 */
    public static final String BUILD_PERMISSION = "zeloria.admin.build";

    /** テンプレート／建築ワールド ID に許可する文字種（パストラバーサル防止）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** メインワールドのレベル名が取得できなかった場合に使用する既定値。 */
    private static final String DEFAULT_MAIN_WORLD_NAME = "world";

    /** テンプレート保存時に使用する一時ディレクトリの接尾辞。 */
    private static final String TEMP_SUFFIX = ".tmp";

    /** テンプレート保存時に使用するバックアップディレクトリの接尾辞。 */
    private static final String BACKUP_SUFFIX = ".bak";

    /** ディレクトリ削除の最大試行回数。 */
    private static final int DELETE_ATTEMPTS = 3;

    /** ディレクトリ削除の再試行間隔（ミリ秒）。 */
    private static final long DELETE_RETRY_DELAY_MS = 50L;

    /** 建築ワールドが無人になってから自動保存するまでの時間（ミリ秒）。 */
    public static final long AUTO_SAVE_IDLE_MILLIS = 60_000L;

    /** 転送待機中にアクションバーへ表示するメッセージ。 */
    private static final Component TRANSFER_PREPARING =
            MiniMessage.miniMessage().deserialize("<gray>転送準備中...</gray>");

    private final Zeloria plugin;

    /** テンプレート配置先: {@code <worldDataFolder>}（既定: {@code ZeloriaServer/WorldData}） */
    private final Path templateRoot;

    /**
     * 建築ワールドの配置先の親ディレクトリ:
     * {@code <worldContainer>/<mainWorldLevel>/dimensions/minecraft}
     */
    private final Path buildRoot;

    /**
     * 現在開いている建築ワールド（テンプレート ID → ワールド）。
     * 複数のテンプレートを同時に建築モードで開くことができる。
     */
    private final Map<String, World> buildWorlds = new ConcurrentHashMap<>();

    /** 建築ワールドの作成者（テンプレート ID → 作成者の UUID）。 */
    private final Map<String, UUID> buildWorldOwners = new ConcurrentHashMap<>();

    /** 建築ワールドの最終アクティブ時刻（テンプレート ID → エポックミリ秒）。 */
    private final Map<String, Long> buildWorldLastActive = new ConcurrentHashMap<>();

    /** 建築ワールド入場前のゲームモード（プレイヤー UUID → 元のゲームモード）。 */
    private final Map<UUID, GameMode> previousGameModes = new ConcurrentHashMap<>();

    /** 複製・保存処理の多重実行を防ぐフラグ。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** 保存処理の実行中フラグ。完了するまで次の建築コマンドを拒否する。 */
    private final AtomicBoolean savePending = new AtomicBoolean(false);

    /** アンロード（破棄）処理の実行中フラグ。完了するまで次の建築コマンドを拒否する。 */
    private final AtomicBoolean unloadPending = new AtomicBoolean(false);

    /** 建築ワールドにプレイヤーがいなくなった時刻（テンプレート ID → ミリ秒）。0 は未計測を表す。 */
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();

    /** ファイルコピー専用のエグゼキュータ。 */
    private final ExecutorService copyExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Zeloria-BuildCopy");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 建築モード管理を生成する。
     *
     * @param plugin プラグイン
     */
    public BuildManager(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        File worldDataFolder = plugin.getWorldDataFolder();
        if (worldDataFolder == null) {
            throw new IllegalStateException("world-data-path が初期化されていません。");
        }
        this.templateRoot = worldDataFolder.toPath()
                .toAbsolutePath()
                .normalize();
        this.buildRoot = resolveBuildRoot();
    }

    /**
     * 建築ワールドを開く（テンプレートの複製・ロード・プレイヤーの転送まで行う）。
     * <p>
     * 後方互換用の入口。指定テンプレートの建築ワールドを新規作成する。
     * 既に同じテンプレートの建築ワールドが開かれている場合は失敗する。
     *
     * @param templateId テンプレート ID（{@code WorldData} 配下のディレクトリ名）
     * @param player     入場させるプレイヤー（{@code null} の場合はワールドのロードのみ）
     * @return ロード完了時にワールドを返す future（失敗時は例外で完了）
     */
    public synchronized CompletableFuture<World> openBuildWorldAsync(String templateId, Player player) {
        return createBuildWorldAsync(templateId, player);
    }

    /**
     * 指定テンプレートの建築ワールドを新規作成する。
     * <p>
     * 建築ワールドは {@code minecraft:<templateId>_build} としてロードし、
     * {@link #buildWorlds} に登録する。テンプレートごとに 1 つの建築ワールドを
     * 複数人で共有できる。
     *
     * @param templateId テンプレート ID（{@code WorldData} 配下のディレクトリ名）
     * @param player     入場させるプレイヤー（{@code null} の場合はワールドのロードのみ）
     * @return ロード完了時にワールドを返す future（失敗時は例外で完了）
     */
    public synchronized CompletableFuture<World> createBuildWorldAsync(String templateId, Player player) {
        assertMainThread();
        validateId(templateId, "templateId");

        World existing = findBuildWorld(templateId);
        if (existing != null) {
            buildWorlds.put(templateId, existing);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "既に開かれています。/admin build join " + templateId + " で参加してください"));
        }

        if (!beginOperation(null)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "建築ワールドの準備中です。完了してから再実行してください。"));
        }

        Path templateDir;
        Path buildDir;
        try {
            templateDir = resolveTemplatePath(templateId);
            buildDir = resolveBuildPath(templateId);
            if (!Files.isDirectory(templateDir)) {
                throw new IllegalArgumentException("建築テンプレートが見つかりません: " + templateDir);
            }
        } catch (RuntimeException e) {
            busy.set(false);
            return CompletableFuture.failedFuture(e);
        }

        if (player != null) {
            buildWorldOwners.putIfAbsent(templateId, player.getUniqueId());
        }

        CompletableFuture<World> result = CompletableFuture
                .supplyAsync(() -> prepareBuildDirectory(templateId, templateDir, buildDir),
                        copyExecutor)
                .thenCompose(dir -> loadWorldOnMainThread(templateId, dir, player));

        result.whenComplete((world, error) -> {
            if (error != null && player != null) {
                buildWorldOwners.remove(templateId, player.getUniqueId());
            }
            busy.set(false);
        });
        return result;
    }

    /**
     * 建築ワールドを保存し、アンロードする（後方互換用）。
     * <p>
     * 引数省略時と同様に、開いているすべての建築ワールドを保存する。
     *
     * @return 保存に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> saveActiveAsync() {
        return saveAllBuildWorldsAsync();
    }

    /**
     * 建築ワールドを保存せずにアンロードする（後方互換用）。
     * <p>
     * 引数省略時と同様に、開いているすべての建築ワールドを破棄する。
     *
     * @return 破棄に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> unsaveActiveAsync() {
        return unsaveAllBuildWorldsAsync();
    }

    /**
     * 指定テンプレートの建築ワールドを保存してアンロードする。
     * <p>
     * ワールド内のプレイヤーは Hub のスポーンへ退避させ、アンロード後に
     * ディメンション側の内容をテンプレートへ上書きコピーしてから
     * ディメンション側のディレクトリを削除する。
     *
     * @param templateId テンプレート ID
     * @return 保存に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> saveBuildWorldAsync(String templateId) {
        return closeBuildWorldAsync(templateId, true, savePending);
    }

    /**
     * 指定テンプレートの建築ワールドを保存せずにアンロードし、ディレクトリを削除する。
     *
     * @param templateId テンプレート ID
     * @return 破棄に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> unsaveBuildWorldAsync(String templateId) {
        return closeBuildWorldAsync(templateId, false, unloadPending);
    }

    /**
     * 開いているすべての建築ワールドを保存してアンロードする。
     *
     * @return すべての保存に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> saveAllBuildWorldsAsync() {
        assertMainThread();
        Set<String> templateIds = collectOpenTemplateIds();
        if (templateIds.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!beginOperation(savePending)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "建築ワールドの準備中です。完了してから再実行してください。"));
        }
        CompletableFuture<Boolean> result = closeBuildWorldsSequentially(templateIds, true);
        result.whenComplete((saved, error) -> finishOperation(savePending));
        return result;
    }

    /**
     * 開いているすべての建築ワールドを保存せずにアンロードする。
     *
     * @return すべての破棄に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> unsaveAllBuildWorldsAsync() {
        assertMainThread();
        Set<String> templateIds = collectOpenTemplateIds();
        if (templateIds.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!beginOperation(unloadPending)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "建築ワールドの準備中です。完了してから再実行してください。"));
        }
        CompletableFuture<Boolean> result = closeBuildWorldsSequentially(templateIds, false);
        result.whenComplete((discarded, error) -> finishOperation(unloadPending));
        return result;
    }

    /**
     * 指定テンプレートの建築ワールドを閉じる共通処理。
     *
     * @param templateId   テンプレート ID
     * @param saveTemplate {@code true} の場合はテンプレートへ保存、{@code false} の場合は破棄
     * @param pendingState 保存待ち／アンロード待ちの状態フラグ
     * @return 処理に成功した場合は {@code true} で完了する future
     */
    private synchronized CompletableFuture<Boolean> closeBuildWorldAsync(
            String templateId, boolean saveTemplate, AtomicBoolean pendingState) {
        assertMainThread();
        validateId(templateId, "templateId");
        if (findBuildWorld(templateId) == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!beginOperation(pendingState)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "建築ワールドの準備中です。完了してから再実行してください。"));
        }
        CompletableFuture<Boolean> result = closeWorldInternal(templateId, saveTemplate);
        result.whenComplete((done, error) -> finishOperation(pendingState));
        return result;
    }

    /**
     * 複数の建築ワールドをメインスレッドで 1 件ずつ順番に閉じる。
     *
     * @param templateIds 対象テンプレート ID
     * @param saveTemplate {@code true} の場合は保存、{@code false} の場合は破棄
     * @return すべて成功した場合は {@code true} で完了する future
     */
    private CompletableFuture<Boolean> closeBuildWorldsSequentially(
            Set<String> templateIds, boolean saveTemplate) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        for (String templateId : templateIds) {
            result = result.thenCompose(success -> {
                if (!Boolean.TRUE.equals(success)) {
                    return CompletableFuture.completedFuture(false);
                }
                return onMainThread(() -> closeWorldInternal(templateId, saveTemplate));
            });
        }
        return result;
    }

    /**
     * メインスレッドで処理を実行し、その結果を返す future を生成する。
     *
     * @param supplier メインスレッドで実行する処理
     * @return 処理結果で完了する future
     */
    private CompletableFuture<Boolean> onMainThread(Supplier<CompletableFuture<Boolean>> supplier) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                supplier.get().whenComplete((value, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(value);
                    }
                });
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
        return future;
    }

    /**
     * 建築ワールドを保存して終了する（プラグイン停止時に呼び出す）。
     * <p>
     * 停止処理では非同期処理の完了を待てないため、ファイルコピーも同期実行する。
     * 開いているすべての建築ワールドを保存する。
     */
    public synchronized void shutdown() {
        for (String templateId : collectOpenTemplateIds()) {
            shutdownWorld(templateId);
        }
        buildWorlds.clear();
        buildWorldOwners.clear();
        buildWorldLastActive.clear();
        emptySince.clear();
        copyExecutor.shutdownNow();
    }

    /**
     * 指定テンプレートの建築ワールドを同期的に保存してアンロードする。
     *
     * @param templateId テンプレート ID
     */
    private void shutdownWorld(String templateId) {
        World world = buildWorlds.get(templateId);
        if (world == null) {
            world = findLoadedWorld(templateId);
        }

        Path buildDir = resolveBuildPath(templateId);

        boolean unloaded = true;
        if (world != null) {
            if (!teleportPlayersToHub(world, false)) {
                plugin.getLogger().severe(
                        "建築ワールド '" + templateId + "' のプレイヤー退避に失敗したため、"
                                + "アンロードを中止しました。");
                unloaded = false;
            } else if (!Bukkit.unloadWorld(world, true)) {
                plugin.getLogger().severe(
                        "建築ワールド '" + templateId + "' のアンロードに失敗しました。");
                unloaded = false;
            }
        }
        removeBuildWorldEntries(templateId);

        // アンロードできていない場合は、稼働中ワールドのディレクトリを
        // テンプレートへコピー／削除しない（未保存データとワールド破損を防ぐ）。
        if (unloaded && Files.isDirectory(buildDir)) {
            LayerManager layerManager = plugin.getLayerManager();
            if (layerManager != null) {
                layerManager.lockTemplate(templateId);
            }
            try {
                overwriteTemplate(templateId, buildDir);
                deleteDirectoryWithRetry(buildDir);
            } catch (RuntimeException e) {
                plugin.getLogger().severe(
                        "建築ワールドの保存に失敗しました: " + templateId + " (" + rootMessage(e) + ")");
            } finally {
                if (layerManager != null) {
                    layerManager.unlockTemplate(templateId);
                }
            }
        }
    }

    /**
     * 建築ワールドの複製・保存・破棄処理が進行中か判定する。
     * <p>
     * 保存待ち・アンロード待ちの状態も「処理中」として扱い、
     * 完了するまで次の建築コマンドを受け付けない。
     *
     * @return 処理中の場合は {@code true}
     */
    public boolean isBusy() {
        return busy.get() || savePending.get() || unloadPending.get();
    }

    /**
     * 保存処理が進行中か判定する。
     *
     * @return 保存待ちの場合は {@code true}
     */
    public boolean isSavePending() {
        return savePending.get();
    }

    /**
     * アンロード（破棄）処理が進行中か判定する。
     *
     * @return アンロード待ちの場合は {@code true}
     */
    public boolean isUnloadPending() {
        return unloadPending.get();
    }

    /**
     * 現在開いているテンプレート ID を取得する。
     * <p>
     * 後方互換用。複数開いている場合は {@link #buildWorlds} の最初のエントリを返す。
     *
     * @return 建築中のテンプレート ID。開いていない場合は {@code null}
     */
    public String getActiveTemplateId() {
        return primaryTemplateId();
    }

    /**
     * 現在開いている建築テンプレート ID を取得する。
     * <p>
     * 後方互換用。複数開いている場合は {@link #buildWorlds} の最初のエントリを返す。
     *
     * @return 建築中のテンプレート ID。開いていない場合は {@code null}
     */
    public String getCurrentTemplateId() {
        return primaryTemplateId();
    }

    /**
     * 現在開いている建築ワールドを取得する。
     * <p>
     * 後方互換用。複数開いている場合は {@link #buildWorlds} の最初のエントリを返す。
     *
     * @return 建築ワールド。開いていない場合は {@code null}
     */
    public World getCurrentBuildWorld() {
        String templateId = primaryTemplateId();
        return templateId == null ? null : buildWorlds.get(templateId);
    }

    /**
     * 開いている建築ワールドのテンプレート ID 一覧を取得する。
     *
     * @return テンプレート ID の一覧（大文字小文字を無視して昇順）
     */
    public Set<String> getOpenTemplateIds() {
        return collectOpenTemplateIds();
    }

    /**
     * 指定テンプレートの建築ワールドが開いているか判定する。
     *
     * @param templateId テンプレート ID
     * @return 開いている場合は {@code true}
     */
    public boolean isBuildWorldOpen(String templateId) {
        return findBuildWorld(templateId) != null;
    }

    /**
     * 指定テンプレートの建築ワールドを取得する。
     *
     * @param templateId テンプレート ID
     * @return 建築ワールド。開いていない場合は {@code null}
     */
    public World getBuildWorld(String templateId) {
        return findBuildWorld(templateId);
    }

    /**
     * 指定テンプレートの建築ワールドにいるプレイヤー数を取得する。
     *
     * @param templateId テンプレート ID
     * @return プレイヤー数。開いていない場合は {@code 0}
     */
    public int getBuildWorldPlayerCount(String templateId) {
        World world = findBuildWorld(templateId);
        return world == null ? 0 : world.getPlayers().size();
    }

    /**
     * 指定テンプレートの建築ワールドの作成者 UUID を取得する。
     *
     * @param templateId テンプレート ID
     * @return 作成者の UUID。記録が無い場合は {@code null}
     */
    public UUID getBuildWorldOwner(String templateId) {
        return templateId == null ? null : buildWorldOwners.get(templateId);
    }

    /**
     * 指定テンプレートの建築ワールドの最終アクティブ時刻を取得する。
     *
     * @param templateId テンプレート ID
     * @return エポックミリ秒。記録が無い場合は {@code 0}
     */
    public long getBuildWorldLastActive(String templateId) {
        Long lastActive = templateId == null ? null : buildWorldLastActive.get(templateId);
        return lastActive == null ? 0L : lastActive;
    }

    /**
     * 建築ワールドにいるプレイヤー名の一覧を取得する。
     *
     * @return プレイヤー名の一覧（名前順）。建築ワールドが無い場合は空リスト
     */
    public List<String> getActivePlayerNames() {
        return getActivePlayerNames(primaryTemplateId());
    }

    /**
     * 指定テンプレートの建築ワールドにいるプレイヤー名の一覧を取得する。
     *
     * @param templateId テンプレート ID
     * @return プレイヤー名の一覧（名前順）。建築ワールドが無い場合は空リスト
     */
    public List<String> getActivePlayerNames(String templateId) {
        World world = findBuildWorld(templateId);
        if (world == null) {
            return List.of();
        }
        return world.getPlayers().stream()
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * 建築ワールドに実行者以外のプレイヤーがいるか判定する。
     *
     * @param executor 実行者。{@code null}（コンソール）の場合は全プレイヤーを対象にする
     * @return 他のプレイヤーがいる場合は {@code true}
     */
    public boolean hasOtherPlayers(Player executor) {
        return hasOtherPlayers(primaryTemplateId(), executor);
    }

    /**
     * 指定テンプレートの建築ワールドに実行者以外のプレイヤーがいるか判定する。
     *
     * @param templateId テンプレート ID
     * @param executor   実行者。{@code null}（コンソール）の場合は全プレイヤーを対象にする
     * @return 他のプレイヤーがいる場合は {@code true}
     */
    public boolean hasOtherPlayers(String templateId, Player executor) {
        World world = findBuildWorld(templateId);
        if (world == null) {
            return false;
        }
        UUID executorId = executor == null ? null : executor.getUniqueId();
        return world.getPlayers().stream()
                .anyMatch(player -> executorId == null || !player.getUniqueId().equals(executorId));
    }

    /**
     * 既に開いている建築ワールドへプレイヤーを参戦させる。
     *
     * @param player 対象プレイヤー
     * @return 参戦できた場合は {@code true}。建築ワールドが無い場合は {@code false}
     */
    public synchronized boolean joinBuildWorld(Player player) {
        return joinBuildWorld(primaryTemplateId(), player);
    }

    /**
     * 指定テンプレートの建築ワールドへプレイヤーを参戦させる。
     * <p>
     * 参加時に最終アクティブ時刻を更新する。
     *
     * @param templateId テンプレート ID
     * @param player     対象プレイヤー
     * @return 参戦できた場合は {@code true}。建築ワールドが無い場合は {@code false}
     */
    public synchronized boolean joinBuildWorld(String templateId, Player player) {
        assertMainThread();
        World world = findBuildWorld(templateId);
        if (world == null) {
            return false;
        }
        if (player != null) {
            buildWorldLastActive.put(templateId, System.currentTimeMillis());
        }
        enterBuildWorld(templateId, player, world, false);
        return true;
    }

    /**
     * 建築ワールド入場前のゲームモードへ戻す。
     * <p>
     * 建築ワールドへ入場していないプレイヤーに対しては何もしない。
     * 退出経路（保存時の退避、テレポート、プラグイン停止）から呼び出す。
     *
     * @param player 対象プレイヤー
     */
    public void restoreGameMode(Player player) {
        if (player == null) {
            return;
        }
        GameMode previous = previousGameModes.remove(player.getUniqueId());
        if (previous != null) {
            applyGameMode(player, previous);
        }
    }

    /**
     * 建築ワールドが無人になってから 1 分経過していれば、自動保存してアンロードする。
     * <p>
     * {@link com.zeloria.world.LayerCleanupTask} からメインスレッドで定期的に呼び出す。
     *
     * @param now 現在時刻（ミリ秒）
     */
    public synchronized void checkAutoSave(long now) {
        if (isBusy()) {
            return;
        }

        emptySince.keySet().retainAll(collectOpenTemplateIds());

        for (String templateId : collectOpenTemplateIds()) {
            World world = findBuildWorld(templateId);
            if (world == null) {
                emptySince.remove(templateId);
                continue;
            }
            if (!world.getPlayers().isEmpty()) {
                emptySince.remove(templateId);
                continue;
            }
            long since = emptySince.getOrDefault(templateId, 0L);
            if (since == 0L) {
                emptySince.put(templateId, now);
                continue;
            }
            if (now - since < AUTO_SAVE_IDLE_MILLIS) {
                continue;
            }

            emptySince.remove(templateId);
            saveBuildWorldAsync(templateId).whenComplete((saved, error) -> {
                if (error != null) {
                    plugin.getLogger().severe(
                            "建築用ワールド " + templateId + " の自動保存に失敗しました: "
                                    + rootMessage(error));
                } else if (Boolean.TRUE.equals(saved)) {
                    plugin.getLogger().info(
                            "建築用ワールド " + templateId + " を自動保存してアンロードしました");
                } else {
                    plugin.getLogger().warning(
                            "建築用ワールド " + templateId + " の自動保存を完了できませんでした。");
                }
            });
            // 1 回の呼び出しで 1 ワールドずつ処理する（次回のタスクで残りを処理する）。
            return;
        }
    }

    /**
     * ロード済みの建築ワールドを取得する。
     *
     * @param templateId テンプレート ID
     * @return ロード済みのワールド。存在しない場合は {@code null}
     */
    public World getLoadedWorld(String templateId) {
        return findBuildWorld(templateId);
    }

    /**
     * 利用可能な建築テンプレート ID の一覧を取得する。
     * <p>
     * {@code WorldData} 配下のディレクトリ名を返す。保存処理中の一時ディレクトリ
     * （{@code .<id>.tmp} など）は先頭が {@code .} のため除外する。
     *
     * @return テンプレート ID の一覧（昇順）。テンプレートが無い場合は空リスト
     */
    public List<String> listTemplateIds() {
        if (!Files.isDirectory(templateRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(templateRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .filter(name -> ID_PATTERN.matcher(name).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "建築テンプレート一覧の取得に失敗しました: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 指定したワールドが建築ワールドか判定する。
     * <p>
     * {@code minecraft} 名前空間を共有するレイヤーインスタンス
     * （{@code minecraft:<templateId>_instance_N}）と区別するため、
     * キー名が {@link #BUILD_WORLD_SUFFIX} で終わるものだけを建築ワールドとする。
     *
     * @param world 判定対象のワールド
     * @return 建築ワールドの場合は {@code true}
     */
    public static boolean isBuildWorld(World world) {
        if (world == null || !BUILD_NAMESPACE.equals(world.getKey().getNamespace())) {
            return false;
        }
        if (!world.getKey().getKey().endsWith(BUILD_WORLD_SUFFIX)) {
            return false;
        }
        // 所有権マーカーを持つディレクトリはレイヤーインスタンスであり、建築ワールドではない。
        if (LayerManager.isInstanceDirectory(world.getWorldPath())) {
            return false;
        }
        return !LayerManager.isInstanceId(world.getKey().getKey());
    }

    /**
     * 指定したワールドが「現在開いている」建築ワールドか判定する。
     * <p>
     * テレポート制限では、同じ名前空間（{@code minecraft}）を共有する
     * レイヤーインスタンスやバニラワールドへの転送を妨げないよう、
     * この判定で建築ワールドを限定する。
     *
     * @param world 判定対象のワールド
     * @return 現在の建築ワールドの場合は {@code true}
     */
    public boolean isActiveBuildWorld(World world) {
        return world != null && buildWorlds.containsValue(world);
    }

    /**
     * テンプレート ID から建築ワールドのキーを生成する。
     *
     * @param templateId テンプレート ID
     * @return {@code minecraft:<templateId>_build} のキー
     */
    public static NamespacedKey keyOf(String templateId) {
        return NamespacedKey.minecraft(
                Objects.requireNonNull(templateId, "templateId") + BUILD_WORLD_SUFFIX);
    }

    /**
     * 指定したテンプレートの建築ワールドがロード済みか検索する。
     *
     * @param templateId テンプレート ID
     * @return ロード済みのワールド。存在しない場合は {@code null}
     */
    private static World findLoadedWorld(String templateId) {
        NamespacedKey key = keyOf(templateId);
        for (World world : Bukkit.getWorlds()) {
            if (key.equals(world.getKey())) {
                return world;
            }
        }
        return null;
    }

    /**
     * 指定テンプレートの建築ワールドを Hub へ退避・アンロードし、必要に応じて保存する。
     * <p>
     * アンロードまではメインスレッドで同期的に実行し、ファイルコピーと
     * ディレクトリ削除のみ非同期で行う。保存中は対象テンプレートを
     * {@link LayerManager} 側でロックし、新規レイヤー生成を抑止する。
     *
     * @param templateId     テンプレート ID
     * @param saveToTemplate テンプレートへ保存する場合は {@code true}。
     *                       {@code false} の場合は保存せずにディレクトリを削除する
     * @return 処理に成功した場合は {@code true} で完了する future
     */
    private CompletableFuture<Boolean> closeWorldInternal(String templateId, boolean saveToTemplate) {
        String activeTemplate = templateId;
        World world = findBuildWorld(templateId);
        if (world == null) {
            removeBuildWorldEntries(activeTemplate);
            Path leftover = resolveBuildPath(activeTemplate);
            if (saveToTemplate || !Files.isDirectory(leftover)) {
                return CompletableFuture.completedFuture(true);
            }
            return CompletableFuture.supplyAsync(
                    () -> deleteDirectoryWithRetry(leftover), copyExecutor);
        }

        if (!teleportPlayersToHub(world, !saveToTemplate)) {
            plugin.getLogger().severe(
                    "建築ワールド '" + activeTemplate + "' のプレイヤー退避に失敗したため、"
                            + "アンロードを中止しました。");
            return CompletableFuture.completedFuture(false);
        }

        // 保存する場合はディレクトリへ書き出してからコピーする。
        // 破棄する場合はディレクトリごと削除するため、アンロード時のセーブは行わない。
        if (!Bukkit.unloadWorld(world, saveToTemplate)) {
            plugin.getLogger().severe(
                    "建築ワールド '" + activeTemplate + "' のアンロードに失敗したため、"
                            + (saveToTemplate ? "保存" : "破棄") + "を中止しました。");
            return CompletableFuture.completedFuture(false);
        }

        removeBuildWorldEntries(activeTemplate);
        Path buildDir = resolveBuildPath(activeTemplate);

        if (!saveToTemplate) {
            CompletableFuture<Boolean> discarded = CompletableFuture.supplyAsync(
                    () -> deleteDirectoryWithRetry(buildDir), copyExecutor);
            discarded.whenComplete((success, error) -> {
                if (error != null) {
                    plugin.getLogger().severe(
                            "建築ワールドのディレクトリ削除に失敗しました: " + activeTemplate
                                    + " (" + rootMessage(error) + ")");
                } else {
                    plugin.getLogger().info(
                            "建築ワールドの変更を破棄しました: template=" + activeTemplate);
                }
            });
            return discarded;
        }

        if (!Files.isDirectory(buildDir)) {
            plugin.getLogger().warning(
                    "建築ワールドのディレクトリが見つからないため、テンプレートへの保存をスキップしました: "
                            + activeTemplate);
            return CompletableFuture.completedFuture(true);
        }

        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager != null) {
            layerManager.lockTemplate(activeTemplate);
        }

        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            overwriteTemplate(activeTemplate, buildDir);
            deleteDirectoryWithRetry(buildDir);
            return true;
        }, copyExecutor);

        result.whenComplete((saved, error) -> {
            if (layerManager != null) {
                layerManager.unlockTemplate(activeTemplate);
            }
            if (error != null) {
                plugin.getLogger().severe(
                        "建築ワールドのテンプレート保存に失敗しました: " + activeTemplate
                                + " (" + rootMessage(error) + ")");
            } else {
                plugin.getLogger().info(
                        "建築ワールドを保存しました: template=" + activeTemplate
                                + ", path=" + buildDir);
            }
        });
        return result;
    }

    /**
     * テンプレートを建築ワールド用ディレクトリへ複製する（非同期スレッド用）。
     *
     * @param templateId  テンプレート ID
     * @param templateDir テンプレートディレクトリ
     * @param buildDir    建築ワールドの複製先
     * @return 複製先ディレクトリ
     * @throws CompletionException 複製に失敗した場合
     */
    private Path prepareBuildDirectory(String templateId, Path templateDir, Path buildDir) {
        try {
            // 前回異常終了した際の残骸は、テンプレートから作り直す。
            deleteDirectoryOrThrow(buildDir);
            Files.createDirectories(buildRoot);
            copyDirectory(templateDir, buildDir);
            return buildDir;
        } catch (IOException e) {
            deleteDirectoryQuietly(buildDir);
            throw new CompletionException(new IllegalStateException(
                    "建築テンプレートの複製に失敗しました: " + templateId, e));
        }
    }

    /**
     * 複製済みディレクトリから建築ワールドをロードし、プレイヤーを入場させる。
     *
     * @param templateId テンプレート ID
     * @param buildDir   建築ワールドのディレクトリ
     * @param player     入場させるプレイヤー（{@code null} 可）
     * @return ロード完了時にワールドを返す future
     */
    @SuppressWarnings("removal")
    private CompletableFuture<World> loadWorldOnMainThread(String templateId, Path buildDir, Player player) {
        CompletableFuture<World> result = new CompletableFuture<>();

        Runnable task = () -> {
            if (!plugin.isEnabled()) {
                deleteDirectoryQuietly(buildDir);
                result.completeExceptionally(
                        new IllegalStateException("プラグインが停止中のため建築ワールドを開けません。"));
                return;
            }

            World existing = findBuildWorld(templateId);
            if (existing != null) {
                debugEnterBuildWorld(player, existing);
                enterBuildWorld(templateId, player, existing, false);
                result.complete(existing);
                return;
            }

            World world;
            // ロード済みでない場合は、これからテンプレートから新規生成されるワールド。
            // 新規生成かどうかはログ出力用に保持する（転送遅延は常に 20 tick）。
            boolean newlyCreated;
            try {
                world = findLoadedWorld(templateId);
                newlyCreated = world == null;
                if (newlyCreated) {
                    WorldCreator creator = WorldCreator.ofKey(keyOf(templateId))
                            .environment(World.Environment.NORMAL)
                            .generateStructures(false)
                            // 建築ワールドのスポーン位置を常時ロードし、テレポート先の
                            // チャンク未ロードによる転送失敗を防ぐ。
                            // Paper 26.3 では boolean 版が無いため TriState を使用する。
                            .keepSpawnLoaded(TriState.TRUE);
                    world = Bukkit.createWorld(creator);
                }
            } catch (RuntimeException e) {
                deleteDirectoryQuietly(buildDir);
                result.completeExceptionally(new IllegalStateException(
                        "建築ワールドのロードに失敗しました: " + templateId, e));
                return;
            }

            if (world == null) {
                deleteDirectoryQuietly(buildDir);
                result.completeExceptionally(new IllegalStateException(
                        "建築ワールドのロードに失敗しました: " + templateId));
                return;
            }

            // 生成直後はスポーン位置のチャンクが未ロードのことがあるため、明示的にロードする。
            loadDestinationChunk(world.getSpawnLocation());

            buildWorlds.put(templateId, world);
            buildWorldLastActive.put(templateId, System.currentTimeMillis());
            emptySince.remove(templateId);

            // プレイヤー転送に失敗してもワールドのロード自体は成功扱いにし、
            // 管理者が手動で移動できるようエラーメッセージとフォールバックを行う。
            try {
                debugEnterBuildWorld(player, world);
                enterBuildWorld(templateId, player, world, newlyCreated);
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "建築ワールドへのプレイヤー転送に失敗しました: template=" + templateId
                                + " (" + rootMessage(e) + ")");
                notifyTeleportFailure(player);
                fallbackTeleport(player, world);
            }

            plugin.getLogger().info(
                    "建築ワールドを開きました: template=" + templateId
                            + ", world=" + world.getName() + ", path=" + buildDir);
            result.complete(world);
        };

        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException e) {
            deleteDirectoryQuietly(buildDir);
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * プレイヤーをクリエイティブモードにして建築ワールドへ転送する。
     * <p>
     * テンプレート ID をキーに {@link #buildWorlds} へ登録し、最終アクティブ時刻を更新する。
     *
     * @param templateId   テンプレート ID
     * @param player       対象プレイヤー（{@code null} またはオフラインの場合は何もしない）
     * @param world        建築ワールド
     * @param newlyCreated {@code true} の場合はワールドを新規生成した直後（ログ用。
     *                     転送遅延は常に 20 tick）
     */
    private void enterBuildWorld(String templateId, Player player, World world, boolean newlyCreated) {
        if (templateId == null || world == null) {
            return;
        }

        buildWorlds.put(templateId, world);
        buildWorldLastActive.put(templateId, System.currentTimeMillis());
        emptySince.remove(templateId);

        if (player == null || !player.isOnline()) {
            return;
        }

        Location spawn = buildSpawn(world, player);
        plugin.getLogger().info("[TransferDebug] player=" + player.getName()
                + ", destination=" + spawn
                + ", world=" + (spawn.getWorld() != null ? spawn.getWorld().getName() : "null")
                + ", newlyCreated=" + newlyCreated);

        // 建築ワールドへ入る直前のゲームモードを記録し、退出時に戻す。
        // 既に建築ワールド内にいる場合は、最初の入場時の値を保持する。
        if (!isBuildWorld(player.getWorld())) {
            previousGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        }
        player.setGameMode(GameMode.CREATIVE);

        // 新規生成かどうかに関わらず、チャンク準備とクライアント側の同期を待つため
        // 常に 20 tick（1 秒）待ってから転送する。
        long delay = 20L;
        player.sendActionBar(TRANSFER_PREPARING);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            // 転送先（建築ワールドのスポーン）のチャンクが未ロードだと teleport が
            // success=false を返すため、転送直前に明示的にロードする。
            loadDestinationChunk(spawn);
            if (player.teleport(spawn)) {
                return;
            }

            plugin.getLogger().warning(
                    "建築ワールドへの同期転送に失敗したため、非同期転送へフォールバックします: "
                            + player.getName() + " -> " + world.getName());
            player.teleportAsync(spawn).whenComplete((success, throwable) -> {
                if (throwable == null && Boolean.TRUE.equals(success)) {
                    return;
                }
                plugin.getLogger().warning(
                        "プレイヤー '" + player.getName() + "' を建築ワールドへ転送できませんでした: "
                                + world.getName()
                                + " (" + (throwable != null ? rootMessage(throwable) : "success=false") + ")");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    notifyTeleportFailure(player);
                    // 転送に失敗した場合は建築ワールドへ入場していないため、ゲームモードを戻す。
                    restoreGameMode(player);
                });
            });
        }, delay);
    }

    /**
     * 建築ワールド入場前に、対象プレイヤー・ワールド・スポーン座標をログへ出力する。
     */
    private void debugEnterBuildWorld(Player player, World world) {
        if (player == null || world == null) {
            return;
        }
        plugin.getLogger().info("[BuildDebug] enterBuildWorld: player=" + player.getName()
                + ", world=" + world.getName() + ", spawn=" + buildSpawn(world, player));
    }

    /**
     * 建築ワールドのスポーン地点を、プレイヤーの向きを引き継いで生成する。
     */
    private static Location buildSpawn(World world, Player player) {
        Location spawn = world.getSpawnLocation().toBlockLocation();
        spawn.add(0.5, 0.0, 0.5);
        if (player != null) {
            spawn.setYaw(player.getLocation().getYaw());
            spawn.setPitch(player.getLocation().getPitch());
        }
        return spawn;
    }

    /**
     * テレポート先のチャンクを明示的にロードする。
     * <p>
     * 未ロードチャンクへのテレポートは {@code success=false} となるため、
     * 転送直前に呼び出して転送先を確実に用意する。失敗は警告に留める。
     *
     * @param location テレポート先
     */
    private void loadDestinationChunk(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        try {
            location.getChunk().load();
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "テレポート先のチャンクをロードできませんでした: " + location
                            + " (" + rootMessage(e) + ")");
        }
    }

    /**
     * 転送に失敗したことをプレイヤーへ通知する。
     */
    private static void notifyTeleportFailure(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendMessage(Component.text(
                "建築ワールドへの転送に失敗しました。管理者に連絡してください。",
                NamedTextColor.RED));
    }

    /**
     * 建築ワールドのスポーンへ同期テレポートする（最終フォールバック）。
     */
    private void fallbackTeleport(Player player, World world) {
        if (player == null || world == null || !player.isOnline()) {
            return;
        }
        Location spawn = buildSpawn(world, player);
        loadDestinationChunk(spawn);
        if (!player.teleport(spawn)) {
            plugin.getLogger().warning(
                    "建築ワールドへのフォールバック転送にも失敗しました: player=" + player.getName()
                            + ", world=" + world.getName() + ", spawn=" + spawn);
        }
    }


    /**
     * 建築ワールドの内容でテンプレートを上書き保存する。
     * <p>
     * 一時ディレクトリへ複製してから既存テンプレートと入れ替えるため、
     * コピー中に失敗しても元のテンプレートは破壊されない。
     *
     * @param templateId テンプレート ID
     * @param buildDir   建築ワールドのディレクトリ
     * @throws CompletionException 保存に失敗した場合
     */
    private void overwriteTemplate(String templateId, Path buildDir) {
        Path templateDir = resolveTemplatePath(templateId);
        Path tempDir = templateRoot.resolve("." + templateId + TEMP_SUFFIX);
        Path backupDir = templateRoot.resolve("." + templateId + BACKUP_SUFFIX);

        try {
            Files.createDirectories(templateRoot);
            deleteDirectoryOrThrow(tempDir);
            deleteDirectoryOrThrow(backupDir);

            copyDirectory(buildDir, tempDir);

            boolean hadTemplate = Files.isDirectory(templateDir);
            if (hadTemplate) {
                Files.move(templateDir, backupDir);
            }
            try {
                Files.move(tempDir, templateDir);
            } catch (IOException moveError) {
                if (hadTemplate && Files.isDirectory(backupDir)) {
                    try {
                        Files.move(backupDir, templateDir);
                    } catch (IOException restoreError) {
                        moveError.addSuppressed(restoreError);
                    }
                }
                throw moveError;
            }

            try {
                deleteDirectoryOrThrow(backupDir);
            } catch (IOException cleanupError) {
                // 保存自体は成功しているため、バックアップの残骸は警告に留める。
                plugin.getLogger().warning(
                        "テンプレートのバックアップ削除に失敗しました: " + backupDir
                                + " (" + cleanupError.getMessage() + ")");
            }
        } catch (IOException e) {
            deleteDirectoryQuietly(tempDir);
            throw new CompletionException(new IllegalStateException(
                    "テンプレートへの保存に失敗しました: " + templateId, e));
        }
    }

    /**
     * ワールドファイルを再帰的に複製する。
     * <p>
     * {@link LayerManager#WORLD_COPY_WHITELIST}（{@code level.dat} と {@code region/}）だけを
     * コピーするホワイトリスト方式。{@code session.lock} / {@code uid.dat} /
     * {@code level.dat_old} などのメタデータは複製せず、テンプレート保存時に
     * 新しいワールド UID が Paper 側で生成される。
     *
     * @param source 複製元
     * @param target 複製先
     * @throws IOException 複製に失敗した場合
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(source)) {
            paths = stream.toList();
        }

        for (Path path : paths) {
            Path relative = source.relativize(path);
            if (relative.getNameCount() == 0) {
                continue;
            }
            String topLevel = relative.getName(0).toString();
            if (!LayerManager.WORLD_COPY_WHITELIST.contains(topLevel)) {
                continue;
            }

            Path destination = target.resolve(relative);
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
                continue;
            }

            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * ディレクトリを再帰的に削除する。存在しない場合は何もしない。
     *
     * @param directory 削除対象
     * @throws IOException 削除に失敗した場合
     */
    private void deleteDirectoryOrThrow(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        List<Path> paths;
        try (Stream<Path> stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }

        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * ディレクトリを再帰的に削除する。数回再試行し、失敗しても例外を投げない。
     *
     * @param directory 削除対象
     * @return 削除に成功した場合は {@code true}
     */
    private boolean deleteDirectoryWithRetry(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return true;
        }

        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            try {
                deleteDirectoryOrThrow(directory);
                return true;
            } catch (IOException e) {
                if (attempt == DELETE_ATTEMPTS) {
                    plugin.getLogger().warning(
                            "建築ワールドのディレクトリ削除に失敗しました: " + directory
                                    + " (" + e.getMessage() + ")");
                    return false;
                }
                try {
                    Thread.sleep(DELETE_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * ディレクトリを再帰的に削除し、失敗は警告に留める。
     *
     * @param directory 削除対象
     */
    private void deleteDirectoryQuietly(Path directory) {
        deleteDirectoryWithRetry(directory);
    }

    /**
     * 建築ワールド内のプレイヤーを Hub のスポーンへ退避させる。
     *
     * @param world          建築ワールド
     * @param forceSurvival  {@code true} の場合は入場前のモードに関わらずサバイバルへ戻す
     * @return 全員の退避に成功した場合は {@code true}
     */
    private boolean teleportPlayersToHub(World world, boolean forceSurvival) {
        List<Player> players = new ArrayList<>(world.getPlayers());
        if (players.isEmpty()) {
            return true;
        }

        World hub = resolveHubWorld();
        World targetWorld = hub != null ? hub : resolveFallbackWorld();
        if (targetWorld == null || targetWorld.equals(world)) {
            plugin.getLogger().severe(
                    "退避先の Hub ワールドが見つかりません: " + world.getName());
            return false;
        }

        Location target = targetWorld.getSpawnLocation().toBlockLocation();
        target.add(0.5, 0.0, 0.5);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        // 退避先（Hub スポーン）のチャンクが未ロードだとテレポートが失敗するため、事前にロードする。
        loadDestinationChunk(target);

        boolean evacuated = true;
        for (Player player : players) {
            if (!teleportOutOfBuildWorld(player, target, forceSurvival)) {
                evacuated = false;
            }
        }
        return evacuated;
    }

    /**
     * 建築ワールドから Hub へ退避させ、ゲームモードを元に戻す。
     *
     * @param player         対象プレイヤー
     * @param target         Hub のスポーン地点
     * @param forceSurvival  {@code true} の場合はサバイバルへ戻す
     * @return 退避に成功した場合は {@code true}
     */
    private boolean teleportOutOfBuildWorld(Player player, Location target, boolean forceSurvival) {
        // テレポートイベントの退出検知より先に元のモードを確定させ、
        // 退出後にサバイバルへ上書きされないようにする。
        GameMode previous = previousGameModes.remove(player.getUniqueId());
        GameMode targetMode = forceSurvival || previous == null
                ? GameMode.SURVIVAL
                : previous;
        if (!player.teleport(target)) {
            if (previous != null) {
                previousGameModes.putIfAbsent(player.getUniqueId(), previous);
            }
            plugin.getLogger().warning(
                    "プレイヤー '" + player.getName() + "' を Hub へ退避できませんでした。");
            return false;
        }
        applyGameMode(player, targetMode);
        return true;
    }

    /**
     * ゲームモードを設定する。失敗は警告に留める。
     *
     * @param player 対象プレイヤー
     * @param mode   設定するゲームモード
     */
    private void applyGameMode(Player player, GameMode mode) {
        if (player.getGameMode() == mode) {
            return;
        }
        try {
            player.setGameMode(mode);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "プレイヤー '" + player.getName() + "' のゲームモードを変更できませんでした: "
                            + rootMessage(e));
        }
    }

    /**
     * Hub レイヤーを取得する。未ロードの場合は生成を試みる。
     *
     * @return Hub ワールド。取得できない場合は {@code null}
     */
    private World resolveHubWorld() {
        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            return null;
        }
        World hub = layerManager.getWorld(Zeloria.HUB_INSTANCE_ID);
        if (hub != null) {
            return hub;
        }
        try {
            return layerManager.createLayer(Zeloria.HUB_TEMPLATE_ID, Zeloria.HUB_INSTANCE_ID);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "Hub レイヤーの生成に失敗しました: " + rootMessage(e));
            return null;
        }
    }

    /**
     * 建築ワールド以外の退避先ワールドを取得する。
     *
     * @return 退避先ワールド。見つからない場合は {@code null}
     */
    private World resolveFallbackWorld() {
        for (World world : Bukkit.getWorlds()) {
            if (!isBuildWorld(world)) {
                return world;
            }
        }
        return null;
    }

    /**
     * 現在開いているテンプレート ID を 1 つ取得する（後方互換用）。
     *
     * @return テンプレート ID。開いていない場合は {@code null}
     */
    private String primaryTemplateId() {
        for (String templateId : collectOpenTemplateIds()) {
            return templateId;
        }
        return null;
    }

    /**
     * 開いている建築ワールドのテンプレート ID 一覧を取得する。
     * <p>
     * ロード済みの建築ワールドを {@link #buildWorlds} へ取り込み、
     * 既にアンロードされたワールドのエントリを除去する。
     *
     * @return テンプレート ID の一覧（大文字小文字を無視して昇順）
     */
    private Set<String> collectOpenTemplateIds() {
        for (World world : Bukkit.getWorlds()) {
            if (!isBuildWorld(world)) {
                continue;
            }
            String templateId = templateIdOf(world);
            if (templateId != null) {
                buildWorlds.put(templateId, world);
            }
        }
        buildWorlds.entrySet().removeIf(entry -> !isWorldLoaded(entry.getValue()));

        Set<String> templateIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        templateIds.addAll(buildWorlds.keySet());
        return templateIds;
    }

    /**
     * 指定テンプレートの建築ワールドを取得する。
     * <p>
     * Map に無い場合は Bukkit にロード済みの建築ワールドを検索して状態を復元する。
     *
     * @param templateId テンプレート ID
     * @return 建築ワールド。無い場合は {@code null}
     */
    private World findBuildWorld(String templateId) {
        if (templateId == null) {
            return null;
        }

        World world = buildWorlds.get(templateId);
        if (isWorldLoaded(world)) {
            return world;
        }

        World loaded = findLoadedWorld(templateId);
        if (loaded != null) {
            buildWorlds.put(templateId, loaded);
            return loaded;
        }

        if (world != null) {
            buildWorlds.remove(templateId, world);
        }
        return null;
    }

    /**
     * ワールドが現在ロード済みか判定する。
     *
     * @param world 判定対象
     * @return ロード済みの場合は {@code true}
     */
    private static boolean isWorldLoaded(World world) {
        return world != null && Bukkit.getWorlds().contains(world);
    }

    /**
     * 建築ワールドのキー名からテンプレート ID を取得する。
     *
     * @param world 建築ワールド
     * @return テンプレート ID。建築ワールドでない場合は {@code null}
     */
    private static String templateIdOf(World world) {
        if (world == null || !BUILD_NAMESPACE.equals(world.getKey().getNamespace())) {
            return null;
        }
        String key = world.getKey().getKey();
        if (!key.endsWith(BUILD_WORLD_SUFFIX)) {
            return null;
        }
        String templateId = key.substring(0, key.length() - BUILD_WORLD_SUFFIX.length());
        return templateId.isBlank() ? null : templateId;
    }

    /**
     * 指定テンプレートの建築ワールドの管理情報を削除する。
     *
     * @param templateId テンプレート ID
     */
    private void removeBuildWorldEntries(String templateId) {
        if (templateId == null) {
            return;
        }
        buildWorlds.remove(templateId);
        buildWorldOwners.remove(templateId);
        buildWorldLastActive.remove(templateId);
        emptySince.remove(templateId);
    }

    /**
     * 建築ワールドの操作を開始する。
     *
     * @param pendingState 保存待ち／アンロード待ちの状態フラグ。不要な場合は {@code null}
     * @return 開始できた場合は {@code true}
     */
    private boolean beginOperation(AtomicBoolean pendingState) {
        if (busy.get() || savePending.get() || unloadPending.get()) {
            return false;
        }
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        if (pendingState != null) {
            pendingState.set(true);
        }
        return true;
    }

    /**
     * 建築ワールドの操作を終了する。
     *
     * @param pendingState 保存待ち／アンロード待ちの状態フラグ。不要な場合は {@code null}
     */
    private void finishOperation(AtomicBoolean pendingState) {
        if (pendingState != null) {
            pendingState.set(false);
        }
        busy.set(false);
    }

    /**
     * テンプレートディレクトリのパスを解決する。
     *
     * @param templateId テンプレート ID
     * @return テンプレートディレクトリ
     * @throws IllegalArgumentException パスが {@code WorldData} 配下から外れる場合
     */
    private Path resolveTemplatePath(String templateId) {
        Path path = templateRoot.resolve(templateId).toAbsolutePath().normalize();
        if (!templateRoot.equals(path.getParent())) {
            throw new IllegalArgumentException("不正なテンプレート ID です: " + templateId);
        }
        return path;
    }

    /**
     * 建築ワールドディレクトリのパスを解決する。
     *
     * @param templateId テンプレート ID
     * @return 建築ワールドディレクトリ
     * @throws IllegalArgumentException パスが建築ワールド用ディレクトリから外れる場合
     */
    private Path resolveBuildPath(String templateId) {
        Path path = buildRoot.resolve(templateId + BUILD_WORLD_SUFFIX).toAbsolutePath().normalize();
        if (!buildRoot.equals(path.getParent())) {
            throw new IllegalArgumentException("不正なテンプレート ID です: " + templateId);
        }
        return path;
    }

    /**
     * 建築ワールドの配置先の親ディレクトリを解決する。
     * <p>
     * Paper 26.3 では {@link World#getWorldPath()} がディメンション名まで含む場合があるため、
     * {@link LayerManager} と同様にワールドコンテナとメインワールドのレベル名から組み立てる。
     *
     * @return {@code <worldContainer>/<mainWorldLevel>/dimensions/minecraft}
     */
    private Path resolveBuildRoot() {
        Path container = plugin.getServer().getWorldContainer()
                .toPath()
                .toAbsolutePath()
                .normalize();

        String levelName = DEFAULT_MAIN_WORLD_NAME;
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            World mainWorld = worlds.get(0);
            String name = mainWorld.getName();
            if (isUsableLevelName(name)) {
                levelName = name;
            } else {
                // Paper 26.3 では getName() がディメンション名まで含む場合があるため、
                // ワールドパスからコンテナ相対の先頭セグメントを採用する。
                try {
                    Path relative = container.relativize(
                            mainWorld.getWorldPath().toAbsolutePath().normalize());
                    if (relative.getNameCount() > 0 && !relative.startsWith("..")) {
                        String first = relative.getName(0).toString();
                        if (isUsableLevelName(first)) {
                            levelName = first;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 取得できない場合は既定値を使用する。
                }
            }
        }

        return container.resolve(levelName)
                .resolve("dimensions")
                .resolve(BUILD_NAMESPACE)
                .normalize();
    }

    /**
     * メインワールドのレベル名として使用できる値か判定する。
     *
     * @param name 判定対象の名前
     * @return 空白でなくパス区切りを含まない場合は {@code true}
     */
    private static boolean isUsableLevelName(String name) {
        return name != null && !name.isBlank()
                && !name.contains("/") && !name.contains("\\")
                && !name.equals(".") && !name.equals("..");
    }

    /**
     * テンプレート ID を検証する。
     *
     * @param templateId テンプレート ID
     * @param field      エラーメッセージ用の項目名
     * @throws IllegalArgumentException 不正な ID の場合
     */
    private void validateId(String templateId, String field) {
        if (templateId == null || !ID_PATTERN.matcher(templateId).matches()) {
            throw new IllegalArgumentException(
                    field + " は英数字・ハイフン・アンダースコアのみ使用できます: " + templateId);
        }
    }

    /**
     * メインスレッドからの呼び出しであることを検証する。
     *
     * @throws IllegalStateException メインスレッド以外から呼び出された場合
     */
    private void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("建築モードの操作はメインスレッドから実行してください。");
        }
    }

    /**
     * 例外チェーンから最も深いメッセージを取得する。
     *
     * @param throwable 例外
     * @return メッセージ（存在しない場合はクラス名）
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}

