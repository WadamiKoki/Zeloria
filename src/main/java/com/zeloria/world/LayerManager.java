package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.mob.SpawnerManager;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * テンプレートワールドからレイヤーインスタンスを生成・管理するクラス。
 * <p>
 * テンプレートは {@code WorldData/{templateId}}（既定: {@code ZeloriaServer/WorldData/{templateId}}）に配置し、
 * 生成されたインスタンスは {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build/{instanceId}}
 * （例: {@code ZeloriaServer/world/dimensions/zeloria_build/hub_instance_1}）に展開される。
 * <p>
 * {@link WorldCreator} に渡すキーは {@code zeloria_build:<instanceId>}
 * （例: {@code zeloria_build:hub_instance_1}）とし、{@link World.Environment#CUSTOM} +
 * {@code customEnvironmentKey} でロードする。これによりテンプレートの {@code level.dat} が持つ
 * {@code minecraft:overworld} のディメンションキーと衝突せず、
 * 「duplicate of another world」でロードを拒否されない。
 * <p>
 * コピー対象は {@link #WORLD_COPY_WHITELIST}（{@code level.dat} と {@code region/}）のみとし、
 * UID・ロック・プレイヤーデータなどのメタデータは引き継がない。
 * <p>
 * ワールド操作を伴うため、すべてのメソッドはメインスレッドから呼び出すこと。
 */
public class LayerManager {

    /**
     * インスタンス／テンプレート ID に許可する文字種。
     * パストラバーサル防止のためパス区切り文字は許可しない。
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * ワールドとしてコピーする対象（これだけをコピーする）。
     * <p>
     * ホワイトリスト方式にすることで、{@code session.lock} / {@code uid.dat} /
     * {@code level.dat_old} / {@code playerdata} / {@code players} / {@code entities} /
     * {@code data} / {@code datapacks} / {@code paper-world.yml} などは一切コピーされない。
     * 結果として Paper がインスタンス用の新しいワールド UID を自動生成する。
     * <p>
     * {@link BuildManager} のテンプレート保存処理からも共有参照される。
     */
    public static final Set<String> WORLD_COPY_WHITELIST = Set.of(
            "level.dat",
            "region"
    );

    /**
     * メインワールドのレベル名が取得できなかった場合に使用する既定値。
     */
    private static final String DEFAULT_MAIN_WORLD_NAME = "world";

    /**
     * クリーンアップで削除してはならない標準ディメンションディレクトリ名（小文字）。
     * これらはバニラのディメンションデータを保持するため、誤削除すると
     * メインワールドの地形が失われる。
     */
    private static final Set<String> PROTECTED_DIMENSION_DIRECTORIES = Set.of(
            "overworld",
            "the_nether",
            "the_end"
    );

    /**
     * ディレクトリ削除の最大試行回数。
     * Windows 等でセッションロック解放が遅延する場合に備えて数回だけ再試行する。
     */
    private static final int DELETE_ATTEMPTS = 3;

    /**
     * ディレクトリ削除の再試行間隔（ミリ秒）。
     */
    private static final long DELETE_RETRY_DELAY_MS = 50L;

    /**
     * レイヤーディレクトリが本プラグインの生成物であることを示す所有権マーカーのファイル名。
     * <p>
     * レイヤー生成時に必ず書き込み、削除時はこのファイルが存在するディレクトリだけを対象とする。
     * 他プラグインやサーバー管理者が配置したワールドを誤って削除しないための安全柵であり、
     * マーカーが無いディレクトリは（たとえ ID 形式が一致しても）削除しない。
     */
    private static final String OWNERSHIP_MARKER_FILE = ".zeloria_layer";

    /**
     * 所有権マーカーの種別識別子（ファイル内容の人間向け参考情報）。
     */
    private static final String OWNERSHIP_MARKER_TYPE = "zeloria-layer";

    private final Zeloria plugin;

    /**
     * テンプレート配置先: {@code <worldDataFolder>}（既定: {@code ZeloriaServer/WorldData}）
     */
    private final Path templateRoot;

    /**
     * 生成インスタンス配置先の親ディレクトリ:
     * {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build}
     */
    private final Path instanceRoot;

    /**
     * 読み込み済みのインスタンスワールド。
     */
    private final Map<String, World> loadedWorlds = new ConcurrentHashMap<>();

    /**
     * 新規生成直後のインスタンス ID → 生成時刻（エポックミリ秒）。
     * <p>
     * 生成直後はチャンクの準備が整っておらず、すぐにテレポートすると失敗する。
     * 転送側（{@link #isNewlyCreated(String)}）はこの情報を参照し、
     * 新規生成されたインスタンスへの転送だけ遅延を長めに取る。
     */
    private final Map<String, Long> newlyCreatedInstances = new ConcurrentHashMap<>();

    /**
     * {@link #isNewlyCreated(String)} が {@code true} を返す期間（ミリ秒）。
     * 生成からこの時間を過ぎたインスタンスは「既存」として扱う。
     */
    private static final long NEWLY_CREATED_WINDOW_MILLIS = 60_000L;

    /**
     * インスタンス ID → 生成元テンプレート ID の対応。
     * 入場演出やポータルの転送先解決に使用する。
     */
    private final Map<String, String> instanceTemplates = new ConcurrentHashMap<>();

    /**
     * 複製中のインスタンス（インスタンス ID → 完了時にワールドを返す future）。
     * <p>
     * ファイル複製は非同期スレッドで行うため、完了までは {@link #isCreating(String)} が
     * {@code true} を返し、レイヤー入場やポータル転送は待機状態となる。
     */
    private final Map<String, CompletableFuture<World>> pendingInstances = new ConcurrentHashMap<>();

    /**
     * 複製中インスタンスの生成元テンプレート ID（インスタンス ID → テンプレート ID）。
     */
    private final Map<String, String> pendingTemplates = new ConcurrentHashMap<>();

    /**
     * 建築モードの保存処理中でロックされているテンプレート ID。
     * <p>
     * ロック中はテンプレートの内容が置き換わっている可能性があるため、
     * 新規レイヤーの生成（テンプレート複製）を拒否する。
     */
    private final Set<String> lockedTemplates = ConcurrentHashMap.newKeySet();

    /**
     * テンプレート複製専用のエグゼキュータ。
     * <p>
     * ワールド操作（{@link Bukkit#createWorld(WorldCreator)}）はメインスレッドで行うため、
     * ここではファイル I/O のみを実行する。
     */
    private final ExecutorService copyExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Zeloria-LayerCopy");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * {@link #getOrCreateInstance(String)} でインスタンス ID を採番する際の上限試行回数。
     */
    private static final int MAX_INSTANCE_ATTEMPTS = 100;

    /**
     * LayerData が見つからない場合に使用する既定の最大インスタンス数。
     */
    private static final int DEFAULT_MAX_INSTANCES = 1;

    public LayerManager(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        File worldDataFolder = plugin.getWorldDataFolder();
        if (worldDataFolder == null) {
            throw new IllegalStateException("world-data-path が初期化されていません。");
        }
        this.templateRoot = worldDataFolder.toPath()
                .toAbsolutePath()
                .normalize();
        this.instanceRoot = resolveDimensionsRoot();

        try {
            Files.createDirectories(this.templateRoot);
        } catch (IOException e) {
            plugin.getLogger().warning("テンプレートディレクトリの作成に失敗しました: " + e.getMessage());
        }
    }

    /**
     * Paper のカスタムディメンション配置先
     * {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build} を解決する。
     * <p>
     * {@link World#getWorldPath()} は Paper 26.3 では
     * {@code <worldContainer>/world/overworld} のようにディメンション名まで含むことがある。
     * その値へ {@code dimensions/zeloria_build} を連結すると
     * {@code world/overworld/dimensions/zeloria_build} という不正なパスになるため、
     * このメソッドでは<b>ワールドコンテナから直接</b>構造を組み立てる。
     * <p>
     * レベル名はメインワールド名の先頭セグメントのみを使用し、取得できない場合は
     * {@code world} にフォールバックする。
     */
    private static Path resolveDimensionsRoot() {
        Path container = Bukkit.getWorldContainer()
                .toPath()
                .toAbsolutePath()
                .normalize();

        return container
                .resolve(mainWorldLevelName(container))
                .resolve("dimensions")
                // ワールドキーの名前空間と一致させる（Paper は dimensions/<namespace>/<key> を参照する）。
                .resolve(BuildManager.BUILD_NAMESPACE)
                .normalize();
    }

    /**
     * メインワールドのレベル名（＝ワールドコンテナ直下のディレクトリ名）を取得する。
     * <p>
     * {@link World#getName()} を優先し、スラッシュを含むなど不正な場合のみ
     * {@link World#getWorldPath()} からコンテナ相対の先頭セグメントを採用する。
     *
     * @param container ワールドコンテナ
     * @return レベル名（例: {@code world}）
     */
    private static String mainWorldLevelName(Path container) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return DEFAULT_MAIN_WORLD_NAME;
        }

        World mainWorld = worlds.getFirst();
        String name = mainWorld.getName();
        if (isUsableLevelName(name)) {
            return name;
        }

        try {
            Path relative = container.relativize(mainWorld.getWorldPath().toAbsolutePath().normalize());
            if (relative.getNameCount() > 0 && !relative.startsWith("..")) {
                String first = relative.getName(0).toString();
                if (isUsableLevelName(first)) {
                    return first;
                }
            }
        } catch (RuntimeException e) {
            // 取得できない実装では既定値へフォールバックする。
        }

        return DEFAULT_MAIN_WORLD_NAME;
    }

    private static boolean isUsableLevelName(String name) {
        return name != null && !name.isBlank() && !name.contains("/") && !name.contains("\\");
    }

    /**
     * インスタンス ID から Bukkit のワールド名（ディメンションキー文字列）を導出する。
     * <p>
     * レイヤーインスタンスは {@code zeloria_build} 名前空間のカスタムディメンションとして
     * ロードするため、名前は {@code zeloria_build:<instanceId>} となる。
     * 実体は {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build/<instanceId>}。
     * 名前にスラッシュを含めると追加の移設処理が走りパス不整合を招くため、
     * ID 部分にはパス区切り文字を許可しない。
     *
     * @param instanceId インスタンス ID
     * @return {@code zeloria_build:<instanceId>}
     */
    public static String worldName(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        // ディメンションキーの path は小文字のみ許可されるため正規化する。
        return BuildManager.BUILD_NAMESPACE + ":" + instanceId.toLowerCase(Locale.ROOT);
    }

    /**
     * 指定 ID がレイヤーインスタンス ID の形式（{@code <templateId>_instance_<番号>}）か判定する。
     * <p>
     * レイヤーインスタンスと建築ワールドは同じ {@code zeloria_build} 名前空間を共有するため、
     * 建築ワールド側（{@link BuildManager#isBuildWorld(World)}）がインスタンスを
     * 建築ワールドと誤認しないようにするための識別子として使用する。
     *
     * @param id 判定対象の ID
     * @return レイヤーインスタンス ID の形式なら {@code true}
     */
    public static boolean isInstanceId(String id) {
        return id != null && id.matches(".+_instance_\\d+");
    }

    /**
     * 指定ディレクトリが本プラグインの生成したレイヤーインスタンスか判定する。
     * <p>
     * 生成時に書き込まれる所有権マーカー（{@link #OWNERSHIP_MARKER_FILE}）の有無で判定する。
     * 建築ワールド（{@code zeloria_build} 名前空間を共有する）と区別するために使用する。
     *
     * @param directory 判定対象ディレクトリ
     * @return レイヤーインスタンスのディレクトリなら {@code true}
     */
    public static boolean isInstanceDirectory(Path directory) {
        return directory != null && Files.isRegularFile(directory.resolve(OWNERSHIP_MARKER_FILE));
    }

    /**
     * テンプレートを複製し、レイヤーインスタンスを生成・ロードする。
     *
     * @param templateId テンプレート ID（{@code WorldData} 配下のディレクトリ名）
     * @param instanceId 生成するインスタンス ID
     *                   （{@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build} 配下のディレクトリ名）
     * @return ロードされたワールド
     * @throws IllegalArgumentException テンプレートが存在しない、または ID が不正な場合
     * @throws IllegalStateException    同一 ID のインスタンスが既に存在する、またはワールドのロードに失敗した場合
     */
    public synchronized World createLayer(String templateId, String instanceId) {
        assertMainThread();

        validateId(templateId, "templateId");
        validateId(instanceId, "instanceId");

        World existing = loadedWorlds.get(instanceId);
        if (existing != null) {
            instanceTemplates.putIfAbsent(instanceId, templateId);
            return existing;
        }

        assertTemplateUnlocked(templateId);

        Path templateDir = resolveTemplatePath(templateId);
        Path instanceDir = prepareInstancePath(instanceId);

        try {
            copyWorldFiles(templateDir, instanceDir);
            // 本プラグインが生成したディレクトリであることを記録する。
            // クリーンアップはこのマーカーが存在するディレクトリのみを削除対象とする。
            writeOwnershipMarker(instanceDir, templateId, instanceId);
        } catch (IOException e) {
            deleteDirectoryQuietly(instanceDir);
            throw new IllegalStateException(
                    "テンプレートの複製または所有権マーカーの作成に失敗しました: " + templateId, e);
        }

        return loadWorld(templateId, instanceId, instanceDir);
    }

    /**
     * テンプレートを複製し、レイヤーインスタンスを非同期で生成・ロードする。
     * <p>
     * ファイルコピー（{@link #copyWorldFiles(Path, Path)}）は専用スレッドで実行し、
     * {@link Bukkit#createWorld(WorldCreator)} を含むワールド操作は
     * {@link Bukkit#getScheduler()} 経由でメインスレッドへ戻してから実行する。
     * これにより大容量テンプレートの複製中もサーバーの Tick を止めない。
     * <p>
     * 同一インスタンス ID の複製が進行中の場合は、新規に開始せず同じ future を返す。
     *
     * @param templateId テンプレート ID
     * @param instanceId 生成するインスタンス ID
     * @return ロード完了時にワールドを返す future（失敗時は {@link IllegalStateException} で完了）
     */
    public synchronized CompletableFuture<World> createLayerAsync(String templateId, String instanceId) {
        assertMainThread();
        validateId(templateId, "templateId");
        validateId(instanceId, "instanceId");

        World existing = loadedWorlds.get(instanceId);
        if (existing != null) {
            instanceTemplates.putIfAbsent(instanceId, templateId);
            return CompletableFuture.completedFuture(existing);
        }

        CompletableFuture<World> pending = pendingInstances.get(instanceId);
        if (pending != null) {
            return pending;
        }

        assertTemplateUnlocked(templateId);

        Path templateDir;
        Path instanceDir;
        try {
            templateDir = resolveTemplatePath(templateId);
            instanceDir = prepareInstancePath(instanceId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<World> future = CompletableFuture
                .supplyAsync(() -> copyTemplate(templateDir, instanceDir, templateId, instanceId), copyExecutor)
                .thenCompose(copiedDir -> createWorldOnMainThread(templateId, instanceId, copiedDir));

        pendingInstances.put(instanceId, future);
        pendingTemplates.put(instanceId, templateId);
        future.whenComplete((world, error) -> {
            pendingInstances.remove(instanceId);
            pendingTemplates.remove(instanceId);
        });
        return future;
    }

    /**
     * テンプレートを複製し、所有権マーカーを書き込む（非同期スレッド用）。
     *
     * @return 複製先ディレクトリ
     * @throws CompletionException 複製に失敗した場合（複製先は削除済み）
     */
    private Path copyTemplate(Path templateDir, Path instanceDir,
                              String templateId, String instanceId) {
        try {
            copyWorldFiles(templateDir, instanceDir);
            // マーカーが無いとクリーンアップ対象外となり残骸が残るため、複製直後に書き込む。
            writeOwnershipMarker(instanceDir, templateId, instanceId);
            return instanceDir;
        } catch (IOException e) {
            deleteDirectoryQuietly(instanceDir);
            throw new CompletionException(new IllegalStateException(
                    "テンプレートの複製または所有権マーカーの作成に失敗しました: " + templateId, e));
        }
    }

    /**
     * ワールド生成をメインスレッドで実行し、結果を future で返す。
     */
    private CompletableFuture<World> createWorldOnMainThread(String templateId, String instanceId,
                                                             Path instanceDir) {
        CompletableFuture<World> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            deleteDirectoryQuietly(instanceDir);
            result.completeExceptionally(
                    new IllegalStateException("プラグインが停止中のためワールドを生成できません。"));
            return result;
        }

        Runnable task = () -> {
            try {
                // 複製完了後にインスタンスディレクトリが消えている場合はロードしない。
                if (!Files.isDirectory(instanceDir)) {
                    result.completeExceptionally(new IllegalStateException(
                            "複製先ディレクトリが存在しないためワールド生成を中止しました: " + instanceDir));
                    return;
                }
                result.complete(loadWorld(templateId, instanceId, instanceDir));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        };

        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException e) {
            deleteDirectoryQuietly(instanceDir);
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * テンプレートディレクトリを解決する。
     *
     * @throws IllegalArgumentException テンプレートが存在しない場合
     */
    private Path resolveTemplatePath(String templateId) {
        Path templateDir = templateRoot.resolve(templateId).normalize();
        if (!templateDir.getParent().equals(templateRoot) || !Files.isDirectory(templateDir)) {
            throw new IllegalArgumentException("テンプレートが見つかりません: " + templateDir);
        }
        return templateDir;
    }

    /**
     * 生成先インスタンスディレクトリを検証して返す。
     *
     * @throws IllegalStateException 同名ワールド／ディレクトリが既に存在する場合
     */
    private Path prepareInstancePath(String instanceId) {
        String bukkitWorldName = LayerManager.worldName(instanceId);
        if (findLoadedInstanceWorld(instanceId) != null) {
            throw new IllegalStateException("ワールド '" + bukkitWorldName + "' は既にロードされています。");
        }

        Path instanceDir = resolveInstancePath(instanceId);
        if (Files.exists(instanceDir)) {
            throw new IllegalStateException("インスタンスディレクトリが既に存在します: " + instanceDir);
        }
        return instanceDir;
    }

    /**
     * 複製済みディレクトリからワールドを生成・ロードする（メインスレッド専用）。
     *
     * @param templateId  テンプレート ID
     * @param instanceId  インスタンス ID
     * @param instanceDir 複製済みインスタンスディレクトリ
     * @return ロードされたワールド
     */
    private World loadWorld(String templateId, String instanceId, Path instanceDir) {
        assertMainThread();

        // 複製中に建築モードの保存が始まった場合、テンプレートの内容が
        // 入れ替わっている可能性があるため、ロード直前に再度検証する。
        if (lockedTemplates.contains(templateId)) {
            deleteDirectoryQuietly(instanceDir);
            throw new IllegalStateException(
                    "テンプレート '" + templateId + "' は建築モードの保存処理中のため使用できません。");
        }

        String bukkitWorldName = LayerManager.worldName(instanceId);
        World world;
        try {
            // ディメンションキーを zeloria_build 名前空間へ変更する。
            // テンプレートの level.dat に含まれる minecraft:overworld のキーと衝突せず、
            // 「duplicate of another world」でロードを拒否されない。
            NamespacedKey dimensionKey = Objects.requireNonNull(
                    NamespacedKey.fromString(bukkitWorldName),
                    "dimensionKey");
            WorldCreator creator = WorldCreator.ofKey(dimensionKey)
                    .environment(World.Environment.CUSTOM)
                    .customEnvironmentKey(dimensionKey)
                    // スポーン位置を常時ロードしない（新規生成時の負荷・競合を避ける）。
                    .keepSpawnLoaded(TriState.FALSE)
                    .generateStructures(false)
                    // 空の ChunkGenerator によりブロック生成を抑止する（Void ワールド）。
                    .generator(new ChunkGenerator() {
                    });

            world = Bukkit.createWorld(creator);
        } catch (RuntimeException e) {
            deleteDirectoryQuietly(instanceDir);
            throw new IllegalStateException("ワールドのロードに失敗しました: " + bukkitWorldName, e);
        }

        if (world == null) {
            deleteDirectoryQuietly(instanceDir);
            throw new IllegalStateException("ワールドのロードに失敗しました: " + bukkitWorldName);
        }

        // 実際にロードされたディレクトリが想定と異なる場合は、以降の運用で不整合が起きるため警告する。
        Path loadedDir = world.getWorldPath().toAbsolutePath().normalize();
        if (!loadedDir.equals(instanceDir)) {
            plugin.getLogger().warning(
                    "ワールドのロード先が想定ディレクトリと一致しません: 期待=" + instanceDir + ", 実際=" + loadedDir);
        }

        applySafetySettings(world);
        // オンメモリキャッシュのキーは常にインスタンス ID で統一する。
        loadedWorlds.put(instanceId, world);
        instanceTemplates.put(instanceId, templateId);
        // 生成直後であることを記録し、転送側がチャンク準備待ちの遅延を取れるようにする。
        newlyCreatedInstances.put(instanceId, System.currentTimeMillis());

        // LayerData に npcs が定義されていれば、ワールドロード直後に配置する。
        // NPC 配置の失敗でレイヤー生成自体を失敗させない。
        LayerNpcService npcService = plugin.getLayerNpcService();
        if (npcService != null) {
            try {
                npcService.spawnFor(world, instanceId, templateId);
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "レイヤー NPC の配置に失敗しました: instance=" + instanceId
                                + " (" + e.getMessage() + ")");
            }
        }

        // LayerData に spawners が定義されていれば、モブの定期スポーンを開始する。
        // スポーン開始の失敗でレイヤー生成自体を失敗させない。
        SpawnerManager spawnerManager = plugin.getSpawnerManager();
        if (spawnerManager != null) {
            try {
                spawnerManager.startFor(world, instanceId, templateId);
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "レイヤーのスポナー開始に失敗しました: instance=" + instanceId
                                + " (" + e.getMessage() + ")");
            }
        }

        plugin.getLogger().info("レイヤーワールドを生成しました: " + bukkitWorldName
                + " (instance=" + instanceId + ", template=" + templateId + ", path=" + loadedDir + ")");
        if (plugin.getLoggerService() != null) {
            plugin.getLoggerService().log(com.zeloria.core.LoggerService.Category.LAYER,
                    "レイヤー生成: instance=" + instanceId + ", template=" + templateId
                            + ", world=" + bukkitWorldName + ", path=" + loadedDir);
        }
        return world;
    }

    /**
     * テンプレート ID から利用可能なインスタンスワールドを取得する。
     * 未生成の場合は新規に生成（{@link #createLayer(String, String)}）する。
     * <p>
     * 共有 Hub は固定インスタンス ID を使用し、それ以外は
     * {@code <templateId>_instance_<n>} 形式で空き番号を採番する。
     * 採番の上限は LayerData の {@code maxInstances}（未設定時は 1）。
     *
     * @param templateId テンプレート ID
     * @return ロード済み、または新規生成されたワールド
     * @throws IllegalArgumentException テンプレートが存在しない、または ID が不正な場合
     * @throws IllegalStateException    インスタンス上限に達した、または生成に失敗した場合
     */
    public synchronized World getOrCreateInstance(String templateId) {
        assertMainThread();
        validateId(templateId, "templateId");

        // 共有 Hub は固定 ID を利用する。
        if (Zeloria.HUB_TEMPLATE_ID.equalsIgnoreCase(templateId)) {
            World hub = getWorld(Zeloria.HUB_INSTANCE_ID);
            if (hub != null) {
                return hub;
            }
            return createLayer(Zeloria.HUB_TEMPLATE_ID, Zeloria.HUB_INSTANCE_ID);
        }

        // 既に同じテンプレートから生成されたインスタンスがあれば再利用する。
        for (Map.Entry<String, String> entry : instanceTemplates.entrySet()) {
            if (!templateId.equalsIgnoreCase(entry.getValue())) {
                continue;
            }
            World world = loadedWorlds.get(entry.getKey());
            if (world != null) {
                return world;
            }
        }

        int maxInstances = resolveMaxInstances(templateId);
        int attempts = Math.min(Math.max(maxInstances, 1), MAX_INSTANCE_ATTEMPTS);

        for (int index = 1; index <= attempts; index++) {
            String instanceId = templateId + "_instance_" + index;
            if (instanceId.length() > 64) {
                throw new IllegalStateException(
                        "テンプレート ID が長すぎるためインスタンス ID を生成できません: " + templateId);
            }
            if (loadedWorlds.containsKey(instanceId) || Files.exists(resolveInstancePath(instanceId))) {
                continue;
            }
            return createLayer(templateId, instanceId);
        }

        throw new IllegalStateException(
                "テンプレート '" + templateId + "' のインスタンス上限 (" + maxInstances + ") に達しました。");
    }

    /**
     * テンプレート ID から利用可能なインスタンスワールドを非同期で取得する。
     * <p>
     * 判定ロジックは {@link #getOrCreateInstance(String)} と同一だが、未生成時の
     * 複製・ワールド生成を {@link #createLayerAsync(String, String)} に委譲する。
     * 既にロード済み、または生成中の場合は該当する future をそのまま返す。
     *
     * @param templateId テンプレート ID
     * @return ワールドを返す future（失敗時は {@link IllegalStateException} で完了）
     */
    public synchronized CompletableFuture<World> getOrCreateInstanceAsync(String templateId) {
        assertMainThread();
        validateId(templateId, "templateId");

        // 共有 Hub は固定 ID を利用する。
        if (Zeloria.HUB_TEMPLATE_ID.equalsIgnoreCase(templateId)) {
            World hub = getWorld(Zeloria.HUB_INSTANCE_ID);
            if (hub != null) {
                return CompletableFuture.completedFuture(hub);
            }
            return createLayerAsync(Zeloria.HUB_TEMPLATE_ID, Zeloria.HUB_INSTANCE_ID);
        }

        // 既に同じテンプレートから生成されたインスタンスがあれば再利用する。
        for (Map.Entry<String, String> entry : instanceTemplates.entrySet()) {
            if (!templateId.equalsIgnoreCase(entry.getValue())) {
                continue;
            }
            World world = loadedWorlds.get(entry.getKey());
            if (world != null) {
                return CompletableFuture.completedFuture(world);
            }
        }

        // 生成中のインスタンスがあれば、その完了を待つ future を共有する。
        for (Map.Entry<String, String> entry : pendingTemplates.entrySet()) {
            if (!templateId.equalsIgnoreCase(entry.getValue())) {
                continue;
            }
            CompletableFuture<World> pending = pendingInstances.get(entry.getKey());
            if (pending != null) {
                return pending;
            }
        }

        int maxInstances = resolveMaxInstances(templateId);
        int attempts = Math.min(Math.max(maxInstances, 1), MAX_INSTANCE_ATTEMPTS);

        for (int index = 1; index <= attempts; index++) {
            String instanceId = templateId + "_instance_" + index;
            if (instanceId.length() > 64) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "テンプレート ID が長すぎるためインスタンス ID を生成できません: " + templateId));
            }
            if (loadedWorlds.containsKey(instanceId)
                    || pendingInstances.containsKey(instanceId)
                    || Files.exists(resolveInstancePath(instanceId))) {
                continue;
            }
            return createLayerAsync(templateId, instanceId);
        }

        return CompletableFuture.failedFuture(new IllegalStateException(
                "テンプレート '" + templateId + "' のインスタンス上限 (" + maxInstances + ") に達しました。"));
    }

    /**
     * 指定インスタンスが複製・生成中かどうかを返す。
     *
     * @param instanceId インスタンス ID
     * @return 複製・生成中なら {@code true}
     */
    public boolean isCreating(String instanceId) {
        return instanceId != null && pendingInstances.containsKey(instanceId);
    }

    /**
     * 指定インスタンスが「新規生成直後」かどうかを返す。
     * <p>
     * 生成直後はテレポート先チャンクの準備が整っておらず転送に失敗しやすいため、
     * 転送側はこのメソッドが {@code true} を返す間だけ遅延を長めに取る。
     * 生成から {@link #NEWLY_CREATED_WINDOW_MILLIS} 経過したインスタンスは
     * 既存扱いとなり {@code false} を返す。
     *
     * @param instanceId インスタンス ID（ワールドキー {@code zeloria_build:<instanceId>} の ID 部分）
     * @return 新規生成直後なら {@code true}
     */
    public boolean isNewlyCreated(String instanceId) {
        if (instanceId == null) {
            return false;
        }
        Long createdAt = newlyCreatedInstances.get(instanceId);
        if (createdAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - createdAt > NEWLY_CREATED_WINDOW_MILLIS) {
            // 期限切れの記録は破棄し、以降は既存インスタンスとして扱う。
            newlyCreatedInstances.remove(instanceId, createdAt);
            return false;
        }
        return true;
    }

    /**
     * テンプレートをロックし、新規レイヤーの生成を抑止する。
     * <p>
     * 建築モードがテンプレートを上書き保存している間、複製処理が
     * 中途半端なテンプレートを読み込まないようにするために使用する。
     *
     * @param templateId テンプレート ID
     */
    public void lockTemplate(String templateId) {
        if (templateId != null) {
            lockedTemplates.add(templateId);
        }
    }

    /**
     * テンプレートのロックを解除する。
     *
     * @param templateId テンプレート ID
     */
    public void unlockTemplate(String templateId) {
        if (templateId != null) {
            lockedTemplates.remove(templateId);
        }
    }

    /**
     * テンプレートがロックされていないことを検証する。
     *
     * @param templateId テンプレート ID
     * @throws IllegalStateException ロック中の場合
     */
    private void assertTemplateUnlocked(String templateId) {
        if (lockedTemplates.contains(templateId)) {
            throw new IllegalStateException(
                    "テンプレート '" + templateId + "' は建築モードの保存処理中のため使用できません。");
        }
    }

    /**
     * 複製・生成中のインスタンス ID を返す（不変ビュー）。
     */
    public Set<String> getPendingInstances() {
        return Set.copyOf(pendingInstances.keySet());
    }

    /**
     * 複製用エグゼキュータを停止する（プラグイン無効化時に呼び出す）。
     */
    public void shutdown() {
        copyExecutor.shutdownNow();
        pendingInstances.clear();
        pendingTemplates.clear();
        newlyCreatedInstances.clear();
    }

    /**
     * LayerData から最大インスタンス数を取得する。未設定の場合は既定値を返す。
     */
    private int resolveMaxInstances(String templateId) {
        LayerConfigService configService = plugin.getLayerConfigService();
        if (configService == null) {
            return DEFAULT_MAX_INSTANCES;
        }
        return configService.findByTemplate(templateId)
                .map(LayerData::getMaxInstances)
                .filter(value -> value > 0)
                .orElse(DEFAULT_MAX_INSTANCES);
    }

    /**
     * インスタンス ID の生成元テンプレート ID を取得する。
     *
     * @param instanceId インスタンス ID（ワールド名）
     * @return テンプレート ID。未登録の場合は {@code null}
     */
    public String getTemplateId(String instanceId) {
        return instanceId == null ? null : instanceTemplates.get(instanceId);
    }

    /**
     * インスタンス ID → テンプレート ID の対応を取得する（不変ビュー）。
     */
    public Map<String, String> getInstanceTemplates() {
        return Map.copyOf(instanceTemplates);
    }

    /**
     * インスタンスワールドをアンロードし、その展開ディレクトリを丸ごと削除する。
     * <p>
     * ワールド内にプレイヤーがいる場合は、メインワールド／ハブ等の別ワールドへ
     * 退避させてからアンロードする（キックは発生しない）。
     * ディレクトリ削除は
     * {@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build/<instanceId>} 直下のみを対象とし、
     * バニラの標準ディメンション（{@code overworld} / {@code the_nether} / {@code the_end}）や
     * インスタンス配置先の外側は決して削除しない。
     * <p>
     * さらに、生成時に書き込まれる所有権マーカー（{@link #OWNERSHIP_MARKER_FILE}）が無い
     * ディレクトリは本プラグインの生成物と断定できないため削除しない。
     * 削除を中止した場合はワールドもアンロードせず {@code false} を返す。
     *
     * @param instanceId インスタンス ID
     * @return アンロードおよび削除に成功した場合は {@code true}
     */
    public synchronized boolean unloadAndDeleteLayer(String instanceId) {
        assertMainThread();
        validateId(instanceId, "instanceId");

        // 複製中（ワールド未生成）のインスタンスは削除対象としない。
        // 中途半端に削除すると複製処理と競合するため、完了後の再試行に委ねる。
        if (pendingInstances.containsKey(instanceId)) {
            plugin.getLogger().warning("レイヤー '" + instanceId + "' は準備中のため削除できません。");
            return false;
        }

        // ワールドアンロード前にスポーンタスクを停止し、削除中のモブ生成を防ぐ。
        // （削除中止時はスポナーを止めたままにしないよう、実際のアンロード直前で行う）

        if (isProtectedDirectoryName(instanceId)) {
            plugin.getLogger().severe("保護対象ディレクトリはレイヤーとして削除できません: " + instanceId);
            return false;
        }

        Path instanceDir = resolveInstancePath(instanceId);
        String bukkitWorldName = LayerManager.worldName(instanceId);

        World world = loadedWorlds.get(instanceId);
        if (world == null) {
            // キャッシュに無くても Bukkit 側でロード済みなら拾う（キーは zeloria_build:<instanceId>）。
            world = findLoadedInstanceWorld(instanceId);
        }

        // プライマリワールド（メインワールド）は誤って削除しないよう保護する。
        if (world != null && !Bukkit.getWorlds().isEmpty() && world.equals(Bukkit.getWorlds().getFirst())) {
            plugin.getLogger().severe("メインワールドはレイヤーとして削除できません: " + bukkitWorldName);
            return false;
        }

        // マイグレーション等で想定と異なる場所に展開された場合に備え、実パスも控えておく。
        Path loadedPath = world == null ? null : worldDirectoryOf(world);

        // 想定パス → 実際にロードされていたパスの順で、存在する方を削除対象にする。
        Path deleteTarget = Files.exists(instanceDir) ? instanceDir
                : (loadedPath != null && Files.exists(loadedPath) ? loadedPath : instanceDir);

        // 所有権マーカーが無いディレクトリは、他プラグインやサーバー管理者が配置した
        // ワールドである可能性があるため削除しない（アンロードも行わず安全側に倒す）。
        if (Files.isDirectory(deleteTarget) && !hasOwnershipMarker(deleteTarget)) {
            plugin.getLogger().severe("所有権マーカー (" + OWNERSHIP_MARKER_FILE
                    + ") が無いためレイヤーの削除を中止しました: " + deleteTarget);
            if (plugin.getLoggerService() != null) {
                plugin.getLoggerService().log(com.zeloria.core.LoggerService.Category.LAYER,
                        "レイヤー削除中止（所有権マーカー無し）: instance=" + instanceId
                                + ", path=" + deleteTarget);
            }
            return false;
        }

        if (world != null) {
            if (!evacuatePlayers(world)) {
                plugin.getLogger().severe(
                        "レイヤー '" + instanceId + "' のプレイヤー退避に失敗したため、アンロードを中止しました。");
                return false;
            }

            // 退避後もプレイヤーが残っている場合は、クライアント側の不整合を避けるため削除しない。
            if (!world.getPlayers().isEmpty()) {
                plugin.getLogger().severe(
                        "レイヤー '" + instanceId + "' にプレイヤーが残っているため、削除をスキップしました: "
                                + world.getPlayers().size() + " 人");
                return false;
            }

            // アンロード直前でスポーン判定を停止し、削除中のモブ生成を防ぐ。
            SpawnerManager spawnerManager = plugin.getSpawnerManager();
            if (spawnerManager != null) {
                spawnerManager.stopFor(instanceId);
            }

            // ディレクトリ削除前の最終状態を保存しつつアンロードする（Hub も同じ経路で削除する）。
            if (!Bukkit.unloadWorld(world, true)) {
                plugin.getLogger().severe("レイヤーワールドのアンロードに失敗しました: " + bukkitWorldName);
                // ワールドが残るため、停止したスポーン判定を再開しておく。
                if (spawnerManager != null) {
                    spawnerManager.startFor(world, instanceId, instanceTemplates.get(instanceId));
                }
                return false;
            }
        }

        loadedWorlds.remove(instanceId);
        instanceTemplates.remove(instanceId);
        newlyCreatedInstances.remove(instanceId);

        if (!Files.exists(deleteTarget)) {
            // ワールドが未ロードかつディレクトリも無い場合は、既に削除済みとして成功扱いにする。
            plugin.getLogger().info("レイヤーディレクトリは既に存在しません: " + instanceDir);
            return true;
        }

        // インスタンス配置先の直下のみを削除対象とする（plugins 等の誤削除を防ぐ）。
        if (!deleteInstanceDirectory(deleteTarget)) {
            plugin.getLogger().severe("レイヤーディレクトリの削除に失敗しました: " + deleteTarget);
            return false;
        }

        plugin.getLogger().info("レイヤーワールドを削除しました: " + bukkitWorldName + " (path=" + deleteTarget + ")");
        if (plugin.getLoggerService() != null) {
            plugin.getLoggerService().log(com.zeloria.core.LoggerService.Category.LAYER,
                    "レイヤー削除: instance=" + instanceId + ", world=" + bukkitWorldName
                            + ", path=" + deleteTarget);
        }
        return true;
    }

    /**
     * ワールドの実ディレクトリを取得する。取得できない場合は {@code null}。
     */
    private static Path worldDirectoryOf(World world) {
        try {
            Path path = world.getWorldPath().toAbsolutePath().normalize();
            return path.startsWith(Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize()) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 読み込み済みのインスタンスワールドを取得する。
     *
     * @param instanceId インスタンス ID
     * @return ロード済みのワールド。存在しない場合は {@code null}
     */
    public World getWorld(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        return loadedWorlds.get(instanceId);
    }

    /**
     * 指定したインスタンスが読み込まれているか判定する。
     */
    public boolean isLoaded(String instanceId) {
        return instanceId != null && loadedWorlds.containsKey(instanceId);
    }

    /**
     * 読み込み済みのインスタンスワールド一覧を取得する（不変ビュー）。
     */
    public Map<String, World> getLoadedWorlds() {
        return Map.copyOf(loadedWorlds);
    }

    /**
     * 読み込み済みのすべてのインスタンスをアンロードし、ディレクトリを削除する。
     * プラグイン停止時のクリーンアップに使用する。
     *
     * @return 削除に成功したインスタンス数
     */
    public synchronized int unloadAllAndDelete() {
        int deleted = 0;
        List<String> targets = new ArrayList<>(loadedWorlds.keySet());
        // Hub を含む全レイヤーを削除対象とする（Hub は起動時に WorldData/hub から再生成される）。
        plugin.getLogger().info(
                "全レイヤーワールドの削除を開始します (対象: " + targets.size() + " 件, Hub含む)");
        for (String instanceId : targets) {
            if (unloadAndDeleteLayer(instanceId)) {
                deleted++;
            }
        }
        plugin.getLogger().info(
                "全レイヤーワールドの削除が完了しました (削除: " + deleted + " 件, Hub含む)");
        return deleted;
    }

    /**
     * ディメンション配置先に残存するテンポラリインスタンスを走査し、
     * アンロードおよびディレクトリ削除を行う。
     * <p>
     * 起動時（前回停止時に削除しきれなかった残骸の掃除）と
     * 終了時（アンロード漏れの掃除）の双方から呼び出すことを想定している。
     * <p>
     * 以下のディレクトリは削除対象から除外する。
     * <ul>
     *     <li>{@code overworld} / {@code the_nether} / {@code the_end}（バニラの標準ディメンション）</li>
     *     <li>インスタンス ID の形式（英数字・ハイフン・アンダースコア）に一致しないもの</li>
     *     <li>インスタンス配置先の直下に無いもの</li>
     *     <li>所有権マーカー（{@link #OWNERSHIP_MARKER_FILE}）を持たないもの
     *         （他プラグインやサーバー管理者のワールドを誤削除しないための安全柵）</li>
     * </ul>
     *
     * @return 削除したインスタンス数
     */
    public synchronized int cleanupOrphanedInstances() {
        assertMainThread();

        if (!Files.isDirectory(instanceRoot)) {
            plugin.getLogger().info(
                    "インスタンス配置先が存在しないため、クリーンアップをスキップしました: " + instanceRoot);
            return 0;
        }

        List<Path> candidates;
        try (var stream = Files.list(instanceRoot)) {
            candidates = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            plugin.getLogger().severe(
                    "インスタンス配置先の走査に失敗しました: " + instanceRoot + " (" + e.getMessage() + ")");
            return 0;
        }

        if (candidates.isEmpty()) {
            plugin.getLogger().info("クリーンアップ対象のインスタンスはありませんでした: " + instanceRoot);
            return 0;
        }

        int deleted = 0;
        for (Path candidate : candidates) {
            Path fileName = candidate.getFileName();
            if (fileName == null) {
                continue;
            }
            String instanceId = fileName.toString();

            if (isProtectedDirectoryName(instanceId)) {
                continue;
            }
            if (!ID_PATTERN.matcher(instanceId).matches()) {
                plugin.getLogger().warning(
                        "インスタンス ID の形式ではないためクリーンアップ対象から除外しました: " + candidate);
                continue;
            }

            // 所有権マーカーが無いディレクトリは本プラグインの生成物と断定できないため削除しない。
            // （他プラグインのカスタムディメンションや手動配置ワールドの巻き込みを防ぐ）
            if (!hasOwnershipMarker(candidate)) {
                plugin.getLogger().info("所有権マーカー (" + OWNERSHIP_MARKER_FILE
                        + ") が無いためクリーンアップ対象から除外しました: " + candidate);
                continue;
            }

            boolean loaded = isLoaded(instanceId) || findLoadedInstanceWorld(instanceId) != null;
            if (loaded) {
                // ロード中のワールドは退避・アンロードを伴う通常の削除経路に委譲する。
                if (unloadAndDeleteLayer(instanceId)) {
                    deleted++;
                }
                continue;
            }

            if (deleteInstanceDirectory(candidate)) {
                plugin.getLogger().info("残存していたインスタンスディレクトリを削除しました: " + candidate);
                deleted++;
            }
        }

        plugin.getLogger().info(
                "インスタンスクリーンアップが完了しました (削除: " + deleted + " 件, 対象: " + instanceRoot + ")");
        return deleted;
    }

    /* ------------------------------------------------------------------
     * 所有権マーカー
     * ------------------------------------------------------------------ */

    /**
     * レイヤーディレクトリへ所有権マーカーを書き込む。
     * <p>
     * マーカーの「存在」自体を本プラグインの生成物である証として扱うため、内容は
     * 障害調査用の人間向け参考情報にとどめる（読み取り・パースは行わない）。
     * 既に同名ファイルがある場合は上書きする。
     *
     * @param instanceDir マーカーを書き込むレイヤーディレクトリ
     * @param templateId  生成元テンプレート ID
     * @param instanceId  インスタンス ID
     * @throws IOException ディレクトリが存在しない、または書き込みに失敗した場合
     */
    private void writeOwnershipMarker(Path instanceDir, String templateId, String instanceId)
            throws IOException {
        String content = String.join(System.lineSeparator(),
                "# Zeloria が生成したレイヤーワールドであることを示す所有権マーカーです。",
                "# このファイルを削除すると自動クリーンアップの対象外になります。",
                "type=" + OWNERSHIP_MARKER_TYPE,
                "instance=" + instanceId,
                "template=" + templateId,
                "created=" + Instant.now(),
                "");
        Files.writeString(instanceDir.resolve(OWNERSHIP_MARKER_FILE), content, StandardCharsets.UTF_8);
    }

    /**
     * ディレクトリが本プラグインの生成物か（所有権マーカーを持つか）判定する。
     *
     * @param directory 判定対象ディレクトリ
     * @return 所有権マーカーが通常ファイルとして存在する場合は {@code true}
     */
    private static boolean hasOwnershipMarker(Path directory) {
        return directory != null && Files.isRegularFile(directory.resolve(OWNERSHIP_MARKER_FILE));
    }

    /**
     * ロード済みのすべてのレイヤーからプレイヤーをメインワールドのスポーンへ退避させる。
     * <p>
     * 安全停止処理で、レイヤーをアンロードする前に呼び出すことを想定している。
     * テレポートは同期実行し、戻り値の時点でレイヤー内にプレイヤーが残っていないことを保証する。
     *
     * @return すべてのプレイヤーの退避に成功した場合は {@code true}
     */
    public synchronized boolean evacuateAllPlayersToMainWorld() {
        assertMainThread();

        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            plugin.getLogger().severe("メインワールドが存在しないため、プレイヤーを退避できません。");
            return false;
        }

        World mainWorld = worlds.getFirst();
        Location spawn = mainWorld.getSpawnLocation().clone();

        boolean success = true;
        for (World world : new ArrayList<>(loadedWorlds.values())) {
            if (world.equals(mainWorld)) {
                continue;
            }
            success &= evacuatePlayersTo(world, spawn);
        }
        return success;
    }

    /**
     * テンプレートのワールドファイルをインスタンスへ複製する。
     * <p>
     * {@link #WORLD_COPY_WHITELIST} に含まれる項目（{@code level.dat} と {@code region/}）だけを
     * コピーするホワイトリスト方式。{@code session.lock} / {@code uid.dat} /
     * {@code level.dat_old} などのメタデータやプレイヤーデータは一切コピーしない。
     * 個別ファイルのコピー失敗は警告に留め、複製全体は継続する。
     *
     * @param source 複製元（テンプレートディレクトリ）
     * @param target 複製先（インスタンスディレクトリ）
     * @throws IOException 複製元の走査に失敗した場合
     */
    private void copyWorldFiles(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(path -> {
                Path relative = source.relativize(path);
                if (relative.getNameCount() == 0) {
                    return;
                }
                String topLevel = relative.getName(0).toString();
                if (!WORLD_COPY_WHITELIST.contains(topLevel)) {
                    return;
                }
                Path targetPath = target.resolve(relative);
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning(
                            "コピー失敗: " + path + " -> " + targetPath + " (" + e.getMessage() + ")");
                }
            });
        }
    }

    /**
     * インスタンス配置先の直下にあるディレクトリのみを削除する。
     * <p>
     * 万一 {@code instanceRoot} 外のパスが渡された場合でも、誤って
     * サーバーディレクトリ（{@code plugins} 等）を削除しないよう二重に検証する。
     *
     * @param directory 削除対象ディレクトリ
     * @return 削除に成功した（または既に存在しない）場合は {@code true}
     */
    private boolean deleteInstanceDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!instanceRoot.equals(normalized.getParent())) {
            plugin.getLogger().severe("インスタンス配置先の外側は削除できません: " + normalized);
            return false;
        }
        return deleteDirectoryWithRetry(normalized);
    }

    /**
     * ディレクトリを再帰的に削除する。ロック解放待ちを考慮し、数回だけ再試行する。
     *
     * @return 存在しない、または削除に成功した場合は {@code true}
     */
    private boolean deleteDirectory(Path directory) {
        return deleteDirectoryWithRetry(directory);
    }

    private boolean deleteDirectoryWithRetry(Path directory) {
        IOException lastError = null;

        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            try {
                deleteDirectoryOrThrow(directory);
                return true;
            } catch (IOException e) {
                lastError = e;
                if (attempt < DELETE_ATTEMPTS && !sleepBeforeRetry()) {
                    break;
                }
            }
        }

        plugin.getLogger().severe("ディレクトリの削除に失敗しました: " + directory
                + (lastError == null ? "" : " (" + lastError.getMessage() + ")"));
        return false;
    }

    /**
     * 削除の再試行前の待機。割り込み時は再試行を打ち切る。
     *
     * @return 継続してよい場合は {@code true}
     */
    private static boolean sleepBeforeRetry() {
        try {
            Thread.sleep(DELETE_RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void deleteDirectoryOrThrow(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        List<Path> paths;
        try (var stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private void deleteDirectoryQuietly(Path directory) {
        deleteDirectory(directory);
    }

    /**
     * 削除禁止ディレクトリか判定する。バニラの標準ディメンションとメインワールド名を保護する。
     */
    private static boolean isProtectedDirectoryName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        if (PROTECTED_DIMENSION_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }

        List<World> worlds = Bukkit.getWorlds();
        return !worlds.isEmpty() && worlds.getFirst().getName().equalsIgnoreCase(name);
    }

    /**
     * ワールド内のプレイヤーを別ワールドへ退避させる。
     *
     * @return 退避に成功した（ワールド内にプレイヤーが残っていない）場合は {@code true}
     */
    private boolean evacuatePlayers(World world) {
        if (world.getPlayers().isEmpty()) {
            return true;
        }

        World fallback = findFallbackWorld(world);
        if (fallback == null) {
            plugin.getLogger().severe(
                    "プレイヤーの退避先ワールドが見つかりません: " + world.getName());
            return false;
        }

        return evacuatePlayersTo(world, fallback.getSpawnLocation().clone());
    }

    /**
     * ワールド内のプレイヤーを指定地点へ同期テレポートさせる。
     *
     * @return 退避に成功した（ワールド内にプレイヤーが残っていない）場合は {@code true}
     */
    private boolean evacuatePlayersTo(World world, Location destination) {
        List<Player> players = new ArrayList<>(world.getPlayers());
        if (players.isEmpty()) {
            return true;
        }

        for (Player player : players) {
            try {
                Location respawn = player.getRespawnLocation();
                if (respawn != null && world.equals(respawn.getWorld())) {
                    player.setRespawnLocation(destination, true);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "プレイヤー '" + player.getName() + "' のリスポーン地点の更新に失敗しました: " + e.getMessage());
            }

            player.eject();
            if (!player.teleport(destination)) {
                plugin.getLogger().severe(
                        "プレイヤー '" + player.getName() + "' を退避できませんでした。");
                return false;
            }
        }

        return world.getPlayers().isEmpty();
    }

    /**
     * 退避先ワールドを決定する。メインワールドを優先し、それが対象ワールドの場合は
     * 他のロード済みワールドを使用する。
     */
    private World findFallbackWorld(World excluded) {
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            World mainWorld = worlds.getFirst();
            if (!mainWorld.equals(excluded)) {
                return mainWorld;
            }
        }
        for (World world : worlds) {
            if (!world.equals(excluded)) {
                return world;
            }
        }
        return null;
    }

    /**
     * ロード直後のワールドに安全設定を適用する。
     * <p>
     * {@code World#setKeepSpawnInMemory(boolean)} は Paper 26.3 で非推奨かつ無効化されたため使用しない。
     * ゲームルールは 26.3 で推奨される {@link GameRules} の定数を使用する。
     */
    private void applySafetySettings(World world) {
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
    }

    /**
     * 指定インスタンス ID のロード済みワールドを Bukkit から検索する。
     * <p>
     * レイヤーインスタンスは {@code zeloria_build:<instanceId>} キーでロードされるため、
     * 名前文字列ではなくディメンションキーで照合する。
     *
     * @param instanceId インスタンス ID
     * @return ロード済みのワールド。存在しない場合は {@code null}
     */
    private static World findLoadedInstanceWorld(String instanceId) {
        NamespacedKey key = NamespacedKey.fromString(worldName(instanceId));
        if (key == null) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            if (key.equals(world.getKey())) {
                return world;
            }
        }
        return null;
    }

    /**
     * インスタンス ID から展開先ディレクトリ
     * （{@code <worldContainer>/<mainWorldLevel>/dimensions/zeloria_build/<instanceId>}）を解決する。
     * <p>
     * テンプレートのコピー先・存在チェック・削除対象はいずれもこのパスを参照する。
     * ワールドキーの名前空間（{@code zeloria_build}）と一致させることで、
     * Paper がコピー済みのワールドデータをそのまま読み込む。
     *
     * @param instanceId インスタンス ID
     * @return インスタンスディレクトリ
     * @throws IllegalArgumentException ID がディレクトリ名として不正な場合
     */
    private Path resolveInstancePath(String instanceId) {
        Path path = instanceRoot.resolve(instanceId).normalize();
        if (!path.getParent().equals(instanceRoot)) {
            throw new IllegalArgumentException("不正なインスタンス ID です: " + instanceId);
        }
        return path;
    }

    private void validateId(String id, String fieldName) {
        Objects.requireNonNull(id, fieldName);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " は英数字・ハイフン・アンダースコアのみ使用できます（1〜64 文字）: " + id);
        }
    }

    private void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("LayerManager のワールド操作はメインスレッドから実行してください。");
        }
    }
}
