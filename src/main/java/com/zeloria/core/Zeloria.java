package com.zeloria.core;

import com.zeloria.command.AdminCommand;
import com.zeloria.command.MenuCommand;
import com.zeloria.combat.CombatService;
import com.zeloria.core.YamlLoader;
import com.zeloria.core.DatabaseManager;
import com.zeloria.core.DatabaseService;
import com.zeloria.item.EquipmentListener;
import com.zeloria.item.EquipmentService;
import com.zeloria.item.VirtualInventoryListener;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.gui.GuiConfigService;
import com.zeloria.gui.MenuListener;
import com.zeloria.gui.MenuService;
import com.zeloria.item.ItemService;
import com.zeloria.world.LayerCleanupTask;
import com.zeloria.world.LayerConfigService;
import com.zeloria.world.LayerEffectService;
import com.zeloria.world.LayerEntryListener;
import com.zeloria.world.BuildManager;
import com.zeloria.world.BuildWorldGuardListener;
import com.zeloria.world.LayerManager;
import com.zeloria.world.LayerNpcService;
import com.zeloria.npc.NpcDamageListener;
import com.zeloria.npc.NpcInteractListener;
import com.zeloria.npc.NpcManager;
import com.zeloria.gui.SimpleGuiListener;
import com.zeloria.skill.LevelListener;
import com.zeloria.skill.LevelService;
import com.zeloria.core.LoggerService;
import com.zeloria.core.MessageService;
import com.zeloria.mob.MobConfigService;
import com.zeloria.mob.MobStatsService;
import com.zeloria.mob.SpawnerManager;
import com.zeloria.combat.DeathListener;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerListener;
import com.zeloria.player.PlayerRegenTask;
import com.zeloria.player.PlayerRepository;
import com.zeloria.player.PlayerService;
import com.zeloria.gui.ClassSelectGUI;
import com.zeloria.combat.CombatListener;
import com.zeloria.combat.CombatModeListener;
import com.zeloria.combat.CombatStateListener;
import com.zeloria.core.ListenerRegistrar;
import com.zeloria.skill.SkillSlotListener;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.CooldownService;
import com.zeloria.skill.SkillCastNotifier;
import com.zeloria.skill.SkillCastService;
import com.zeloria.skill.SkillManager;
import com.zeloria.combat.CombatActionBarTask;
import com.zeloria.combat.CombatModeService;
import com.zeloria.combat.CombatStateService;
import com.zeloria.player.PlayerStatsService;
import com.zeloria.skill.SkillCooldownHotbarTask;
import com.zeloria.skill.SkillSlotService;
import com.zeloria.world.PortalListener;
import com.zeloria.world.PortalParticleTask;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class Zeloria extends JavaPlugin {

    /** 起動時に常時ロードする共有 Hub テンプレート ID。 */
    public static final String HUB_TEMPLATE_ID = "hub";

    /** 起動時に常時ロードする共有 Hub インスタンス ID。 */
    public static final String HUB_INSTANCE_ID = "hub_instance_1";

    /**
     * 起動時に存在を確認する必須設定ディレクトリ（共有設定フォルダの {@code config/} 配下）。
     * <p>
     * 同梱リソース（{@code src/main/resources/config/}）はテンプレートであり、
     * 自動生成は行わない。いずれかが未配置の場合はプラグインを無効化する。
     */
    private static final List<String> REQUIRED_CONFIG_DIRECTORIES = List.of(
            "ClassData",
            "SkillData",
            "ArmorData",
            "WeaponData",
            "NpcData",
            "SpawnerData",
            "LayerData",
            "LevelData",
            "MessageData",
            "MaterialData",
            "MobData",
            "gui"
    );

    /** サーバー固有のチャンネル設定（{@code plugins/Zeloria/channel.yml}）。 */
    private ChannelConfig channelConfig;

    /**
     * 共有設定フォルダ（{@code channel.yml} の {@code shared-config-path} を解決したパス）。
     * <p>
     * このフォルダ自体に YAML 一式（{@code ClassData/} など）と {@code sql.yml} を置き、
     * 全サーバーで共有する。
     */
    private File sharedConfigFolder;

    /**
     * テンプレートワールドの保存先（{@code channel.yml} の {@code world-data-path} を解決したパス）。
     * <p>
     * このフォルダ自体にテンプレート（{@code <templateId>/}）を置き、全サーバーで共有する。
     */
    private File worldDataFolder;

    private YamlLoader yamlLoader;
    private LoggerService loggerService;
    private MessageService messageService;
    private LayerConfigService layerConfigService;
    private LayerEffectService layerEffectService;
    private GuiConfigService guiConfigService;
    private MenuService menuService;
    private ItemService itemService;
    private EquipmentService equipmentService;

    /** 装備中の武器・防具をインベントリへ仮想表示するサービス。 */
    private VirtualInventoryService virtualInventoryService;

    private LevelService levelService;
    private DatabaseService databaseService;
    private DatabaseManager databaseManager;
    private PlayerRepository playerRepository;
    private PlayerService playerService;
    private LayerManager layerManager;

    /**
     * チャンネル 1 専用の建築モード管理。
     * チャンネル 2 以降では {@code null}。
     */
    private BuildManager buildManager;

    private MobConfigService mobConfigService;

    /** スポーン済みカスタムモブの実行時ステータス管理。 */
    private MobStatsService mobStatsService;

    /** {@code LayerData.spawners} に従ってモブを定期スポーンさせるマネージャ。 */
    private SpawnerManager spawnerManager;

    /** カスタムダメージの計算・適用。 */
    private CombatService combatService;

    /** スキル発動の検証・コスト消費・効果適用。 */
    private SkillCastService skillCastService;

    /** スキル発動結果の通知。 */
    private SkillCastNotifier skillCastNotifier;

    /** クラス定義マネージャ（{@code config/ClassData/} を読み込む）。 */
    private ClassManager classManager;

    /** スキル定義マネージャ（{@code config/SkillData/} を読み込む）。 */
    private SkillManager skillManager;

    /** プレイヤーごとのスキルクールダウン管理。 */
    private CooldownService cooldownService;

    /** プレイヤーごとの最終戦闘時刻（自然回復の待機判定に使用）。 */
    private CombatStateService combatStateService;

    /** RPG ステータスのゲーム内反映（移動速度など）を担うサービス。 */
    private PlayerStatsService playerStatsService;

    /** 戦闘モード中のスキルスロット表示を管理するサービス。 */
    private SkillSlotService skillSlotService;

    /** プレイヤーごとの戦闘モード ON / OFF を保持するサービス。 */
    private CombatModeService combatModeService;

    /** 戦闘モード中の HP / MP・スキル情報を表示するアクションバータスク。 */
    private CombatActionBarTask combatActionBarTask;

    /** 戦闘外の HP / MP 自然回復タスク（停止時にキャンセルする）。 */
    private PlayerRegenTask playerRegenTask;

    /** ホットバーのスキルアイコンへ残りクールダウンを表示するタスク。 */
    private SkillCooldownHotbarTask skillCooldownHotbarTask;

    /** クラス選択 GUI（リスナーも兼ねる）。 */
    private ClassSelectGUI classSelectGUI;

    /** 死亡からリスポーンまでの独自演出を処理するリスナー。 */
    private DeathListener deathListener;

    /** イベントリスナーの登録・解除を一元管理する。 */
    private ListenerRegistrar listenerRegistrar;

    /** ポータル外周のパーティクル演出タスク（停止時にキャンセルする）。 */
    private PortalParticleTask portalParticleTask;

    /** 空インスタンス自動削除タスク（停止時にキャンセルする）。 */
    private LayerCleanupTask layerCleanupTask;

    /** NPC 定義の読み込みと NPC の生成・識別を担うマネージャ。 */
    private NpcManager npcManager;

    /** レイヤーワールドのロード時に {@code LayerData.npcs} を配置するサービス。 */
    private LayerNpcService layerNpcService;

    @Override
    public void onEnable() {
        // channel.yml はサーバーごとに異なるため自動生成しない。
        // 未配置・共有フォルダ不在のまま起動すると設定の参照先が定まらないため、ここで停止する。
        if (!loadChannelConfig()) {
            return;
        }

        this.yamlLoader = new YamlLoader(this);

        // 必須 YAML は自動生成しない。未配置のまま起動すると各サービスが
        // 空の設定で動作してしまうため、ここで検出してプラグインを停止する。
        String missingPath = findMissingConfig();
        if (missingPath != null) {
            getLogger().severe("必須YAMLが見つかりません: " + missingPath);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.yamlLoader.loadAll();

        this.loggerService = new LoggerService(this);
        this.loggerService.log(LoggerService.Category.SYSTEM, "プラグインを有効化しています。");

        this.messageService = new MessageService(this, this.yamlLoader);
        this.layerConfigService = new LayerConfigService(this, this.yamlLoader);
        this.mobConfigService = new MobConfigService(this, this.yamlLoader);
        this.npcManager = new NpcManager(this, this.yamlLoader);
        this.mobStatsService = new MobStatsService(this);
        this.combatService = new CombatService(this);
        this.guiConfigService = new GuiConfigService(this, this.yamlLoader);
        this.itemService = new ItemService(this, this.yamlLoader);
        this.equipmentService = new EquipmentService(this, this.itemService);
        this.virtualInventoryService = new VirtualInventoryService(this);
        this.levelService = new LevelService(this, this.yamlLoader);
        this.classManager = new ClassManager(this, this.yamlLoader);
        this.skillManager = new SkillManager(this, this.yamlLoader);
        this.cooldownService = new CooldownService();
        this.combatStateService = new CombatStateService();
        this.playerStatsService = new PlayerStatsService(this, this.combatStateService);
        this.skillCastService = new SkillCastService(
                this, this.combatService, this.playerStatsService, this.cooldownService);
        this.skillCastNotifier = new SkillCastNotifier(this);
        this.skillSlotService = new SkillSlotService(this);
        this.combatModeService = new CombatModeService(this, this.skillSlotService);

        this.databaseService = new DatabaseService(this);
        this.databaseService.connect();

        this.databaseManager = new DatabaseManager(this.databaseService);
        this.playerRepository = new PlayerRepository(this.databaseManager);
        this.playerService = new PlayerService(this, this.playerRepository);
        this.menuService = new MenuService(this);
        this.classSelectGUI = new ClassSelectGUI(this, this.classManager);
        this.deathListener = new DeathListener(this);
        registerMenuFeatures();

        initializeDatabaseSchema();

        this.layerManager = new LayerManager(this);
        // 建築モードはチャンネル 1 のみ有効。他チャンネルでは生成自体を行わない。
        if (this.channelConfig != null && this.channelConfig.isBuildChannel()) {
            this.buildManager = new BuildManager(this);
        }
        this.spawnerManager = new SpawnerManager(this);
        this.layerNpcService = new LayerNpcService(this, this.npcManager, this.layerConfigService);
        this.layerEffectService = new LayerEffectService(this, this.messageService, this.layerConfigService);

        cleanupOrphanedInstances("起動時");
        loadHubLayer();

        registerListeners();
        registerCommands();
        startTasks();

        this.loggerService.log(LoggerService.Category.SYSTEM,
                "プラグインの有効化が完了しました (YAML: " + this.yamlLoader.size()
                        + " 件, MessageData: " + this.yamlLoader.getMessageCount() + " 件).");
    }

    @Override
    public void onDisable() {
        if (this.loggerService != null) {
            this.loggerService.log(LoggerService.Category.SYSTEM, "プラグインを無効化しています。");
        }

        // ワールド操作を伴うタスクを先に止め、停止処理中の競合を防ぐ。
        cancelTasks();

        // リスナーを明示的に解除し、停止処理中のイベント割り込みを防ぐ。
        if (this.listenerRegistrar != null) {
            this.listenerRegistrar.unregisterAll();
            this.listenerRegistrar = null;
        }
        if (this.cooldownService != null) {
            this.cooldownService.clearAll();
        }
        if (this.combatStateService != null) {
            this.combatStateService.clearAll();
        }
        if (this.mobStatsService != null) {
            this.mobStatsService.clearAll();
        }

        // スキル表示中に停止した場合も、退避した実アイテムをインベントリへ戻す。
        if (this.skillSlotService != null) {
            this.skillSlotService.shutdown();
        }
        if (this.deathListener != null) {
            this.deathListener.shutdown();
        }
        if (this.combatModeService != null) {
            this.combatModeService.clearAll();
        }

        // 退出イベントが間に合わなかったプレイヤーのデータを保存してから切断する。
        if (this.playerService != null) {
            this.playerService.saveAllBlocking(10);
        }

        // レイヤーワールド削除前に NPC を明示的に除去する。
        if (this.npcManager != null) {
            this.npcManager.removeAll();
        }

        // ワールド削除中のモブ生成を防ぐため、スポナーを先に停止する。
        if (this.spawnerManager != null) {
            this.spawnerManager.shutdown();
        }

        // 建築中のワールドをテンプレートへ保存してから、レイヤーワールドを片付ける。
        if (this.buildManager != null) {
            this.buildManager.shutdown();
        }

        if (this.layerManager != null) {
            this.layerManager.unloadAllAndDelete();
            cleanupOrphanedInstances("終了時");
            this.layerManager.shutdown();
        }

        // 保存が完了しなかったプレイヤーデータが残らないよう、キャッシュを破棄する。
        PlayerData.clearCache();

        if (this.databaseManager != null) {
            this.databaseManager.shutdown();
        }

        if (this.databaseService != null) {
            this.databaseService.disconnect();
        }

        if (this.loggerService != null) {
            this.loggerService.log(LoggerService.Category.SYSTEM, "プラグインを無効化しました。");
            this.loggerService.shutdown();
        }
    }

    /**
     * {@code plugins/Zeloria/channel.yml} を読み込み、共有設定フォルダを解決する。
     * <p>
     * チャンネル番号はサーバーごとに異なるため自動生成しない。ファイルが無い場合や
     * 共有設定フォルダが存在しない場合は、{@code getLogger().severe(...)} を出力して
     * プラグインを無効化する。
     *
     * @return 読み込みに成功した場合は {@code true}
     */
    private boolean loadChannelConfig() {
        File channelFile = new File(getDataFolder(), ChannelConfig.FILE_NAME);
        if (!channelFile.isFile()) {
            getLogger().severe("channel.yml が見つかりません。plugins/Zeloria/channel.yml を作成してください。");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        this.channelConfig = ChannelConfig.load(channelFile);

        // shared-config-path / world-data-path はサーバールート（paper.jar がある場所）からの
        // 相対パスとして解決する。絶対パスを許可すると環境ごとに設定を書き換える必要があり、
        // 環境非依存の運用が崩れるため禁止する。
        String sharedConfigPath = this.channelConfig.getSharedConfigPath();
        if (isAbsolutePath(sharedConfigPath)) {
            getLogger().severe("shared-config-path には絶対パスを使用しないでください: " + sharedConfigPath);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        String worldDataPath = this.channelConfig.getWorldDataPath();
        if (isAbsolutePath(worldDataPath)) {
            getLogger().severe("world-data-path には絶対パスを使用しないでください");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        File resolvedShared;
        File resolvedWorldData;
        try {
            // サーバールート（paper.jar や world フォルダがある場所）は Bukkit.getWorldContainer() から解決する。
            // getDataFolder() は <server>/plugins/Zeloria/ を指すが、データフォルダの配置によっては
            // 親ディレクトリが存在せず getParentFile() が null を返して NullPointerException になるため、
            // getDataFolder().getParentFile().getParentFile() による解決は行わない。
            File worldContainer = Bukkit.getWorldContainer();
            if (worldContainer == null) {
                throw new IOException("サーバールート（world container）を取得できませんでした。");
            }
            File serverRoot = worldContainer.getCanonicalFile();
            resolvedShared = new File(serverRoot, sharedConfigPath).getCanonicalFile();
            resolvedWorldData = new File(serverRoot, worldDataPath).getCanonicalFile();
        } catch (IOException e) {
            getLogger().severe("設定フォルダの解決に失敗しました: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        if (!resolvedShared.isDirectory()) {
            getLogger().severe("共有設定フォルダが見つかりません: " + resolvedShared.getAbsolutePath());
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        this.sharedConfigFolder = resolvedShared;
        this.worldDataFolder = resolvedWorldData;
        getLogger().info("チャンネル " + this.channelConfig.getChannel()
                + " で起動します（共有設定フォルダ: "
                + this.sharedConfigFolder.getAbsolutePath()
                + "、ワールドデータフォルダ: "
                + this.worldDataFolder.getAbsolutePath() + "）");
        return true;
    }

    /**
     * OS に依存せずに絶対パスか判定する。
     * <p>
     * {@link File#isAbsolute()} は実行 OS の形式のみを判定するため、
     * Unix 上で {@code C:\\...} のような Windows 形式も絶対パスとして扱う。
     *
     * @param path 判定対象のパス
     * @return 絶対パスの場合は {@code true}
     */
    private static boolean isAbsolutePath(String path) {
        if (new File(path).isAbsolute()) {
            return true;
        }
        // 実行環境が Windows 以外でもドライブレター形式（C:\ または C:/）を検出する。
        return path.length() >= 2
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && (path.length() == 2 || path.charAt(2) == '\\' || path.charAt(2) == '/');
    }

    /**
     * 必須 YAML の配置状況を確認し、最初に不足しているパスを返す。
     * <p>
     * ディレクトリ自体が存在しない場合に加え、ディレクトリ内に YAML が 1 つも
     * 無い場合も「未配置」とみなす。パスは共有設定フォルダからの相対表記で返す。
     *
     * @return 不足しているパス。すべて配置済みの場合は {@code null}
     */
    private String findMissingConfig() {
        // sharedConfigFolder は YAML 一式を直接置くフォルダ（例: ZeloriaServer/config）。
        Path configFolder = this.sharedConfigFolder.toPath();
        for (String directory : REQUIRED_CONFIG_DIRECTORIES) {
            Path path = configFolder.resolve(directory);
            if (!Files.isDirectory(path) || !containsYaml(path)) {
                return directory;
            }
        }
        return null;
    }

    /**
     * 指定ディレクトリ配下（サブディレクトリを含む）に YAML ファイルがあるか判定する。
     */
    private static boolean containsYaml(Path directory) {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.anyMatch(path -> Files.isRegularFile(path) && isYamlFile(path));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isYamlFile(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    /**
     * {@code players} テーブルの自動生成と必須カラムの検証を開始する。
     * <p>
     * DDL は {@link DatabaseManager} の専用スレッドプール上で非同期に実行されるため、
     * ここでメインスレッドをブロックすることはない。プレイヤーデータの読み書きは
     * スキーマ初期化 future の完了を待ってから行われる。
     * <p>
     * 必須カラムが不足している場合は、自動 ALTER は行わずにプラグインを無効化する。
     */
    private void initializeDatabaseSchema() {
        if (this.databaseManager == null) {
            return;
        }

        this.databaseManager.initialize().whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                getLogger().info("players テーブルの準備が完了しました。");
                return;
            }

            Throwable cause = throwable;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }

            if (cause instanceof DatabaseManager.MissingColumnsException missing) {
                getLogger().severe(
                        "players テーブルに必要なカラムがありません。sql/reset.sql を実行してください。");
                getLogger().severe("不足しているカラム: "
                        + String.join(", ", missing.missingColumns()));
                // disablePlugin はメインスレッドから呼ぶ必要がある。
                Bukkit.getScheduler().runTask(this, () -> {
                    if (isEnabled()) {
                        getServer().getPluginManager().disablePlugin(this);
                    }
                });
                return;
            }

            getLogger().severe("players テーブルの自動生成に失敗しました: " + cause.getMessage());
        });
    }

    /**
     * ディメンション配置先に残存するテンポラリインスタンスを掃除する。
     * <p>
     * 起動時は前回停止時に削除しきれなかった残骸を、終了時はアンロード漏れを対象とする。
     * クリーンアップ自体の失敗でプラグインの起動・停止を妨げないよう、例外は握りつぶす。
     *
     * @param phase ログ表示用のフェーズ名
     */
    private void cleanupOrphanedInstances(String phase) {
        try {
            int deleted = this.layerManager.cleanupOrphanedInstances();
            if (deleted > 0) {
                getLogger().info(phase + "クリーンアップで " + deleted + " 件のテンポラリレイヤーを削除しました。");
            }
        } catch (RuntimeException e) {
            getLogger().warning(phase + "クリーンアップに失敗しました: " + e.getMessage());
        }
    }

    /**
     * 共有 Hub レイヤーを起動時に生成・ロードする。
     * テンプレートが未配置などの理由で失敗しても、プラグイン全体の起動は継続する。
     */
    private void loadHubLayer() {
        try {
            var world = this.layerManager.createLayer(HUB_TEMPLATE_ID, HUB_INSTANCE_ID);
            getLogger().info("共有 Hub レイヤーをロードしました: " + world.getName());
        } catch (RuntimeException e) {
            getLogger().severe("共有 Hub レイヤーの生成に失敗しました（テンプレート "
                    + "WorldData/" + HUB_TEMPLATE_ID + " を確認してください）: " + e.getMessage());
        }
    }

    /**
     * イベントリスナーを登録する。
     * <p>
     * スキル基盤ではスキルごとにリスナーを作らず、{@link CombatListener} が
     * {@code PlayerInteractEvent} を一元的に処理する。{@link CombatStateListener} は
     * ダメージの有無のみを監視して最終戦闘時刻を記録する（自然回復の待機判定用）。
     * 登録処理は {@link ListenerRegistrar} に集約し、停止時にまとめて解除する。
     */
    private void registerListeners() {
        this.listenerRegistrar = new ListenerRegistrar(this);
        this.listenerRegistrar.register(new PlayerListener(this));
        this.listenerRegistrar.register(new MenuListener(this));
        this.listenerRegistrar.register(new SimpleGuiListener(this));
        this.listenerRegistrar.register(new NpcInteractListener(this));
        this.listenerRegistrar.register(new NpcDamageListener(this));
        this.listenerRegistrar.register(new LayerEntryListener(this));
        this.listenerRegistrar.register(new PortalListener(this));
        if (this.buildManager != null) {
            this.listenerRegistrar.register(new BuildWorldGuardListener(this.buildManager));
        }
        this.listenerRegistrar.register(new CombatListener(this));
        this.listenerRegistrar.register(new CombatStateListener(this));
        this.listenerRegistrar.register(new EquipmentListener(this));
        this.listenerRegistrar.register(new VirtualInventoryListener(this));
        this.listenerRegistrar.register(new SkillSlotListener(this));
        this.listenerRegistrar.register(new CombatModeListener(this));
        this.listenerRegistrar.register(new LevelListener(this));
        if (this.classSelectGUI != null) {
            this.listenerRegistrar.register(this.classSelectGUI);
        }
        if (this.deathListener != null) {
            this.listenerRegistrar.register(this.deathListener);
        }
    }

    /**
     * コマンドを登録する。
     * <p>
     * {@code /menu}（統合メニュー）と {@code /admin}（管理コマンド）を登録する。
     * クラス選択は {@code /class} ではなく {@code /menu} の
     * 「クラス選択」ボタンから開くため、専用コマンドは持たない。
     */
    private void registerCommands() {
        registerMenuCommand();

        PluginCommand command = getCommand("admin");
        if (command == null) {
            getLogger().severe("plugin.yml にコマンド 'admin' が定義されていないため登録できません。");
            return;
        }

        AdminCommand executor = new AdminCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * {@code /menu} コマンドを登録する。
     */
    private void registerMenuCommand() {
        PluginCommand command = getCommand("menu");
        if (command == null) {
            getLogger().warning("plugin.yml にコマンド 'menu' が定義されていないため登録できません。");
            return;
        }

        MenuCommand executor = new MenuCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * メニュー GUI の機能アクションを登録する。
     * <p>
     * {@code config/gui/menu.yml} の {@code features} で定義した
     * {@code action} 名とハンドラを対応付ける。現在は
     * {@code class_select} のみを登録し、クリック時に
     * {@link ClassSelectGUI} を開く。
     */
    private void registerMenuFeatures() {
        if (this.menuService == null) {
            return;
        }

        this.menuService.registerFeatureAction("class_select", player -> {
            ClassSelectGUI gui = this.classSelectGUI;
            if (gui != null) {
                gui.open(player);
            }
        });
    }

    /**
     * 常駐タスク（ポータル演出・空インスタンス自動削除・自然回復）を開始する。
     * <p>
     * いずれもワールド／プレイヤーを操作するため、メインスレッドで周期的に実行する。
     */
    private void startTasks() {
        this.portalParticleTask = new PortalParticleTask(this);
        this.portalParticleTask.runTaskTimer(
                this, 0L, PortalParticleTask.PERIOD_TICKS);

        this.layerCleanupTask = new LayerCleanupTask(this);
        this.layerCleanupTask.runTaskTimer(
                this, LayerCleanupTask.CHECK_INTERVAL_TICKS, LayerCleanupTask.CHECK_INTERVAL_TICKS);

        this.playerRegenTask = new PlayerRegenTask(this);
        this.playerRegenTask.runTaskTimer(
                this, PlayerRegenTask.PERIOD_TICKS, PlayerRegenTask.PERIOD_TICKS);

        this.combatActionBarTask = new CombatActionBarTask(this);
        this.combatActionBarTask.runTaskTimer(
                this, CombatActionBarTask.PERIOD_TICKS, CombatActionBarTask.PERIOD_TICKS);

        // アクションバーがアイコンを再生成した後に、残り秒数をスタック数へ反映する。
        this.skillCooldownHotbarTask = new SkillCooldownHotbarTask(this);
        this.skillCooldownHotbarTask.runTaskTimer(
                this,
                SkillCooldownHotbarTask.PERIOD_TICKS,
                SkillCooldownHotbarTask.PERIOD_TICKS);
    }

    /**
     * 常駐タスクを停止する。多重停止しても安全。
     */
    private void cancelTasks() {
        if (this.portalParticleTask != null) {
            this.portalParticleTask.cancel();
            this.portalParticleTask = null;
        }
        if (this.layerCleanupTask != null) {
            this.layerCleanupTask.cancel();
            this.layerCleanupTask.clear();
            this.layerCleanupTask = null;
        }
        if (this.playerRegenTask != null) {
            this.playerRegenTask.cancel();
            this.playerRegenTask = null;
        }
        if (this.combatActionBarTask != null) {
            this.combatActionBarTask.cancel();
            this.combatActionBarTask = null;
        }
        if (this.skillCooldownHotbarTask != null) {
            this.skillCooldownHotbarTask.cancel();
            this.skillCooldownHotbarTask = null;
        }
    }

    /**
     * YamlLoader を取得する。
     *
     * @return プラグインの YamlLoader（onEnable 前は null）
     */
    public YamlLoader getYamlLoader() {
        return yamlLoader;
    }

    /**
     * サーバー固有のチャンネル設定を取得する。
     *
     * @return プラグインの ChannelConfig（onEnable 前は null）
     */
    public ChannelConfig getChannelConfig() {
        return channelConfig;
    }

    /**
     * 共有設定フォルダ（YAML 一式と {@code sql.yml} を直接置くフォルダ）を取得する。
     *
     * @return 共有設定フォルダ（onEnable 前は null）
     */
    public File getSharedConfigFolder() {
        return sharedConfigFolder;
    }

    /**
     * テンプレートワールドの保存先（{@code WorldData/}）を取得する。
     *
     * @return ワールドデータフォルダ（onEnable 前は null）
     */
    public File getWorldDataFolder() {
        return worldDataFolder;
    }

    /**
     * LoggerService を取得する。
     *
     * @return プラグインの LoggerService（onEnable 前は null）
     */
    public LoggerService getLoggerService() {
        return loggerService;
    }

    /**
     * MessageService を取得する。
     *
     * @return プラグインの MessageService（onEnable 前は null）
     */
    public MessageService getMessageService() {
        return messageService;
    }

    /**
     * LayerConfigService を取得する。
     *
     * @return プラグインの LayerConfigService（onEnable 前は null）
     */
    public LayerConfigService getLayerConfigService() {
        return layerConfigService;
    }

    /**
     * LayerEffectService を取得する。
     *
     * @return プラグインの LayerEffectService（onEnable 前は null）
     */
    public LayerEffectService getLayerEffectService() {
        return layerEffectService;
    }

    /**
     * GuiConfigService を取得する。
     *
     * @return プラグインの GuiConfigService（onEnable 前は null）
     */
    public GuiConfigService getGuiConfigService() {
        return guiConfigService;
    }

    /**
     * MenuService を取得する。
     *
     * @return プラグインの MenuService（onEnable 前は null）
     */
    public MenuService getMenuService() {
        return menuService;
    }

    /**
     * ItemService を取得する。
     *
     * @return プラグインの ItemService（onEnable 前は null）
     */
    public ItemService getItemService() {
        return itemService;
    }

    /**
     * EquipmentService を取得する。
     *
     * @return プラグインの EquipmentService（onEnable 前は null）
     */
    public EquipmentService getEquipmentService() {
        return equipmentService;
    }

    /**
     * VirtualInventoryService を取得する。
     *
     * @return プラグインの VirtualInventoryService（onEnable 前は null）
     */
    public VirtualInventoryService getVirtualInventoryService() {
        return virtualInventoryService;
    }

    /**
     * LevelService を取得する。
     *
     * @return プラグインの LevelService（onEnable 前は null）
     */
    public LevelService getLevelService() {
        return levelService;
    }

    /**
     * DatabaseService を取得する。
     *
     * @return プラグインの DatabaseService（onEnable 前は null）
     */
    public DatabaseService getDatabaseService() {
        return databaseService;
    }

    /**
     * DatabaseManager を取得する。
     *
     * @return プラグインの DatabaseManager（onEnable 前は null）
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * PlayerRepository を取得する。
     *
     * @return プラグインの PlayerRepository（onEnable 前は null）
     */
    public PlayerRepository getPlayerRepository() {
        return playerRepository;
    }

    /**
     * PlayerService を取得する。
     *
     * @return プラグインの PlayerService（onEnable 前は null）
     */
    public PlayerService getPlayerService() {
        return playerService;
    }

    /**
     * LayerManager を取得する。
     *
     * @return プラグインの LayerManager（onEnable 前は null）
     */
    public LayerManager getLayerManager() {
        return layerManager;
    }

    /**
     * BuildManager を取得する。
     *
     * @return プラグインの BuildManager。チャンネル 2 以降または onEnable 前は {@code null}
     */
    public BuildManager getBuildManager() {
        return buildManager;
    }

    /**
     * MobConfigService を取得する。
     *
     * @return プラグインの MobConfigService（onEnable 前は null）
     */
    public MobConfigService getMobConfigService() {
        return mobConfigService;
    }

    /**
     * MobStatsService を取得する。
     *
     * @return プラグインの MobStatsService（onEnable 前は null）
     */
    public MobStatsService getMobStatsService() {
        return mobStatsService;
    }

    /**
     * SpawnerManager を取得する。
     *
     * @return プラグインの SpawnerManager（onEnable 前は null）
     */
    public SpawnerManager getSpawnerManager() {
        return spawnerManager;
    }

    /**
     * NpcManager を取得する。
     *
     * @return プラグインの NpcManager（onEnable 前は null）
     */
    public NpcManager getNpcManager() {
        return npcManager;
    }

    /**
     * LayerNpcService を取得する。
     *
     * @return プラグインの LayerNpcService（onEnable 前は null）
     */
    public LayerNpcService getLayerNpcService() {
        return layerNpcService;
    }

    /**
     * CombatService を取得する。
     *
     * @return プラグインの CombatService（onEnable 前は null）
     */
    public CombatService getCombatService() {
        return combatService;
    }

    /**
     * DeathListener を取得する。
     *
     * @return プラグインの DeathListener（onEnable 前は null）
     */
    public DeathListener getDeathListener() {
        return deathListener;
    }

    /**
     * SkillCastService を取得する。
     *
     * @return プラグインの SkillCastService（onEnable 前は null）
     */
    public SkillCastService getSkillCastService() {
        return skillCastService;
    }

    /**
     * SkillCastNotifier を取得する。
     *
     * @return プラグインの SkillCastNotifier（onEnable 前は null）
     */
    public SkillCastNotifier getSkillCastNotifier() {
        return skillCastNotifier;
    }

    /**
     * ClassManager を取得する。
     *
     * @return プラグインの ClassManager（onEnable 前は null）
     */
    public ClassManager getClassManager() {
        return classManager;
    }

    /**
     * SkillManager を取得する。
     *
     * @return プラグインの SkillManager（onEnable 前は null）
     */
    public SkillManager getSkillManager() {
        return skillManager;
    }

    /**
     * CooldownService を取得する。
     *
     * @return プラグインの CooldownService（onEnable 前は null）
     */
    public CooldownService getCooldownService() {
        return cooldownService;
    }

    /**
     * CombatStateService を取得する。
     *
     * @return プラグインの CombatStateService（onEnable 前は null）
     */
    public CombatStateService getCombatStateService() {
        return combatStateService;
    }

    /**
     * PlayerStatsService を取得する。
     *
     * @return プラグインの PlayerStatsService（onEnable 前は null）
     */
    public PlayerStatsService getPlayerStatsService() {
        return playerStatsService;
    }

    /**
     * SkillSlotService を取得する。
     *
     * @return プラグインの SkillSlotService（onEnable 前は null）
     */
    public SkillSlotService getSkillSlotService() {
        return skillSlotService;
    }

    /**
     * CombatModeService を取得する。
     *
     * @return プラグインの CombatModeService（onEnable 前は null）
     */
    public CombatModeService getCombatModeService() {
        return combatModeService;
    }

    /**
     * ClassSelectGUI を取得する。
     *
     * @return プラグインの ClassSelectGUI（onEnable 前は null）
     */
    public ClassSelectGUI getClassSelectGUI() {
        return classSelectGUI;
    }
}
