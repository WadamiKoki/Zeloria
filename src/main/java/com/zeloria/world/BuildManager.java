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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * チャンネル 1 専用の建築モードを管理するクラス。
 * <p>
 * 建築ワールドはテンプレート（{@code WorldData/<templateId>}、既定:
 * {@code ZeloriaServer/WorldData/<templateId>}）を
 * {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build/<templateId>}
 * へ複製し、{@code WorldCreator.ofKey(new NamespacedKey("zeloria_build", templateId))}
 * でロードする。
 * <p>
 * 保存時は建築ワールドを {@code Bukkit.unloadWorld(world, true)} でセーブしたうえで、
 * ディメンション側の内容をテンプレートへ上書きコピーし、ディメンション側の
 * ディレクトリを削除する。
 * <p>
 * ファイルコピーは専用スレッドで行い、ワールド操作はすべてメインスレッドで行う。
 * 建築モードは本クラスが {@code null}（チャンネル 2 以降）の場合は利用できない。
 */
public class BuildManager {

    /** 建築ワールドのネームスペース。 */
    public static final String BUILD_NAMESPACE = "zeloria_build";

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
     * {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build}
     */
    private final Path buildRoot;

    /** 現在開いている建築ワールド。開いていない場合は {@code null}。 */
    private volatile World currentBuildWorld;

    /** 現在開いている建築テンプレート ID。開いていない場合は {@code null}。 */
    private volatile String currentTemplateId;

    /** 建築ワールド入場前のゲームモード（プレイヤー UUID → 元のゲームモード）。 */
    private final Map<UUID, GameMode> previousGameModes = new ConcurrentHashMap<>();

    /** 複製・保存処理の多重実行を防ぐフラグ。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** 保存処理の実行中フラグ。完了するまで次の建築コマンドを拒否する。 */
    private final AtomicBoolean savePending = new AtomicBoolean(false);

    /** アンロード（破棄）処理の実行中フラグ。完了するまで次の建築コマンドを拒否する。 */
    private final AtomicBoolean unloadPending = new AtomicBoolean(false);

    /** 建築ワールドにプレイヤーがいなくなった時刻（ミリ秒）。0 は未計測を表す。 */
    private volatile long emptySince = 0L;

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
     * 建築ワールドは常に最大 1 つだけとする。既に同じテンプレートのワールドが
     * 開かれている場合は複製せず、プレイヤーをそのワールドへ参戦させる。
     * 別のテンプレートが開かれている場合は保存を促してキャンセルする。
     *
     * @param templateId テンプレート ID（{@code WorldData} 配下のディレクトリ名）
     * @param player     入場させるプレイヤー（{@code null} の場合はワールドのロードのみ）
     * @return ロード完了時にワールドを返す future（失敗時は例外で完了）
     */
    public synchronized CompletableFuture<World> openBuildWorldAsync(String templateId, Player player) {
        assertMainThread();
        validateId(templateId, "templateId");

        World existing = resolveCurrentWorld();
        if (existing != null) {
            String existingTemplate = currentTemplateId != null
                    ? currentTemplateId
                    : existing.getKey().getKey();
            currentBuildWorld = existing;
            currentTemplateId = existingTemplate;
            if (!templateId.equals(existingTemplate)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "既に別の建築ワールド（" + existingTemplate + "）が開かれています。"
                                + "先に /admin build save で保存してください"));
            }
            enterBuildWorld(player, existing, false);
            return CompletableFuture.completedFuture(existing);
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

        CompletableFuture<World> result = CompletableFuture
                .supplyAsync(() -> prepareBuildDirectory(templateId, templateDir, buildDir),
                        copyExecutor)
                .thenCompose(dir -> loadWorldOnMainThread(templateId, dir, player));

        result.whenComplete((world, error) -> busy.set(false));
        return result;
    }

    /**
     * 現在開いている建築ワールドを保存し、アンロードする。
     * <p>
     * ワールド内のプレイヤーは Hub のスポーンへ退避させ、アンロード後に
     * ディメンション側の内容をテンプレートへ上書きコピーしてから
     * ディメンション側のディレクトリを削除する。
     *
     * @return 保存に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> saveActiveAsync() {
        assertMainThread();
        if (resolveCurrentWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!beginOperation(savePending)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "建築ワールドの準備中です。完了してから再実行してください。"));
        }
        CompletableFuture<Boolean> result = closeActiveInternal(true);
        result.whenComplete((saved, error) -> finishOperation(savePending));
        return result;
    }

    /**
     * 現在開いている建築ワールドを保存せずにアンロードし、ディレクトリを削除する。
     *
     * @return 破棄に成功した場合は {@code true} で完了する future
     */
    public synchronized CompletableFuture<Boolean> unsaveActiveAsync() {
        assertMainThread();
        if (resolveCurrentWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!beginOperation(unloadPending)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "建築ワールドの準備中です。完了してから再実行してください。"));
        }
        CompletableFuture<Boolean> result = closeActiveInternal(false);
        result.whenComplete((discarded, error) -> finishOperation(unloadPending));
        return result;
    }

    /**
     * 建築ワールドを保存して終了する（プラグイン停止時に呼び出す）。
     * <p>
     * 停止処理では非同期処理の完了を待てないため、ファイルコピーも同期実行する。
     */
    public synchronized void shutdown() {
        String templateId = currentTemplateId;
        World world = resolveCurrentWorld();
        if (templateId == null && world != null) {
            templateId = world.getKey().getKey();
        }
        if (templateId == null) {
            resetCurrentWorld();
            copyExecutor.shutdownNow();
            return;
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
        resetCurrentWorld();

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

        copyExecutor.shutdownNow();
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
     *
     * @return 建築中のテンプレート ID。開いていない場合は {@code null}
     */
    public String getActiveTemplateId() {
        return currentTemplateId;
    }

    /**
     * 現在開いている建築テンプレート ID を取得する。
     *
     * @return 建築中のテンプレート ID。開いていない場合は {@code null}
     */
    public String getCurrentTemplateId() {
        return currentTemplateId;
    }

    /**
     * 現在開いている建築ワールドを取得する。
     *
     * @return 建築ワールド。開いていない場合は {@code null}
     */
    public World getCurrentBuildWorld() {
        return resolveCurrentWorld();
    }

    /**
     * 建築ワールドにいるプレイヤー名の一覧を取得する。
     *
     * @return プレイヤー名の一覧（名前順）。建築ワールドが無い場合は空リスト
     */
    public List<String> getActivePlayerNames() {
        World world = resolveCurrentWorld();
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
        World world = resolveCurrentWorld();
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
        assertMainThread();
        World world = resolveCurrentWorld();
        if (world == null) {
            return false;
        }
        enterBuildWorld(player, world, false);
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
        World world = resolveCurrentWorld();
        if (world == null) {
            emptySince = 0L;
            return;
        }
        if (isBusy()) {
            return;
        }
        if (!world.getPlayers().isEmpty()) {
            emptySince = 0L;
            return;
        }
        if (emptySince == 0L) {
            emptySince = now;
            return;
        }
        if (now - emptySince < AUTO_SAVE_IDLE_MILLIS) {
            return;
        }

        String templateId = currentTemplateId;
        if (templateId == null) {
            templateId = world.getKey().getKey();
        }
        String savedTemplate = templateId;
        emptySince = 0L;

        saveActiveAsync().whenComplete((saved, error) -> {
            if (error != null) {
                plugin.getLogger().severe(
                        "建築用ワールド " + savedTemplate + " の自動保存に失敗しました: "
                                + rootMessage(error));
            } else if (Boolean.TRUE.equals(saved)) {
                plugin.getLogger().info(
                        "建築用ワールド " + savedTemplate + " を自動保存してアンロードしました");
            } else {
                plugin.getLogger().warning(
                        "建築用ワールド " + savedTemplate + " の自動保存を完了できませんでした。");
            }
        });
    }

    /**
     * ロード済みの建築ワールドを取得する。
     *
     * @param templateId テンプレート ID
     * @return ロード済みのワールド。存在しない場合は {@code null}
     */
    public World getLoadedWorld(String templateId) {
        if (templateId == null || !templateId.equals(currentTemplateId)) {
            return null;
        }
        return resolveCurrentWorld();
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
     * {@code zeloria_build} 名前空間を共有するレイヤーインスタンス
     * （{@code zeloria_build:<instanceId>}）は建築ワールドではないため除外する。
     *
     * @param world 判定対象のワールド
     * @return 建築ワールドの場合は {@code true}
     */
    public static boolean isBuildWorld(World world) {
        if (world == null || !BUILD_NAMESPACE.equals(world.getKey().getNamespace())) {
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
     * テレポート制限では、同じ名前空間を共有するレイヤーインスタンスへの転送を
     * 妨げないよう、この判定で建築ワールドを限定する。
     *
     * @param world 判定対象のワールド
     * @return 現在の建築ワールドの場合は {@code true}
     */
    public boolean isActiveBuildWorld(World world) {
        if (world == null) {
            return false;
        }
        World current = resolveCurrentWorld();
        return current != null && current.equals(world);
    }

    /**
     * テンプレート ID から建築ワールドのキーを生成する。
     *
     * @param templateId テンプレート ID
     * @return {@code zeloria_build:<templateId>} のキー
     */
    public static NamespacedKey keyOf(String templateId) {
        return new NamespacedKey(BUILD_NAMESPACE, Objects.requireNonNull(templateId, "templateId"));
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
     * 現在の建築ワールドを Hub へ退避・アンロードし、必要に応じて保存する。
     * <p>
     * アンロードまではメインスレッドで同期的に実行し、ファイルコピーと
     * ディレクトリ削除のみ非同期で行う。保存中は対象テンプレートを
     * {@link LayerManager} 側でロックし、新規レイヤー生成を抑止する。
     *
     * @param saveToTemplate テンプレートへ保存する場合は {@code true}。
     *                       {@code false} の場合は保存せずにディレクトリを削除する
     * @return 処理に成功した場合は {@code true} で完了する future
     */
    private CompletableFuture<Boolean> closeActiveInternal(boolean saveToTemplate) {
        String templateId = currentTemplateId;
        World world = resolveCurrentWorld();
        if (world == null) {
            resetCurrentWorld();
            if (templateId == null) {
                return CompletableFuture.completedFuture(true);
            }
            Path leftover = resolveBuildPath(templateId);
            if (saveToTemplate || !Files.isDirectory(leftover)) {
                return CompletableFuture.completedFuture(true);
            }
            return CompletableFuture.supplyAsync(
                    () -> deleteDirectoryWithRetry(leftover), copyExecutor);
        }

        if (templateId == null) {
            templateId = world.getKey().getKey();
        }
        String activeTemplate = templateId;

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

        resetCurrentWorld();
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

            World existing = resolveCurrentWorld();
            if (existing != null) {
                if (!templateId.equals(currentTemplateId)) {
                    deleteDirectoryQuietly(buildDir);
                    result.completeExceptionally(new IllegalStateException(
                            "既に別の建築ワールド（" + currentTemplateId + "）が開かれています。"
                                    + "先に /admin build save で保存してください"));
                    return;
                }
                debugEnterBuildWorld(player, existing);
                enterBuildWorld(player, existing, false);
                result.complete(existing);
                return;
            }

            World world;
            // ロード済みでない場合は、これからテンプレートから新規生成されるワールド。
            // 新規生成時はチャンク準備に時間がかかるため、転送遅延を長めに取る。
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

            currentBuildWorld = world;
            currentTemplateId = templateId;
            emptySince = 0L;

            // プレイヤー転送に失敗してもワールドのロード自体は成功扱いにし、
            // 管理者が手動で移動できるようエラーメッセージとフォールバックを行う。
            try {
                debugEnterBuildWorld(player, world);
                enterBuildWorld(player, world, newlyCreated);
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
     *
     * @param player       対象プレイヤー（{@code null} またはオフラインの場合は何もしない）
     * @param world        建築ワールド
     * @param newlyCreated {@code true} の場合はワールドを新規生成した直後
     *                     （チャンク準備待ちの遅延を長めに取る）
     */
    private void enterBuildWorld(Player player, World world, boolean newlyCreated) {
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

        // 新規生成直後はチャンクの準備に時間がかかり、テレポートが false で失敗しやすい。
        // そのため新規生成時は 20 tick（1 秒）、既にロード済みなら 5 tick（0.25 秒）待つ。
        long delay = newlyCreated ? 20L : 5L;
        player.sendActionBar(TRANSFER_PREPARING);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
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
     * 現在の建築ワールドを取得する。
     * <p>
     * フィールドが未設定の場合は Bukkit にロード済みの建築ワールドを検索し、
     * 状態を復元する。
     *
     * @return 建築ワールド。無い場合は {@code null}
     */
    private World resolveCurrentWorld() {
        World world = currentBuildWorld;
        if (world != null) {
            return world;
        }

        String templateId = currentTemplateId;
        if (templateId != null) {
            World loaded = findLoadedWorld(templateId);
            if (loaded != null) {
                currentBuildWorld = loaded;
                return loaded;
            }
        }

        World scanned = findAnyLoadedBuildWorld();
        if (scanned != null) {
            currentBuildWorld = scanned;
            currentTemplateId = scanned.getKey().getKey();
        }
        return scanned;
    }

    /**
     * ロード済みの建築ワールドを 1 つ検索する。
     *
     * @return 建築ワールド。無い場合は {@code null}
     */
    private static World findAnyLoadedBuildWorld() {
        for (World world : Bukkit.getWorlds()) {
            if (isBuildWorld(world)) {
                return world;
            }
        }
        return null;
    }

    /**
     * 現在の建築ワールドの状態をクリアする。
     */
    private void resetCurrentWorld() {
        currentBuildWorld = null;
        currentTemplateId = null;
        emptySince = 0L;
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
        Path path = buildRoot.resolve(templateId).toAbsolutePath().normalize();
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
     * @return {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build}
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

