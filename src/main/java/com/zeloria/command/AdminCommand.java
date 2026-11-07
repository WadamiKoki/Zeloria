package com.zeloria.command;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.core.DatabaseService;
import com.zeloria.item.ItemCategory;
import com.zeloria.item.ItemData;
import com.zeloria.item.ItemService;
import com.zeloria.world.BuildManager;
import com.zeloria.world.LayerCleanupTask;
import com.zeloria.world.LayerConfigService;
import com.zeloria.world.LayerData;
import com.zeloria.world.LayerManager;
import com.zeloria.skill.LevelService;
import com.zeloria.core.LoggerService;
import com.zeloria.core.MessageService;
import com.zeloria.mob.MobConfigService;
import com.zeloria.mob.MobData;
import com.zeloria.mob.MobStatsService;
import com.zeloria.npc.NpcData;
import com.zeloria.npc.NpcManager;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.InventorySerializer;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerService;
import com.zeloria.player.PlayerSnapshot;
import com.zeloria.player.PlayerStatus;
import com.zeloria.player.PlayerStats;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.logging.Level;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理コマンド {@code /admin}（別名 {@code /zeloria}）の実装。
 * <p>
 * <ul>
 *     <li>{@code /admin reload} - YAML 設定を再読み込みする</li>
 *     <li>{@code /admin test yaml [<folder>] [<id>]} - ロード済み YAML を要約／照会する
 *         （{@code WeaponData/archer/test} のような階層パス指定にも対応）</li>
 *     <li>{@code /admin test db} - DB 接続とテーブル作成を検証する</li>
 *     <li>{@code /admin mob spawn <mobId> [count]} - MobData を指定してモブをスポーンする</li>
 *     <li>{@code /admin build create <yml>} / {@code /admin build join <yml>} - 建築ワールドを作成／参加する</li>
 *     <li>{@code /admin build list|info|save|unsave} - チャンネル 1 の建築ワールドを管理する</li>
 *     <li>{@code /admin stop} - プレイヤーデータ保存・レイヤー破棄・ワールド保存を行ってから安全に停止する</li>
 * </ul>
 * 実行結果のメッセージは {@code config/MessageData/admin.yml} で一元管理する。
 * 表示には Paper 推奨の MiniMessage を使用し、1 項目あたり 1〜2 行に凝縮して出力する。
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String PREFIX = "<green>[Zeloria]</green> ";
    private static final String RELOAD = "reload";
    private static final String TEST = "test";
    private static final String YAML = "yaml";
    private static final String DB = "db";
    private static final String MOB = "mob";
    private static final String SPAWN = "spawn";
    private static final String GIVE = "give";
    private static final String INFO = "info";
    private static final String GUI = "gui";
    private static final String BUILD = "build";
    private static final String CREATE = "create";
    private static final String JOIN = "join";
    private static final String LAYER = "layer";
    private static final String ALL = "all";
    private static final String ONLINE = "online";
    private static final String SAVE = "save";
    private static final String UNSAVE = "unsave";
    private static final String FORCE = "force";
    private static final String NPC = "npc";
    private static final String LIST = "list";
    private static final String REMOVE = "remove";
    private static final String STOP = "stop";

    /**
     * /admin コマンド関連メッセージを格納した MessageData ファイル名。
     */
    private static final String MESSAGE_FILE = "admin";

    /** モブを識別するための PersistentDataContainer キー。 */
    private static final String MOB_ID_KEY = "mob_id";

    /** モブに付与するスコアボードタグの接頭辞。 */
    private static final String MOB_TAG_PREFIX = "zeloria_mob:";

    /** 1 回のコマンドでスポーンできる上限（誤用によるラグ防止）。 */
    private static final int MAX_SPAWN_COUNT = 32;

    /** 1 回のコマンドで付与できるアイテム数の上限。 */
    private static final int MAX_GIVE_AMOUNT = 9999;

    /** フォルダサマリーで 1 フォルダあたりに表示する ID の上限。 */
    private static final int MAX_YAML_SUMMARY_IDS = 8;

    /** YAML 詳細表示で展開する末端キーの上限（チャット欄の溢れ防止）。 */
    private static final int MAX_YAML_DETAIL_LINES = 40;

    /** {@code /admin test db} で表示するテーブル数の上限。 */
    private static final int MAX_DB_TABLE_LINES = 30;

    /** {@code /admin test yaml} の表示・タブ補完対象から除外するフォルダ。 */
    private static final String EXCLUDED_FOLDER = "MessageData";

    /**
     * 管理コマンド全般の権限。
     */
    private static final String ADMIN_PERMISSION = "zeloria.admin";

    /**
     * 安全停止コマンド専用の権限。
     */
    private static final String STOP_PERMISSION = "zeloria.admin.stop";

    /** {@code /admin npc remove} で NPC を探索する半径（ブロック）。 */
    private static final double NPC_REMOVE_RADIUS = 10.0;

    private static final List<String> TEST_TARGETS = List.of(YAML, DB);

    private static final List<String> MOB_TARGETS = List.of(SPAWN);

    private static final List<String> NPC_TARGETS = List.of(LIST, SPAWN, REMOVE);

    private static final List<String> LAYER_TARGETS = List.of(ALL, ONLINE);

    /**
     * {@code admin.yml} を読み込めなかった場合に使用する使用方法のフォールバック。
     */
    private static final String FALLBACK_USAGE =
            "<gray>使い方:</gray> <white>/admin <reload|test yaml|test db|mob|npc|give|info|gui|build|layer|stop></white>";

    private final Zeloria plugin;

    /**
     * スポーンしたモブを識別するための PDC キー。
     */
    private final NamespacedKey mobIdKey;

    /**
     * 安全停止処理の多重実行を防ぐフラグ。
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public AdminCommand(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, usage());
            return true;
        }

        // 全サブコマンドの共通権限。未保有の場合はサブコマンドを解析せず早期に拒否する。
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, noPermission(ADMIN_PERMISSION));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // 建築モードはチャンネル 1 専用。サブコマンドの解析より前に判定する。
        if (BUILD.equals(sub) && !isBuildChannel()) {
            send(sender, message("build.channel_only", Map.of(),
                    "<red>建築モードはチャンネル1でのみ使用できます</red>"));
            return true;
        }

        // stop は専用権限で判定する（zeloria.admin から子権限として継承される）。
        if (STOP.equals(sub)) {
            if (!sender.hasPermission(STOP_PERMISSION)) {
                send(sender, noPermission(STOP_PERMISSION));
                return true;
            }
            stop(sender);
            return true;
        }

        switch (sub) {
            case RELOAD -> reload(sender);
            case TEST -> handleTest(sender, args);
            case MOB -> handleMob(sender, args);
            case NPC -> handleNpc(sender, args);
            case GIVE -> handleGive(sender, args);
            case INFO -> handleInfo(sender, args);
            case GUI -> handleGui(sender, args);
            case BUILD -> handleBuild(sender, args);
            case LAYER -> handleLayer(sender, args);
            default -> {
                send(sender, message("command.unknown_subcommand", Map.of("input", sub),
                        "<red>不明なサブコマンドです: <white>{input}</white></red>"));
                send(sender, usage());
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 候補の重複を除去し、大文字小文字を無視して昇順に並べる。
        Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sorted.addAll(complete(sender, command, alias, args));
        return new ArrayList<>(sorted);
    }

    private List<String> complete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> candidates = new ArrayList<>();
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                candidates.add(RELOAD);
                candidates.add(TEST);
                candidates.add(MOB);
                candidates.add(NPC);
                candidates.add(GIVE);
                candidates.add(INFO);
                candidates.add(GUI);
                candidates.add(LAYER);
                if (isBuildChannel() && plugin.getBuildManager() != null) {
                    candidates.add(BUILD);
                }
            }
            if (sender.hasPermission(STOP_PERMISSION)) {
                candidates.add(STOP);
            }
            return partial(args[0], candidates);
        }

        if (MOB.equalsIgnoreCase(args[0])) {
            return completeMob(sender, args);
        }

        if (LAYER.equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                return List.of();
            }
            if (args.length == 2) {
                return partial(args[1], List.of(INFO));
            }
            if (args.length == 3 && INFO.equalsIgnoreCase(args[1])) {
                return partial(args[2], LAYER_TARGETS);
            }
            return List.of();
        }

        if (NPC.equalsIgnoreCase(args[0])) {
            return completeNpc(sender, args);
        }

        if (GIVE.equalsIgnoreCase(args[0])) {
            return completeGive(sender, args);
        }

        if (INFO.equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(ADMIN_PERMISSION) || args.length != 2) {
                return List.of();
            }
            return partial(args[1], Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .toList());
        }

        if (GUI.equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(ADMIN_PERMISSION) || args.length != 2) {
                return List.of();
            }
            YamlLoader loader = plugin.getYamlLoader();
            return loader == null ? List.of() : partial(args[1], idsOf(loader, "gui"));
        }

        if (BUILD.equalsIgnoreCase(args[0])) {
            BuildManager buildManager = plugin.getBuildManager();
            if (!isBuildChannel()
                    || !sender.hasPermission(ADMIN_PERMISSION)
                    || buildManager == null) {
                return List.of();
            }

            Set<String> openTemplates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            openTemplates.addAll(buildManager.getOpenTemplateIds());

            if (args.length == 2) {
                Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                candidates.add(CREATE);
                candidates.add(JOIN);
                candidates.add(LIST);
                candidates.add(INFO);
                candidates.add(SAVE);
                candidates.add(UNSAVE);
                return partial(args[1], candidates);
            }
            if (args.length == 3) {
                String action = args[1];
                // create / join は config/LayerData/ の YAML 名（拡張子なし）を候補にする。
                if (CREATE.equalsIgnoreCase(action) || JOIN.equalsIgnoreCase(action)) {
                    return partial(args[2], buildTemplateNames());
                }
                if (INFO.equalsIgnoreCase(action)) {
                    return partial(args[2], openTemplates);
                }
                if (SAVE.equalsIgnoreCase(action) || UNSAVE.equalsIgnoreCase(action)) {
                    Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    candidates.addAll(openTemplates);
                    candidates.add(FORCE);
                    return partial(args[2], candidates);
                }
                return List.of();
            }
            if (args.length == 4
                    && (SAVE.equalsIgnoreCase(args[1]) || UNSAVE.equalsIgnoreCase(args[1]))) {
                return partial(args[3], List.of(FORCE));
            }
            return List.of();
        }

        if (!TEST.equalsIgnoreCase(args[0]) || !sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 2) {
            return partial(args[1], TEST_TARGETS);
        }
        if (!YAML.equalsIgnoreCase(args[1])) {
            return List.of();
        }

        YamlLoader yamlLoader = plugin.getYamlLoader();
        if (yamlLoader == null) {
            return List.of();
        }
        if (args.length == 3) {
            // config/ 直下のフォルダ名のみを候補にする（ファイル名は出さない）。
            return partial(args[2], completeYamlFolder(yamlLoader, args[2]));
        }
        if (args.length == 4) {
            // 選択したフォルダ配下のファイル・サブフォルダのみを候補にする。
            return completeYamlId(yamlLoader, args[2], args[3]);
        }
        return List.of();
    }

    /**
     * {@code /admin mob ...} のタブ補完。
     * <p>
     * 第 3 引数（モブ ID）には {@link MobConfigService} が読み込んだ ID 一覧を返す。
     */
    private List<String> completeMob(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 2) {
            return partial(args[1], MOB_TARGETS);
        }
        if (args.length == 3 && SPAWN.equalsIgnoreCase(args[1])) {
            MobConfigService mobConfigService = plugin.getMobConfigService();
            return mobConfigService == null ? List.of() : partial(args[2], mobConfigService.getIds());
        }
        return List.of();
    }

    /**
     * {@code /admin npc ...} のタブ補完。
     * <p>
     * 第 3 引数（NPC ID）には {@link NpcManager} が読み込んだ ID 一覧を返す。
     */
    private List<String> completeNpc(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 2) {
            return partial(args[1], NPC_TARGETS);
        }
        if (args.length == 3 && SPAWN.equalsIgnoreCase(args[1])) {
            NpcManager npcManager = plugin.getNpcManager();
            return npcManager == null ? List.of() : partial(args[2], npcManager.getIds());
        }
        return List.of();
    }

    /**
     * {@code /admin give <armor|material|weapon> <id> [amount]} のタブ補完。
     * <p>
     * 第 3 引数には {@link ItemService} が読み込んだ種別ごとの ID 一覧、
     * 第 4 引数には代表的な個数を候補として返す。
     */
    private List<String> completeGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 2) {
            return partial(args[1], Arrays.stream(ItemCategory.values())
                    .map(ItemCategory::key)
                    .toList());
        }

        ItemCategory category = ItemCategory.fromKey(args[1]);
        ItemService itemService = plugin.getItemService();
        if (category == null || itemService == null) {
            return List.of();
        }
        if (args.length == 3) {
            return partial(args[2], itemService.getIds(category));
        }
        if (args.length == 4) {
            return partial(args[3], List.of("1", "16", "64", "256", "9999"));
        }
        return List.of();
    }

    /**
     * {@code /admin reload}: config/ 配下の YAML を再読み込みする。
     */
    private void reload(CommandSender sender) {
        YamlLoader yamlLoader = plugin.getYamlLoader();
        if (yamlLoader == null) {
            send(sender, PREFIX + "<red>YamlLoader が初期化されていません。</red>");
            return;
        }

        int count = yamlLoader.loadAllConfigs();

        // LayerData / MessageData / MobData / GUI のキャッシュも最新化する。
        if (plugin.getLayerConfigService() != null) {
            plugin.getLayerConfigService().reload();
        }
        if (plugin.getMobConfigService() != null) {
            plugin.getMobConfigService().reload();
        }
        if (plugin.getCollisionManager() != null) {
            plugin.getCollisionManager().reload();
        }
        if (plugin.getItemService() != null) {
            plugin.getItemService().reload();
        }
        if (plugin.getLevelService() != null) {
            plugin.getLevelService().reload();
        }
        if (plugin.getGuiConfigService() != null) {
            plugin.getGuiConfigService().reload();
        }
        if (plugin.getClassManager() != null) {
            plugin.getClassManager().reload();
        }
        if (plugin.getTreeDataService() != null) {
            plugin.getTreeDataService().reload();
        }
        if (plugin.getSkillVariantManager() != null) {
            plugin.getSkillVariantManager().reload();
        }
        if (plugin.getClassTreeManager() != null) {
            plugin.getClassTreeManager().reload();
        }
        if (plugin.getSkillManager() != null) {
            plugin.getSkillManager().reload();
        }
        if (plugin.getNpcManager() != null) {
            plugin.getNpcManager().reload();
        }
        if (plugin.getClassSelectGUI() != null) {
            plugin.getClassSelectGUI().reload();
        }
        if (plugin.getSpawnerManager() != null) {
            plugin.getSpawnerManager().reload();
        }

        send(sender, message("command.reloaded",
                Map.of("count", String.valueOf(count),
                        "messages", String.valueOf(yamlLoader.getMessageCount())),
                "<white>設定を再読み込みしました。</white> <gray>(YAML: <yellow>{count}</yellow>, "
                        + "MessageData: <yellow>{messages}</yellow>)</gray>"));

        if (plugin.getLoggerService() != null) {
            plugin.getLoggerService().log(
                    com.zeloria.core.LoggerService.Category.COMMAND,
                    "設定再読み込み: sender=" + sender.getName() + ", YAML=" + count
                            + ", MessageData=" + yamlLoader.getMessageCount());
        }
    }

    /**
     * {@code /admin mob ...} のディスパッチ。
     * <p>
     * 現時点では {@code spawn} のみをサポートする。
     */
    private void handleMob(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, mobUsage());
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (!SPAWN.equals(action)) {
            send(sender, message("command.unknown_subcommand", Map.of("input", args[1]),
                    "<red>不明なサブコマンドです: <white>{input}</white></red>"));
            send(sender, mobUsage());
            return;
        }

        if (args.length < 3) {
            send(sender, mobUsage());
            return;
        }

        int count = 1;
        if (args.length >= 4) {
            try {
                count = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                send(sender, mobUsage());
                return;
            }
        }
        if (count < 1 || count > MAX_SPAWN_COUNT) {
            send(sender, mobUsage());
            return;
        }

        spawnMob(sender, args[2], count);
    }

    /**
     * {@code /admin npc ...} のディスパッチ。
     * <p>
     * {@code list} / {@code spawn <npcId>} / {@code remove} をサポートする。
     */
    private void handleNpc(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, npcUsage());
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case LIST -> listNpcs(sender);
            case SPAWN -> {
                if (args.length < 3) {
                    send(sender, npcUsage());
                    return;
                }
                spawnNpc(sender, args[2]);
            }
            case REMOVE -> removeNearestNpc(sender);
            default -> {
                send(sender, message("command.unknown_subcommand", Map.of("input", args[1]),
                        "<red>不明なサブコマンドです: <white>{input}</white></red>"));
                send(sender, npcUsage());
            }
        }
    }

    /**
     * 読み込み済みの NPC 定義一覧を表示する。
     */
    private void listNpcs(CommandSender sender) {
        NpcManager npcManager = plugin.getNpcManager();
        if (npcManager == null) {
            send(sender, message("npc.unavailable", Map.of(),
                    "<red>NPC 管理が初期化されていません。</red>"));
            return;
        }
        if (npcManager.size() == 0) {
            send(sender, message("npc.list.empty", Map.of(),
                    "<gray>NpcData が 1 件も読み込まれていません。</gray>"));
            return;
        }

        send(sender, message("npc.list.header",
                Map.of("count", String.valueOf(npcManager.size())),
                "<gray>NpcData: <white>{count}</white> 件</gray>"));
        for (NpcData data : npcManager.getAll()) {
            send(sender, message("npc.list.entry",
                    Map.of("npc_id", data.id(),
                            "entity_type", data.spawnableMobType().getKey().toString(),
                            "gui", data.hasInteractGui() ? data.interactGui() : "-"),
                    "<gray>- <white>{npc_id}</white> <dark_gray>({entity_type}, GUI: {gui})</dark_gray></gray>"));
        }
    }

    /**
     * 実行者（プレイヤー）の位置へ NPC をスポーンする。
     */
    private void spawnNpc(CommandSender sender, String npcId) {
        if (!(sender instanceof Player player)) {
            send(sender, message("command.player_only", Map.of(),
                    "<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return;
        }

        NpcManager npcManager = plugin.getNpcManager();
        if (npcManager == null) {
            send(sender, message("npc.unavailable", Map.of(),
                    "<red>NPC 管理が初期化されていません。</red>"));
            return;
        }

        Optional<NpcData> found = npcManager.find(npcId);
        if (found.isEmpty()) {
            send(sender, message("npc.spawn.not_found", Map.of("npc_id", npcId),
                    "<red>NPC 設定が見つかりません: <white>{npc_id}</white></red>"));
            return;
        }

        NpcData data = found.get();
        Optional<LivingEntity> spawned = npcManager.spawnNpc(data, player.getLocation());
        if (spawned.isEmpty()) {
            send(sender, message("npc.spawn.failed", Map.of("npc_id", data.id()),
                    "<red>NPC のスポーンに失敗しました: <white>{npc_id}</white></red>"));
            return;
        }

        send(sender, message("npc.spawn.success", Map.of("npc_id", data.id()),
                "<green>NPC</green> <white>{npc_id}</white> <gray>をスポーンしました。</gray>"));
    }

    /**
     * 実行者（プレイヤー）から最も近い NPC を削除する。
     */
    private void removeNearestNpc(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, message("command.player_only", Map.of(),
                    "<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return;
        }

        NpcManager npcManager = plugin.getNpcManager();
        if (npcManager == null) {
            send(sender, message("npc.unavailable", Map.of(),
                    "<red>NPC 管理が初期化されていません。</red>"));
            return;
        }

        Optional<LivingEntity> removed = npcManager.removeNearest(
                player.getLocation(), NPC_REMOVE_RADIUS);
        if (removed.isEmpty()) {
            send(sender, message("npc.remove.not_found",
                    Map.of("radius", String.valueOf((int) NPC_REMOVE_RADIUS)),
                    "<red>半径 {radius} ブロック以内に NPC が見つかりません。</red>"));
            return;
        }

        send(sender, message("npc.remove.success", Map.of(),
                "<green>最寄りの NPC を削除しました。</green>"));
    }

    /**
     * {@code /admin mob spawn <mobId> [count]} の本体。
     * <p>
     * {@link MobConfigService} から {@link MobData} を引き、実行者（プレイヤー）の
     * 現在位置にカスタム名・最大 HP・識別タグを付与したエンティティをスポーンする。
     */
    private void spawnMob(CommandSender sender, String mobId, int count) {
        if (!(sender instanceof Player player)) {
            send(sender, message("command.player_only", Map.of(),
                    "<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return;
        }

        MobConfigService mobConfigService = plugin.getMobConfigService();
        Optional<MobData> found = mobConfigService == null
                ? Optional.empty()
                : mobConfigService.find(mobId);
        if (found.isEmpty()) {
            send(sender, message("mob.spawn.not_found", Map.of("mob_id", mobId),
                    "<red>モブ設定が見つかりません: <white>{mob_id}</white></red>"));
            return;
        }

        MobData data = found.get();
        EntityType entityType = data.spawnableEntityType();
        if (!entityType.isSpawnable()) {
            send(sender, message("mob.spawn.invalid_type",
                    Map.of("entity_type", entityType.getKey().toString()),
                    "<red>スポーンできないエンティティ種別です: <white>{entity_type}</white></red>"));
            return;
        }

        int spawned;
        try {
            spawned = spawnEntities(player, data, entityType, count);
        } catch (RuntimeException e) {
            String error = rootMessage(e);
            plugin.getLogger().warning("モブのスポーンに失敗しました: mob=" + data.id()
                    + ", player=" + player.getName() + ", error=" + error);
            send(sender, message("mob.spawn.failed",
                    Map.of("mob_id", data.id(), "error", error),
                    "<red>モブのスポーンに失敗しました: <white>{mob_id}</white> <gray>({error})</gray></red>"));
            return;
        }

        send(sender, message("mob.spawn.success",
                Map.of("mob_name", data.name(), "count", String.valueOf(spawned)),
                "<green>モブ</green> <white>{mob_name}</white> <gray>をスポーンしました。</gray> "
                        + "<dark_gray>(<gray>{count} 体</gray>)</dark_gray>"));

        LoggerService logger = plugin.getLoggerService();
        if (logger != null) {
            logger.log(LoggerService.Category.COMMAND,
                    "モブスポーン: mob=" + data.id()
                            + ", entity=" + entityType.getKey()
                            + ", count=" + spawned
                            + ", player=" + player.getName()
                            + ", world=" + player.getWorld().getName());
        }
    }

    /**
     * 指定体数のエンティティを実行者位置にスポーンする。
     *
     * @return 実際にスポーンできた体数
     */
    private int spawnEntities(Player player, MobData data, EntityType entityType, int count) {
        World world = player.getWorld();
        Location location = player.getLocation();

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = world.spawnEntity(location, entityType,
                    CreatureSpawnEvent.SpawnReason.COMMAND,
                    created -> applyMobData(created, data));
            if (entity != null) {
                spawned++;
            }
        }
        return spawned;
    }

    /**
     * スポーンしたエンティティへ {@link MobData} の設定を反映する。
     * <p>
     * 表示名（MiniMessage）・最大 HP・スコアボードタグ・PDC のモブ ID を設定する。
     * 防御力や報酬は戦闘システム実装までの保持項目のため、ここでは適用しない。
     */
    private void applyMobData(Entity entity, MobData data) {
        entity.customName(displayName(data.name()));
        entity.setCustomNameVisible(data.alwaysShowName());
        entity.addScoreboardTag(MOB_TAG_PREFIX + data.id());
        entity.getPersistentDataContainer().set(mobIdKey, PersistentDataType.STRING, data.id());

        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        // カスタムダメージ計算用の実行時ステータスを登録する。
        MobStatsService mobStatsService = plugin.getMobStatsService();
        if (mobStatsService != null) {
            mobStatsService.attach(living, data);
        }

        if (data.maxHp() <= 0.0) {
            return;
        }

        AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        maxHealth.setBaseValue(data.maxHp());
        living.setHealth(Math.min(data.maxHp(), maxHealth.getValue()));
    }

    /**
     * モブの表示名を MiniMessage として解決する。
     */
    private Component displayName(String name) {
        MessageService messageService = plugin.getMessageService();
        if (messageService == null) {
            return Component.text(name);
        }
        return messageService.deserialize(name, Map.of());
    }

    /**
     * {@code /admin build}: チャンネル 1 の建築モードを操作する。
     * <ul>
     *     <li>{@code /admin build create <yml>} - テンプレートから建築ワールドを新規作成して入場する</li>
     *     <li>{@code /admin build join <yml>} - 開いている建築ワールドへ参加する</li>
     *     <li>{@code /admin build list} - 開いている建築ワールドを一覧表示する</li>
     *     <li>{@code /admin build info [yml]} - 建築ワールドの状態を表示する</li>
     *     <li>{@code /admin build save [yml] [force]} - 保存して Hub へ戻る</li>
     *     <li>{@code /admin build unsave [yml] [force]} - 保存せずに破棄して Hub へ戻る</li>
     * </ul>
     * 建築モードは {@link BuildManager} が生成されるチャンネル 1 でのみ利用できる。
     */
    private void handleBuild(CommandSender sender, String[] args) {
        // list / info は建築モードの状態確認のみのため、zeloria.admin だけで実行できる。
        if (args.length >= 2 && (LIST.equalsIgnoreCase(args[1]) || INFO.equalsIgnoreCase(args[1]))) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                send(sender, noPermission(ADMIN_PERMISSION));
                return;
            }
            BuildManager buildManager = plugin.getBuildManager();
            if (buildManager == null) {
                send(sender, message("build.channel_only", Map.of(),
                        "<red>建築モードはチャンネル1でのみ使用できます</red>"));
                return;
            }
            if (args.length > 3 || (LIST.equalsIgnoreCase(args[1]) && args.length != 2)) {
                send(sender, buildUsage());
                return;
            }
            if (args.length == 2) {
                if (LIST.equalsIgnoreCase(args[1])) {
                    handleBuildList(sender, buildManager);
                    return;
                }
                // テンプレート未指定の info は開いている代表ワールドを表示する。
                String templateId = buildManager.getActiveTemplateId();
                if (templateId == null) {
                    send(sender, message("build.not_active", Map.of(),
                            "<yellow>建築中のワールドはありません。</yellow>"));
                    return;
                }
                handleBuildInfo(sender, buildManager, templateId);
            } else {
                handleBuildInfo(sender, buildManager, args[2]);
            }
            return;
        }

        if (!sender.hasPermission(BuildManager.BUILD_PERMISSION)) {
            send(sender, noPermission(BuildManager.BUILD_PERMISSION));
            return;
        }

        BuildManager buildManager = plugin.getBuildManager();
        if (buildManager == null) {
            send(sender, message("build.channel_only", Map.of(),
                    "<red>建築モードはチャンネル1でのみ使用できます</red>"));
            return;
        }

        if (args.length < 2) {
            send(sender, buildUsage());
            return;
        }

        String action = args[1];
        if (SAVE.equalsIgnoreCase(action) || UNSAVE.equalsIgnoreCase(action)) {
            handleBuildSave(sender, buildManager, args, SAVE.equalsIgnoreCase(action));
            return;
        }

        if (CREATE.equalsIgnoreCase(action) || JOIN.equalsIgnoreCase(action)) {
            if (args.length != 3) {
                send(sender, buildUsage());
                return;
            }
            if (CREATE.equalsIgnoreCase(action)) {
                handleBuildCreate(sender, buildManager, args[2]);
            } else {
                handleBuildJoin(sender, buildManager, args[2]);
            }
            return;
        }

        send(sender, message("command.unknown_subcommand", Map.of("input", args[1]),
                "<red>不明なサブコマンドです: <white>{input}</white></red>"));
        send(sender, buildUsage());
    }

    /**
     * {@code /admin build create <yml>}: 建築ワールドを新規作成して入場する。
     *
     * @param sender       実行者
     * @param buildManager 建築モード管理
     * @param templateName テンプレート名（{@code config/LayerData/} の YAML 名・拡張子なし）
     */
    private void handleBuildCreate(CommandSender sender, BuildManager buildManager, String templateName) {
        String templateId = resolveBuildTemplateId(templateName);
        if (buildManager.isBusy()) {
            sendBuildBusy(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, message("build.player_only", Map.of(),
                    "<red>建築モードを開始するにはプレイヤーとして実行してください。</red>"));
            return;
        }
        if (buildManager.isBuildWorldOpen(templateId)) {
            send(sender, message("build.already_open", Map.of("template", esc(templateName)),
                    "<yellow>建築ワールド '<white>{template}</white>' は既に開かれています。"
                            + "/admin build join <white>{template}</white> で参加してください。</yellow>"));
            return;
        }

        send(sender, message("build.preparing", Map.of("template", esc(templateName)),
                "<yellow>建築ワールド '<white>{template}</white>' を準備しています...</yellow>"));
        buildManager.createBuildWorldAsync(templateId, player).whenComplete((world, error) -> {
            if (error != null) {
                send(sender, message("build.open_failed", Map.of(
                                "template", esc(templateName),
                                "error", esc(rootMessage(error))),
                        "<red>建築ワールドを開けませんでした: <white>{error}</white></red>"));
                return;
            }
            send(sender, message("build.opened", Map.of("template", esc(templateName)),
                    "<green>建築ワールド '<white>{template}</white>' を開きました。</green>"));
        });
    }

    /**
     * {@code /admin build join <yml>}: 開いている建築ワールドへ参加する。
     *
     * @param sender       実行者
     * @param buildManager 建築モード管理
     * @param templateName テンプレート名（{@code config/LayerData/} の YAML 名・拡張子なし）
     */
    private void handleBuildJoin(CommandSender sender, BuildManager buildManager, String templateName) {
        String templateId = resolveBuildTemplateId(templateName);
        if (buildManager.isBusy()) {
            sendBuildBusy(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, message("build.player_only", Map.of(),
                    "<red>建築モードを開始するにはプレイヤーとして実行してください。</red>"));
            return;
        }
        if (!buildManager.joinBuildWorld(templateId, player)) {
            send(sender, message("build.not_open", Map.of("template", esc(templateName)),
                    "<yellow>建築ワールド '<white>{template}</white>' は開かれていません。"
                            + "/admin build create <white>{template}</white> で作成してください。</yellow>"));
            return;
        }
        send(sender, message("build.joined", Map.of("template", esc(templateName)),
                "<green>建築ワールド '<white>{template}</white>' に参加しました。</green>"));
    }

    /**
     * {@code config/LayerData/} 配下の YAML 名（拡張子なし）を候補として返す。
     *
     * @return 建築テンプレート名の一覧（大文字小文字を無視して昇順）
     */
    private List<String> buildTemplateNames() {
        YamlLoader yamlLoader = plugin.getYamlLoader();
        if (yamlLoader == null) {
            return List.of();
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : yamlLoader.getKeys()) {
            if ("LayerData".equalsIgnoreCase(folderOf(key))) {
                names.add(stripExtension(fileNameOf(key)));
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * 入力されたテンプレート名を建築テンプレート ID へ変換する。
     * <p>
     * {@code Field1} のような YAML 名のほか、{@code field_1} のようなレイヤー ID・
     * テンプレート ID でも指定できる。一致する LayerData が無い場合は入力をそのまま返す。
     *
     * @param input 入力されたテンプレート名
     * @return 建築テンプレート ID
     */
    private String resolveBuildTemplateId(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String name = input.trim();
        LayerConfigService layerConfigService = plugin.getLayerConfigService();
        if (layerConfigService == null) {
            return name;
        }
        for (LayerData layer : layerConfigService.getLayers()) {
            String sourceName = stripExtension(fileNameOf(layer.getSourceKey()));
            if (sourceName.equalsIgnoreCase(name)
                    || layer.getId().equalsIgnoreCase(name)
                    || layer.getTemplateId().equalsIgnoreCase(name)) {
                return layer.getTemplateId();
            }
        }
        return name;
    }

    /**
     * {@code /admin build} の使用方法メッセージを返す。
     */
    private String buildUsage() {
        return message("build.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin build create <yml></white> "
                        + "<dark_gray>|</dark_gray> <white>/admin build join <yml></white> "
                        + "<dark_gray>|</dark_gray> <white>/admin build list</white> "
                        + "<dark_gray>|</dark_gray> <white>/admin build info [yml]</white> "
                        + "<dark_gray>|</dark_gray> <white>/admin build save [yml] [force]</white> "
                        + "<dark_gray>|</dark_gray> <white>/admin build unsave [yml] [force]</white>");
    }

    /**
     * {@code /admin build save} / {@code /admin build unsave} の共通処理。
     *
     * @param save {@code true} の場合は保存、{@code false} の場合は破棄する
     */
    private void handleBuildSave(CommandSender sender, BuildManager buildManager,
                                 String[] args, boolean save) {
        String templateId = null;
        boolean force = false;
        if (args.length == 3) {
            if (FORCE.equalsIgnoreCase(args[2])) {
                force = true;
            } else {
                templateId = resolveBuildTemplateId(args[2]);
            }
        } else if (args.length == 4 && FORCE.equalsIgnoreCase(args[3])) {
            templateId = resolveBuildTemplateId(args[2]);
            force = true;
        } else if (args.length != 2) {
            send(sender, buildUsage());
            return;
        }

        if (buildManager.isBusy()) {
            sendBuildBusy(sender);
            return;
        }

        Player executor = sender instanceof Player player ? player : null;

        if (templateId == null) {
            // テンプレート未指定時は開いているすべての建築ワールドを対象にする。
            Set<String> templateIds = buildManager.getOpenTemplateIds();
            if (templateIds.isEmpty()) {
                send(sender, message("build.not_active", Map.of(),
                        "<yellow>建築中のワールドはありません。</yellow>"));
                return;
            }
            if (!force) {
                for (String openTemplate : templateIds) {
                    if (buildManager.hasOtherPlayers(openTemplate, executor)) {
                        send(sender, buildOthersMessage(save, openTemplate));
                        return;
                    }
                }
            }
            String label = String.join(", ", templateIds);
            send(sender, buildProgressMessage(save, label));
            reportBuildResult(sender, save, label, save
                    ? buildManager.saveAllBuildWorldsAsync()
                    : buildManager.unsaveAllBuildWorldsAsync());
            return;
        }

        if (!buildManager.isBuildWorldOpen(templateId)) {
            send(sender, message("build.not_open", Map.of("template", esc(templateId)),
                    "<yellow>建築ワールド '<white>{template}</white>' は開かれていません。"
                            + "/admin build create <white>{template}</white> で作成してください。</yellow>"));
            return;
        }
        if (!force && buildManager.hasOtherPlayers(templateId, executor)) {
            send(sender, buildOthersMessage(save, templateId));
            return;
        }

        send(sender, buildProgressMessage(save, templateId));
        reportBuildResult(sender, save, templateId, save
                ? buildManager.saveBuildWorldAsync(templateId)
                : buildManager.unsaveBuildWorldAsync(templateId));
    }

    /**
     * 建築ワールドの保存／破棄結果を実行者へ通知する。
     *
     * @param sender   実行者
     * @param save     {@code true} の場合は保存、{@code false} の場合は破棄
     * @param template 対象テンプレートの表示名
     * @param future   処理 future
     */
    private void reportBuildResult(CommandSender sender, boolean save, String template,
                                   CompletableFuture<Boolean> future) {
        future.whenComplete((success, error) -> {
            if (error != null) {
                send(sender, message("build.save_failed", Map.of("error", esc(rootMessage(error))),
                        "<red>建築ワールドの処理に失敗しました: <white>{error}</white></red>"));
                return;
            }
            if (!Boolean.TRUE.equals(success)) {
                send(sender, message("build.save_failed",
                        Map.of("error", esc("処理を完了できませんでした")),
                        "<red>建築ワールドの処理に失敗しました: <white>{error}</white></red>"));
                return;
            }
            send(sender, message(save ? "build.saved" : "build.unsaved",
                    Map.of("template", esc(template)),
                    save
                            ? "<green>テンプレート <white>{template}</white> を保存しました。Hubに戻ります</green>"
                            : "<green>テンプレート <white>{template}</white> の変更を破棄しました。Hubに戻ります</green>"));
        });
    }

    /**
     * 他のプレイヤーが建築ワールドにいる場合の警告メッセージを返す。
     *
     * @param save       {@code true} の場合は保存、{@code false} の場合は破棄
     * @param templateId テンプレート ID
     */
    private String buildOthersMessage(boolean save, String templateId) {
        String command = (save ? SAVE : UNSAVE) + " " + templateId + " " + FORCE;
        return message(save ? "build.others_save" : "build.others_unsave",
                Map.of("template", esc(templateId), "command", esc(command)),
                "<yellow>他のプレイヤーが建築ワールド '<white>{template}</white>' にいます。"
                        + "全員退出するか、強制する場合は <white>/admin build {command}</white> を実行してください</yellow>");
    }

    /**
     * 建築ワールドの保存／破棄中メッセージを返す。
     *
     * @param save       {@code true} の場合は保存、{@code false} の場合は破棄
     * @param templateId テンプレート ID（カンマ区切りの複数指定可）
     */
    private String buildProgressMessage(boolean save, String templateId) {
        return message(save ? "build.saving" : "build.discarding",
                Map.of("template", esc(templateId)),
                save
                        ? "<yellow>建築ワールド '<white>{template}</white>' を保存しています...</yellow>"
                        : "<yellow>建築ワールド '<white>{template}</white>' の変更を破棄しています...</yellow>");
    }

    /**
     * {@code /admin build list}: 開いている建築ワールドを一覧表示する。
     *
     * @param sender       実行者
     * @param buildManager 建築モード管理
     */
    private void handleBuildList(CommandSender sender, BuildManager buildManager) {
        Set<String> templateIds = buildManager.getOpenTemplateIds();
        if (templateIds.isEmpty()) {
            send(sender, message("build.list_empty", Map.of(),
                    "<gray>開いている建築ワールドはありません。</gray>"));
            return;
        }

        send(sender, message("build.list_header",
                Map.of("count", Integer.toString(templateIds.size())),
                "<green>建築ワールド一覧</green> <gray>(<white>{count}</white> 件)</gray>"));
        for (String templateId : templateIds) {
            World world = buildManager.getBuildWorld(templateId);
            send(sender, message("build.list_entry", Map.of(
                            "template", esc(templateId),
                            "world", esc(world == null ? "不明" : world.getName()),
                            "players", Integer.toString(buildManager.getBuildWorldPlayerCount(templateId)),
                            "owner", esc(ownerName(buildManager.getBuildWorldOwner(templateId))),
                            "last_active", esc(formatTimestamp(
                                    buildManager.getBuildWorldLastActive(templateId)))),
                    "<gray>-</gray> <white>{template}</white> <dark_gray>({world})</dark_gray> "
                            + "<gray>プレイヤー: </gray><white>{players}</white> <dark_gray>|</dark_gray> "
                            + "<gray>作成者: </gray><white>{owner}</white> <dark_gray>|</dark_gray> "
                            + "<gray>最終操作: </gray><white>{last_active}</white>"));
        }
    }

    /**
     * {@code /admin build info [yml]}: 建築ワールドの状態を表示する。
     *
     * @param sender       実行者
     * @param buildManager 建築モード管理
     * @param templateName テンプレート名（{@code config/LayerData/} の YAML 名・拡張子なし）
     */
    private void handleBuildInfo(CommandSender sender, BuildManager buildManager, String templateName) {
        String templateId = resolveBuildTemplateId(templateName);
        World world = buildManager.getBuildWorld(templateId);
        if (world == null) {
            send(sender, message("build.not_open", Map.of("template", esc(templateName)),
                    "<yellow>建築ワールド '<white>{template}</white>' は開かれていません。"
                            + "/admin build create <white>{template}</white> で作成してください。</yellow>"));
            return;
        }

        List<String> playerNames = buildManager.getActivePlayerNames(templateId);
        String players = playerNames.isEmpty() ? "なし" : String.join(", ", playerNames);
        send(sender, message("build.info", Map.of(
                        "template", esc(templateName),
                        "world", esc(world.getName()),
                        "players", esc(players),
                        "owner", esc(ownerName(buildManager.getBuildWorldOwner(templateId))),
                        "last_active", esc(formatTimestamp(
                                buildManager.getBuildWorldLastActive(templateId)))),
                "<gray>建築モード:</gray> <white>{template}</white> "
                        + "<dark_gray>|</dark_gray> <gray>ワールド:</gray> <white>{world}</white> "
                        + "<dark_gray>|</dark_gray> <gray>プレイヤー:</gray> <white>{players}</white> "
                        + "<dark_gray>|</dark_gray> <gray>作成者:</gray> <white>{owner}</white> "
                        + "<dark_gray>|</dark_gray> <gray>最終操作:</gray> <white>{last_active}</white>"));
    }

    /**
     * 建築ワールドの作成者名を取得する。
     *
     * @param ownerId 作成者の UUID
     * @return 表示用の作成者名。記録が無い場合は {@code 不明}
     */
    private static String ownerName(UUID ownerId) {
        if (ownerId == null) {
            return "不明";
        }
        String name = Bukkit.getOfflinePlayer(ownerId).getName();
        return name == null ? ownerId.toString() : name;
    }

    /**
     * エポックミリ秒を表示用の日時文字列へ整形する。
     *
     * @param epochMillis エポックミリ秒
     * @return 整形済みの日時。未記録の場合は {@code 不明}
     */
    private static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0L) {
            return "不明";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * {@code /admin layer info <all|online>}: 読み込み済みレイヤーインスタンスを一覧表示する。
     * <p>
     * {@code all} はプレイヤーが 0 人のインスタンスも含め、アイドルアンロードまでの
     * 残り秒数を表示する。{@code online} はプレイヤーがいるインスタンスだけを表示する。
     */
    private void handleLayer(CommandSender sender, String[] args) {
        if (args.length != 3 || !INFO.equalsIgnoreCase(args[1])) {
            send(sender, layerUsage());
            return;
        }

        String target = args[2].toLowerCase(Locale.ROOT);
        boolean onlineOnly;
        if (ONLINE.equals(target)) {
            onlineOnly = true;
        } else if (ALL.equals(target)) {
            onlineOnly = false;
        } else {
            send(sender, layerUsage());
            return;
        }

        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            send(sender, message("layer.unavailable", Map.of(),
                    "<yellow>レイヤーマネージャーが初期化されていません。</yellow>"));
            return;
        }

        // 表示対象をインスタンス ID 順に整理する。
        Map<String, World> visible = new TreeMap<>();
        for (Map.Entry<String, World> entry : layerManager.getLoadedWorlds().entrySet()) {
            World world = entry.getValue();
            if (world == null) {
                continue;
            }
            if (onlineOnly && world.getPlayers().isEmpty()) {
                continue;
            }
            visible.put(entry.getKey(), world);
        }

        if (visible.isEmpty()) {
            send(sender, message("layer.empty", Map.of(),
                    "<gray>表示できるレイヤーインスタンスはありません。</gray>"));
            return;
        }

        send(sender, message("layer.header", Map.of("count", Integer.toString(visible.size())),
                "<green>レイヤー一覧</green> <gray>(<white>{count}</white> 件)</gray>"));

        long now = System.currentTimeMillis();
        for (Map.Entry<String, World> entry : visible.entrySet()) {
            String instanceId = entry.getKey();
            World world = entry.getValue();
            String templateId = layerManager.getTemplateId(instanceId);
            if (templateId == null || templateId.isBlank()) {
                templateId = "不明";
            }

            List<Player> players = world.getPlayers();
            Map<String, String> placeholders = Map.of(
                    "template", esc(templateId),
                    "world", esc(instanceId),
                    "players", Integer.toString(players.size()));

            if (players.isEmpty()) {
                long remaining = plugin.getLayerCleanupTask() == null
                        ? LayerCleanupTask.IDLE_TIMEOUT_SECONDS
                        : plugin.getLayerCleanupTask().remainingSeconds(instanceId, now);
                send(sender, message("layer.entry_idle", Map.of(
                                "template", placeholders.get("template"),
                                "world", placeholders.get("world"),
                                "seconds", Long.toString(remaining)),
                        "<gray>-</gray> <white>{template}</white> "
                                + "<dark_gray>({world})</dark_gray> "
                                + "<gray>プレイヤー: </gray><white>0</white> "
                                + "<dark_gray>| </dark_gray><yellow>アンロードまで最大 {seconds} 秒</yellow>"));
                continue;
            }

            List<String> names = new ArrayList<>();
            for (Player player : players) {
                names.add(player.getName());
            }
            send(sender, message("layer.entry", Map.of(
                            "template", placeholders.get("template"),
                            "world", placeholders.get("world"),
                            "players", placeholders.get("players"),
                            "names", esc(String.join(", ", names))),
                    "<green>-</green> <white>{template}</white> "
                            + "<dark_gray>({world})</dark_gray> "
                            + "<gray>プレイヤー: </gray><white>{players}</white> "
                            + "<dark_gray>|</dark_gray> <white>{names}</white>"));
        }
    }

    /**
     * {@code /admin layer} の使用方法メッセージを返す。
     */
    private String layerUsage() {
        return message("layer.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin layer info <all|online></white>");
    }

    /**
     * 建築ワールドの処理中メッセージを送信する。
     */
    private void sendBuildBusy(CommandSender sender) {
        send(sender, message("build.busy", Map.of(),
                "<yellow>建築ワールドの処理中です。完了してから再実行してください。</yellow>"));
    }

    /**
     * このサーバーが建築モードを許可するチャンネル 1 か判定する。
     */
    private boolean isBuildChannel() {
        return plugin.getChannelConfig() != null && plugin.getChannelConfig().isBuildChannel();
    }

    /**
     * {@code /admin stop}: サーバーを安全に停止する。
     * <p>
     * 以下の順序で、非同期処理（DB 保存）と同期処理（ワールド操作）を制御する。
     * <ol>
     *     <li>全オンラインプレイヤーのデータを保存し、DB を Flush する（非同期）</li>
     *     <li>レイヤー内のプレイヤーをメインワールドのスポーンへ退避する（同期）</li>
     *     <li>全テンポラリレイヤーをアンロードし、ディレクトリを削除する（同期）</li>
     *     <li>メインワールド等の永続ワールドを保存する（同期）</li>
     *     <li>{@link Bukkit#shutdown()} でサーバーを停止する（同期）</li>
     * </ol>
     * 停止処理は多重実行を防ぐため、実行中の再実行要求は無視する。
     */
    private void stop(CommandSender sender) {
        if (!shuttingDown.compareAndSet(false, true)) {
            send(sender, PREFIX + "<yellow>安全停止処理は既に実行中です。</yellow>");
            return;
        }

        send(sender, PREFIX + "<yellow>安全停止処理を開始します...</yellow>");
        Bukkit.broadcast(MINI.deserialize(PREFIX
                + "<red>サーバーを安全に停止します。しばらくお待ちください。</red>"));

        // (a) 全プレイヤーのデータ保存 + Flush（非同期）。完了後にメインスレッドへ戻す。
        saveAllPlayerData().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning(
                        "プレイヤーデータの保存・Flush に失敗しました: " + rootMessage(throwable));
            }
            Bukkit.getScheduler().runTask(plugin, () -> shutdownOnMainThread(sender));
        });
    }

    /**
     * オンライン全員のプレイヤーデータを保存し、その後 DB を Flush する。
     * <p>
     * 個々の保存失敗はログに記録したうえで処理を継続し、停止処理を妨げない。
     *
     * @return すべての保存および Flush の完了を表す future
     */
    private CompletableFuture<Void> saveAllPlayerData() {
        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            plugin.getLogger().warning(
                    "PlayerService が初期化されていないため、プレイヤーデータの保存をスキップしました。");
            return CompletableFuture.completedFuture(null);
        }

        return playerService.saveAllAsync()
                .thenRun(() -> {
                    if (plugin.getLoggerService() != null) {
                        plugin.getLoggerService().log(
                                com.zeloria.core.LoggerService.Category.DATA,
                                "プレイヤーデータ保存（安全停止）: 全オンラインプレイヤー");
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "プレイヤーデータの保存・Flush に失敗しました: " + rootMessage(throwable));
                    return null;
                });
    }

    /**
     * メインスレッドで実行する停止処理の本体（手順 b〜e）。
     */
    private void shutdownOnMainThread(CommandSender sender) {
        LayerManager layerManager = plugin.getLayerManager();

        // (b) レイヤー内（Hub を含む）プレイヤーをメインワールドのスポーンへ退避
        int evacuatedCount = countLayerPlayers(layerManager);
        boolean evacuated = true;
        try {
            evacuated = layerManager == null || layerManager.evacuateAllPlayersToMainWorld();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "[StopDebug] [1/4] プレイヤー退避中に例外が発生しました。", e);
            evacuated = false;
        }
        plugin.getLogger().info("[StopDebug] [1/4] プレイヤー退避完了: " + evacuatedCount + " 人");
        send(sender, PREFIX + (evacuated
                ? "<green>[1/4]</green> <white>プレイヤーをメインワールドへ退避しました。</white>"
                : "<yellow>[1/4]</yellow> <white>プレイヤーの退避を完了できませんでした（ログを確認してください）。</white>"));

        // (c) 全テンポラリレイヤー（Hub 含む）をアンロード & ディレクトリ削除
        int unloaded = 0;
        int cleaned = 0;
        if (layerManager != null) {
            try {
                unloaded = layerManager.unloadAllAndDelete();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "[StopDebug] [2/4] テンポラリレイヤーの削除中に例外が発生しました。", e);
            }
            try {
                cleaned = layerManager.cleanupOrphanedInstances();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "[StopDebug] [2/4] 残存インスタンスの掃除中に例外が発生しました。", e);
            }
        }
        plugin.getLogger().info("[StopDebug] [2/4] テンポラリレイヤー削除完了: "
                + unloaded + " 件（Hub含む）");
        send(sender, PREFIX + "<green>[2/4]</green> <white>テンポラリレイヤーを削除しました "
                + "(アンロード: " + unloaded + " 件, 残存掃除: " + cleaned + " 件)</white>");

        // (d) 永続ワールドの自動保存を維持（手動 save は行わない）
        int saved = 0;
        try {
            saved = savePersistentWorlds(layerManager);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "[StopDebug] [3/4] 永続ワールドの自動保存設定中に例外が発生しました。", e);
        }
        plugin.getLogger().info("[StopDebug] [3/4] 永続ワールド保存完了: " + saved + " 件");
        send(sender, PREFIX + "<green>[3/4]</green> <white>永続ワールドを保存しました ("
                + saved + " 件)</white>");

        // (e) 安全に停止
        plugin.getLogger().info("[StopDebug] [4/4] 停止処理開始");
        send(sender, PREFIX + "<red>[4/4]</red> <white>サーバーを停止します。</white>");
        plugin.getLogger().info("安全停止処理が完了したため、サーバーをシャットダウンします。");
        Bukkit.shutdown();
    }

    /**
     * レイヤーワールド内に残っているプレイヤー数を数える（ログ用）。
     */
    private static int countLayerPlayers(LayerManager layerManager) {
        if (layerManager == null) {
            return 0;
        }
        int count = 0;
        for (World world : layerManager.getLoadedWorlds().values()) {
            count += world.getPlayers().size();
        }
        return count;
    }

    /**
     * 永続ワールドの自動保存設定を維持する。
     * <p>
     * Paper では {@link World#save()} の手動呼び出しが非推奨（警告が出る）ため、
     * ワールドのブロックデータはサーバー停止時の自動保存に任せ、
     * ここでは自動保存が無効化されていないことだけを確認する。
     *
     * @return 自動保存を有効化したワールド数
     */
    private int savePersistentWorlds(LayerManager layerManager) {
        Map<String, World> layers = layerManager == null ? Map.of() : layerManager.getLoadedWorlds();

        int saved = 0;
        for (World world : Bukkit.getWorlds()) {
            if (layers.containsValue(world)) {
                // テンポラリレイヤーは破棄対象のため対象外。
                continue;
            }
            try {
                world.setAutoSave(true);
                saved++;
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "ワールドの自動保存設定に失敗しました: " + world.getName() + " (" + e.getMessage() + ")");
            }
        }
        return saved;
    }

    /**
     * {@code /admin give <armor|material|weapon> <id> [amount]} のディスパッチ。
     * <p>
     * アイテム定義は {@link ItemService} から取得し、実行者自身のプレイヤーインベントリへ格納する。
     */
    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, message("command.player_only", Map.of(),
                    "<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return;
        }

        if (args.length < 3) {
            send(sender, giveUsage());
            return;
        }

        ItemCategory category = ItemCategory.fromKey(args[1]);
        if (category == null) {
            send(sender, giveUsage());
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                send(sender, giveInvalidAmount());
                return;
            }
            if (amount < 1 || amount > MAX_GIVE_AMOUNT) {
                send(sender, giveInvalidAmount());
                return;
            }
        }

        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            send(sender, PREFIX + "<red>ItemService が初期化されていません。</red>");
            return;
        }

        Optional<ItemData> found = itemService.find(category, args[2]);
        if (found.isEmpty()) {
            send(sender, message("give.not_found", Map.of("item_id", args[2]),
                    "<red>アイテム設定が見つかりません: <white>{item_id}</white></red>"));
            return;
        }

        giveItem(player, found.get(), amount);
    }

    /**
     * 生成したアイテムをプレイヤーインベントリへ追加する。
     * <p>
     * バニラのスタック上限ごとに分割して追加し、入りきらなかった個数を溢れとして扱う。
     */
    private void giveItem(Player player, ItemData definition, int amount) {
        ItemService itemService = plugin.getItemService();
        if (itemService == null) {
            send(player, PREFIX + "<red>ItemService が初期化されていません。</red>");
            return;
        }

        ItemStack prototype = itemService.createItem(definition);
        int maxStack = Math.max(1, prototype.getMaxStackSize());
        int remaining = amount;
        int given = 0;

        while (remaining > 0) {
            int chunk = Math.min(remaining, maxStack);
            ItemStack stack = prototype.clone();
            stack.setAmount(chunk);

            int rejected = 0;
            for (ItemStack overflow : player.getInventory().addItem(stack).values()) {
                rejected += overflow.getAmount();
            }
            given += chunk - rejected;
            remaining -= chunk;

            if (rejected > 0) {
                // インベントリが満杯のため、これ以上は入らない。
                break;
            }
        }

        int leftover = amount - given;
        Map<String, String> placeholders = Map.of(
                "item_id", definition.id(),
                "item_name", MINI.stripTags(definition.name()),
                "amount", String.valueOf(given),
                "requested", String.valueOf(amount),
                "leftover", String.valueOf(leftover)
        );

        if (given <= 0) {
            send(player, message("give.full", placeholders,
                    "<red>プレイヤーインベントリに空きがありません。</red>"));
            return;
        }
        if (leftover > 0) {
            send(player, message("give.partial", placeholders,
                    "<yellow>アイテム</yellow> <white>{item_name}</white> <gray>を</gray> "
                            + "<white>{amount}</white> <gray>個付与しました。</gray> "
                            + "<dark_gray>(溢れ: <gray>{leftover}</gray> 個)</dark_gray>"));
        } else {
            send(player, message("give.success", placeholders,
                    "<green>アイテム</green> <white>{item_name}</white> <gray>を</gray> "
                            + "<white>{amount}</white> <gray>個付与しました。</gray>"));
        }

        LoggerService logger = plugin.getLoggerService();
        if (logger != null) {
            logger.log(LoggerService.Category.COMMAND,
                    "アイテム付与: id=" + definition.id()
                            + ", category=" + definition.category().key()
                            + ", requested=" + amount
                            + ", given=" + given
                            + ", leftover=" + leftover
                            + ", player=" + player.getName());
        }
    }

    /**
     * {@code /admin info <player>} の本体。
     * <p>
     * オンライン・オフラインを問わず DB からプレイヤーデータを取得し、
     * 最終接続インスタンス・最終ログイン日時とあわせて表示する。
     * オンラインでキャッシュ済みの場合は、保存前の最新値を優先する。
     *
     * @param sender 実行者
     * @param args   コマンド引数（{@code [info, player]}）
     */
    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, infoUsage());
            return;
        }

        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            send(sender, message("info.error", Map.of("error", "PlayerService 未初期化"),
                    "<red>プレイヤー情報を取得できません: <gray>{error}</gray></red>"));
            return;
        }

        String query = args[1];
        Player online = Bukkit.getPlayerExact(query);
        PlayerData cached = online == null ? null : PlayerData.getCached(online.getUniqueId());

        playerService.getRepository().findStatus(query).whenComplete((result, error) -> {
            PlayerStatus status = result == null ? null : result.orElse(null);
            PlayerSnapshot snapshot = status == null ? null : status.snapshot();
            if (cached != null) {
                // オンライン中の値は保存前の変更を含むため、DB より優先する。
                snapshot = cached.snapshot();
            }

            if (snapshot == null) {
                if (error != null) {
                    send(sender, message("info.error",
                            Map.of("error", esc(rootMessage(error))),
                            "<red>プレイヤー情報の取得に失敗しました: <gray>{error}</gray></red>"));
                } else {
                    send(sender, message("info.not_found", Map.of("player", esc(query)),
                            "<red>プレイヤーデータが見つかりません: <white>{player}</white></red>"));
                }
                return;
            }

            String lastInstance = status == null ? "" : status.lastInstance();
            sendPlayerInfo(sender, snapshot,
                    lastInstance.isBlank() ? "-" : lastInstance,
                    formatTimestamp(status == null ? null : status.lastLogin()),
                    formatTimestamp(status == null ? null : status.lastUpdated()));
        });
    }

    /**
     * 取得済みのプレイヤー情報をチャットへ出力する。
     */
    private void sendPlayerInfo(CommandSender sender, PlayerSnapshot snapshot,
                                String lastInstance, String lastLogin, String lastUpdated) {
        LevelService levelService = plugin.getLevelService();
        long requiredExp = levelService == null ? 0L : levelService.requiredExp(snapshot.level());

        send(sender, message("info.header", Map.of("player", esc(snapshot.playerName())),
                "<gold>=== <white>{player}</white> ===</gold>"));
        send(sender, message("info.identity", Map.of(
                        "uuid", snapshot.uuid().toString(),
                        "class", esc(text(snapshot.currentClassId(), "-"))),
                "<gray>UUID:</gray> <white>{uuid}</white> <dark_gray>|</dark_gray> "
                        + "<gray>Class:</gray> <aqua>{class}</aqua>"));
        send(sender, message("info.level", Map.of(
                        "level", String.valueOf(snapshot.level()),
                        "exp", String.valueOf(snapshot.exp()),
                        "required", String.valueOf(requiredExp)),
                "<gray>Level:</gray> <yellow>{level}</yellow> <dark_gray>|</dark_gray> "
                        + "<gray>EXP:</gray> <white>{exp}</white><gray>/</gray><white>{required}</white>"));
        send(sender, message("info.vitals", Map.of(
                        "health", decimal(snapshot.currentHealth()),
                        "max_health", decimal(snapshot.maxHealth()),
                        "mana", decimal(snapshot.currentMana()),
                        "max_mana", decimal(snapshot.maxMana()),
                        "money", String.valueOf(snapshot.money())),
                "<gray>HP:</gray> <red>{health}</red><gray>/</gray><white>{max_health}</white> "
                        + "<dark_gray>|</dark_gray> <gray>MP:</gray> <blue>{mana}</blue>"
                        + "<gray>/</gray><white>{max_mana}</white> <dark_gray>|</dark_gray> "
                        + "<gray>Money:</gray> <gold>{money}</gold>"));
        send(sender, message("info.combat", Map.of(
                        "attack", decimal(snapshot.attackPower()),
                        "defense1", decimal(snapshot.defense1()),
                        "defense2", decimal(snapshot.defense2()),
                        // 旧テンプレート（DEF: {defense}）との互換用。合計値を渡す。
                        "defense", decimal(snapshot.defense1() + snapshot.defense2()),
                        "crit_rate", decimal(snapshot.criticalRate()),
                        "crit_damage", decimal(snapshot.criticalDamage()),
                        "accuracy", decimal(snapshot.accuracy()),
                        "evasion", decimal(snapshot.evasion()),
                        "cdr", decimal(snapshot.cooldownReduction() * 100.0),
                        "move_speed", decimal(
                                snapshot.walkSpeed() / PlayerStats.DEFAULT_MOVE_SPEED * 100.0)),
                "<gray>ATK:</gray> <white>{attack}</white> <dark_gray>|</dark_gray> "
                        + "<gray>DEF1:</gray> <white>{defense1}</white> <dark_gray>|</dark_gray> "
                        + "<gray>DEF2:</gray> <white>{defense2}</white> <dark_gray>|</dark_gray> "
                        + "<gray>CRIT:</gray> <white>{crit_rate}%</white> <dark_gray>|</dark_gray> "
                        + "<gray>CRIT DMG:</gray> <white>{crit_damage}%</white> <dark_gray>|</dark_gray> "
                        + "<gray>ACC:</gray> <white>{accuracy}%</white> <dark_gray>|</dark_gray> "
                        + "<gray>EVA:</gray> <white>{evasion}%</white> <dark_gray>|</dark_gray> "
                        + "<gray>CDR:</gray> <white>{cdr}%</white> <dark_gray>|</dark_gray> "
                        + "<gray>Speed:</gray> <white>{move_speed}%</white>"));
        send(sender, message("info.regen", Map.of(
                        "hp_flat", decimal(snapshot.healthRegenFlat()),
                        "hp_percent", decimal(snapshot.healthRegenPercent() * 100.0),
                        "mp_flat", decimal(snapshot.manaRegenFlat()),
                        "mp_percent", decimal(snapshot.manaRegenPercent() * 100.0)),
                "<gray>Regen:</gray> HP <white>{hp_flat}</white> + <white>{hp_percent}%</white>"
                        + " <dark_gray>|</dark_gray> MP <white>{mp_flat}</white> + "
                        + "<white>{mp_percent}%</white>"));
        send(sender, message("info.connection", Map.of(
                        "instance", esc(lastInstance),
                        "last_login", esc(lastLogin),
                        "last_updated", esc(lastUpdated)),
                "<gray>Last instance:</gray> <white>{instance}</white> <dark_gray>|</dark_gray> "
                        + "<gray>Login:</gray> <white>{last_login}</white> <dark_gray>|</dark_gray> "
                        + "<gray>Saved:</gray> <white>{last_updated}</white>"));

        Map<EquipSlot, ItemStack> equipment;
        try {
            equipment = InventorySerializer.decodeEquipment(snapshot.virtualEquipmentBase64());
        } catch (RuntimeException e) {
            equipment = Map.of();
        }
        for (EquipSlot slot : EquipSlot.values()) {
            ItemStack item = equipment.get(slot);
            send(sender, message("info.equipment_entry", Map.of(
                            "slot", esc(slot.getDisplayName()),
                            "item", esc(describeItem(item))),
                    "<gray>└ {slot}: <white>{item}</white></gray>"));
        }
    }

    /**
     * アイテムを管理表示用の 1 行テキストへ変換する。
     */
    private String describeItem(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return "-";
        }
        ItemService itemService = plugin.getItemService();
        ItemData data = itemService == null ? null : itemService.findItem(item).orElse(null);
        if (data != null) {
            return data.id() + " (" + MINI.stripTags(data.name()) + ")";
        }
        return item.getType().name() + " x" + item.getAmount();
    }

    /**
     * {@code /admin gui <yml>} の本体。
     * <p>
     * GUI 設定 ID を受け取り、対応する GUI を実行者へ開く。
     * {@code menu} は {@link com.zeloria.gui.MenuGui}、{@code class_select} は
     * クラス選択 GUI を開く。
     *
     * @param sender 実行者
     * @param args   コマンド引数（{@code [gui, yml]}）
     */
    private void handleGui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, message("command.player_only", Map.of(),
                    "<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return;
        }
        if (args.length < 2) {
            send(sender, guiUsage());
            return;
        }

        YamlLoader yamlLoader = plugin.getYamlLoader();
        FileConfiguration config = yamlLoader == null ? null : yamlLoader.getConfig(args[1]);
        if (config == null) {
            send(sender, message("gui.not_found", Map.of("yml", esc(args[1])),
                    "<red>GUI 設定が見つかりません: <white>{yml}</white></red>"));
            return;
        }

        String id = text(config.getString("id"), stripExtension(args[1]));
        switch (id.toLowerCase(Locale.ROOT)) {
            case "class_select" -> {
                if (plugin.getClassSelectGUI() == null) {
                    send(sender, unavailableGui(args[1]));
                    return;
                }
                plugin.getClassSelectGUI().open(player);
            }
            case "menu" -> {
                if (plugin.getMenuService() == null) {
                    send(sender, unavailableGui(args[1]));
                    return;
                }
                plugin.getMenuService().open(player);
            }
            default -> send(sender, message("gui.unsupported", Map.of("yml", esc(args[1])),
                    "<red>この GUI 設定はコマンドから開けません: <white>{yml}</white></red>"));
        }
    }

    private String unavailableGui(String input) {
        return message("gui.unavailable", Map.of("yml", esc(input)),
                "<red>GUI を開けません（未初期化）: <white>{yml}</white></red>");
    }

    private String guiUsage() {
        return message("gui.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin gui \\<yml></white>");
    }

    private String infoUsage() {
        return message("info.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin info \\<player></white>");
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(timestamp.toInstant());
    }

    /**
     * {@code /admin give} の使用方法メッセージを返す。
     */
    private String giveUsage() {
        return message("give.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin give \\<armor|material|weapon> \\<id> [amount]</white>");
    }

    /**
     * 個数指定が不正な場合のメッセージを返す。
     */
    private String giveInvalidAmount() {
        return message("give.invalid_amount", Map.of("max", String.valueOf(MAX_GIVE_AMOUNT)),
                "<red>個数は <white>1</white> 〜 <white>{max}</white> の範囲で指定してください。</red>");
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, usage());
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case YAML -> testYaml(sender, Arrays.copyOfRange(args, 2, args.length));
            case DB -> testDb(sender);
            default -> send(sender, usage());
        }
    }

    /**
     * {@code /admin test yaml [<folder>] [<id>]} の本体。
     * <p>
     * 引数なしの場合はフォルダ別件数のサマリーを、引数ありの場合は
     * 該当 YAML の内容を 1〜2 行に凝縮して表示する。
     */
    private void testYaml(CommandSender sender, String[] rest) {
        YamlLoader yamlLoader = plugin.getYamlLoader();
        if (yamlLoader == null) {
            send(sender, PREFIX + "<red>YamlLoader が初期化されていません。</red>");
            return;
        }

        if (rest.length == 0) {
            sendYamlSummary(sender, yamlLoader, null);
            return;
        }

        // 引数が 1 つでもフォルダ名（gui / gui/ / LayerData など）なら
        // 設定ファイルとして解決せず、フォルダ配下の件数サマリーを表示する。
        if (rest.length == 1) {
            String folderKey = normalizeFolder(rest[0]);
            if (isFolder(yamlLoader, folderKey)) {
                sendYamlSummary(sender, yamlLoader, folderKey);
                return;
            }
        }

        // 第 2 引数がサブフォルダ（WeaponData archer など）の場合も、
        // 配下の YAML 件数サマリーを表示する。
        if (rest.length >= 2) {
            String folderKey = normalizeFolder(rest[0]);
            String subKey = normalizeFolder(rest[1]);
            String nested = folderKey.isEmpty() ? subKey : folderKey + "/" + subKey;
            if (!subKey.isEmpty() && isFolder(yamlLoader, nested)) {
                sendYamlSummary(sender, yamlLoader, nested);
                return;
            }
        }

        String folder = rest.length >= 2 ? normalizeFolder(rest[0]) : null;
        String id = rest.length >= 2 ? rest[1] : rest[0];

        YamlEntry entry = resolve(yamlLoader, folder, id);
        if (entry == null) {
            String target = folder == null ? id : folder + " " + id;
            send(sender, PREFIX + "<red>指定された YAML が見つかりません: " + esc(target) + "</red>");
            return;
        }
        sendYamlEntry(sender, entry);
    }

    /**
     * フォルダごとの読み込み件数を、色分けした見出しと階層で出力する。
     *
     * @param folderFilter 対象フォルダ（{@code null} の場合は全フォルダ）
     */
    private void sendYamlSummary(CommandSender sender, YamlLoader yamlLoader, String folderFilter) {
        if (folderFilter != null && isExcludedFolder(folderFilter)) {
            send(sender, PREFIX + "<red>" + EXCLUDED_FOLDER + " は表示対象外です。</red>");
            return;
        }

        Map<String, List<String>> entries = new TreeMap<>();
        int total = 0;
        for (String key : yamlLoader.getKeys()) {
            String folder = folderOf(key);
            if (folder.isEmpty() || isExcludedFolder(folder)) {
                continue;
            }
            if (folderFilter != null && !matchesFolder(folder, folderFilter)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            String id = config == null ? null : config.getString("id");
            entries.computeIfAbsent(folder, ignored -> new ArrayList<>())
                    .add(text(id, stripExtension(fileNameOf(key))));
            total++;
        }

        String scope = folderFilter == null
                ? "ロード済み YAML: <yellow>" + total + "</yellow> 件"
                : "フォルダ <white>" + esc(folderFilter) + "</white>: <yellow>" + total + "</yellow> 件";
        send(sender, PREFIX + "<white>" + scope + "</white>");

        if (entries.isEmpty()) {
            send(sender, "  <gray>└ 対象の YAML はありません。</gray>");
            return;
        }

        for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
            String folder = entry.getKey();
            List<String> ids = entry.getValue();
            ids.sort(String.CASE_INSENSITIVE_ORDER);

            String color = folderColor(folderOf(folder).isEmpty() ? folder : folderOf(folder));
            send(sender, "  <" + color + "><bold>▶ " + esc(folder) + "</bold></" + color + ">"
                    + " <gray>(" + ids.size() + ")</gray>"
                    + " <dark_gray>" + esc(folder) + "/</dark_gray>");

            int shown = Math.min(ids.size(), MAX_YAML_SUMMARY_IDS);
            for (int i = 0; i < shown; i++) {
                boolean last = i == shown - 1 && ids.size() <= MAX_YAML_SUMMARY_IDS;
                send(sender, "    <dark_gray>" + (last ? "└" : "├") + "</dark_gray> "
                        + "<white>" + esc(ids.get(i)) + "</white>");
            }
            if (ids.size() > shown) {
                send(sender, "    <dark_gray>└</dark_gray> <gray>…ほか "
                        + (ids.size() - shown) + " 件</gray>");
            }
        }

        send(sender, "<gray>└ <white>/admin test yaml \\<id></white> / "
                + "<white>/admin test yaml \\<folder> \\<id></white> / "
                + "<white>/admin test yaml \\<folder>/\\<subfolder>/\\<id></white> "
                + "で詳細を参照できます。</gray>");
    }

    /**
     * フォルダ名に対応する見出し色を返す。
     */
    private static String folderColor(String folder) {
        return switch (folder) {
            case "WeaponData" -> "gold";
            case "ArmorData" -> "blue";
            case "SkillData" -> "aqua";
            case "MobData" -> "red";
            case "MaterialData" -> "green";
            case "ClassData" -> "light_purple";
            case "LevelData" -> "yellow";
            case "LayerData" -> "dark_green";
            case "MessageData" -> "gray";
            case "gui" -> "light_purple";
            default -> "white";
        };
    }

    /**
     * フォルダが指定フォルダ自身、またはその配下か判定する（大文字小文字は区別しない）。
     */
    private static boolean matchesFolder(String folder, String filter) {
        if (folder.equalsIgnoreCase(filter)) {
            return true;
        }
        String prefix = filter.toLowerCase(Locale.ROOT) + "/";
        return folder.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    /**
     * 表示・タブ補完の対象外フォルダ（{@link #EXCLUDED_FOLDER}）か判定する。
     * <p>
     * {@code MessageData} 本体と、その配下のサブフォルダをすべて対象外とする。
     */
    private static boolean isExcludedFolder(String folder) {
        String normalized = normalizeFolder(folder);
        if (normalized.isEmpty()) {
            return false;
        }
        String excluded = EXCLUDED_FOLDER.toLowerCase(Locale.ROOT);
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.equals(excluded) || lower.startsWith(excluded + "/");
    }

    /**
     * フォルダ名の末尾スラッシュ・バックスラッシュを正規化する。
     */
    private static String normalizeFolder(String folder) {
        if (folder == null) {
            return "";
        }
        String normalized = folder.trim().replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 読み込み済み YAML に指定フォルダが存在するか判定する（大文字小文字は区別しない）。
     * <p>
     * 配下に YAML が 1 件も無いフォルダでも「フォルダ指定」として扱えるよう、
     * 読み込み済みキーに該当が無い場合は {@code config/} 配下の実ディレクトリを確認する。
     * これにより {@code /admin test yaml gui} などが
     * 「指定された YAML が見つかりません」にならず、フォルダのサマリーを表示する。
     */
    private static boolean isFolder(YamlLoader yamlLoader, String folder) {
        if (folder == null || folder.isBlank()) {
            return false;
        }
        for (String key : yamlLoader.getKeys()) {
            if (folderOf(key).equalsIgnoreCase(folder)) {
                return true;
            }
        }
        return yamlLoader.isDirectory(folder);
    }

    /**
     * 指定された YAML の内容をフォルダ種別に応じて整形して出力する。
     * <p>
     * 数値は {@code 10.0} ではなく {@code 10} のように末尾の不要なゼロを除去し、
     * {@code stats} などのネストしたセクションは階層を保ったまま展開する。
     */
    /**
     * 武器定義の武器タイプ表示を組み立てる。
     * <p>
     * 新キー {@code weaponTypes}（リスト）を優先し、無い場合は旧キー
     * {@code weaponType}（単一文字列）へフォールバックする。
     */
    private static String weaponTypesLabel(FileConfiguration config) {
        List<String> weaponTypes = config.getStringList("weaponTypes");
        if (!weaponTypes.isEmpty()) {
            return String.join(", ", weaponTypes);
        }
        return text(config.getString("weaponType"), "-");
    }

    private void sendYamlEntry(CommandSender sender, YamlEntry entry) {
        FileConfiguration config = entry.config();
        String id = text(config.getString("id"), entry.fileName());
        String name = text(config.getString("name"), id);
        String label = "<aqua>[" + esc(entry.label(id)) + "]</aqua>";

        String folderType = folderOf(entry.folder());
        if (folderType.isEmpty()) {
            folderType = entry.folder();
        }

        switch (folderType) {
            case "WeaponData" -> {
                sendYamlHeader(sender, label, name);
                send(sender, treeBranch(config.isConfigurationSection("stats"))
                        + " <white>Type</white>: <yellow>"
                        + esc(weaponTypesLabel(config)) + "</yellow>"
                        + " <dark_gray>|</dark_gray> <white>BaseDmg</white>: <yellow>"
                        + number(config, "baseDamage") + "</yellow>"
                        + " <dark_gray>|</dark_gray> <white>Range</white>: <yellow>"
                        + number(config, "attackRange") + "m</yellow>"
                        + " <dark_gray>|</dark_gray> <white>Angle</white>: <yellow>"
                        + number(config, "attackAngle") + "°</yellow>"
                        + " <dark_gray>|</dark_gray> <white>ReqLv</white>: <yellow>"
                        + number(config, "requiredLevel") + "</yellow>");
                sendStatsSection(sender, config, "stats", "  <gray>└</gray>");
            }
            case "ArmorData" -> {
                sendYamlHeader(sender, label, name);
                send(sender, treeBranch(config.isConfigurationSection("stats"))
                        + " <white>Slot</white>: <yellow>"
                        + esc(text(config.getString("equipment_slot"), "-")) + "</yellow>"
                        + " <dark_gray>|</dark_gray> <white>DEF2</white>: <yellow>"
                        + numberWithFallback(config, "stats.defense2", "defense2") + "</yellow>"
                        + " <dark_gray>|</dark_gray> <white>ReqLv</white>: <yellow>"
                        + number(config, "requiredLevel") + "</yellow>");
                sendStatsSection(sender, config, "stats", "  <gray>└</gray>");
            }
            case "SkillData" -> {
                sendYamlHeader(sender, label, name);
                send(sender, treeBranch(config.isConfigurationSection("stats"))
                        + " <white>Multiplier</white>: <yellow>"
                        + number(config, "baseDamageMultiplier") + "x</yellow>"
                        + " <dark_gray>|</dark_gray> <white>Range</white>: <yellow>"
                        + number(config, "range") + "m</yellow>"
                        + " <dark_gray>|</dark_gray> <white>Mana</white>: <yellow>"
                        + number(config, "manaCost") + "</yellow>"
                        + " <dark_gray>|</dark_gray> <white>Cooldown</white>: <yellow>"
                        + number(config, "cooldown") + "s</yellow>");
                sendStatsSection(sender, config, "stats", "  <gray>└</gray>");
            }
            case "MobData" -> {
                sendYamlHeader(sender, label, name);
                send(sender, treeBranch(config.isConfigurationSection("stats"))
                        + " <white>MaxHP</white>: <yellow>" + number(config, "maxHp")
                        + "</yellow> <dark_gray>|</dark_gray> <white>DEF1</white>: <yellow>"
                        + number(config, "defense1")
                        + "</yellow> <dark_gray>|</dark_gray> <white>DEF2</white>: <yellow>"
                        + numberWithFallback(config, "defense2", "defense")
                        + "</yellow> <dark_gray>|</dark_gray> <white>EXP</white>: <yellow>"
                        + number(config, "expReward")
                        + "</yellow> <dark_gray>|</dark_gray> <white>Money</white>: <yellow>"
                        + number(config, "moneyReward") + "</yellow>");
                sendStatsSection(sender, config, "stats", "  <gray>└</gray>");
            }
            case "LayerData" -> {
                boolean shared = config.getBoolean("isShared", false);
                sendYamlHeader(sender, label, name);
                send(sender, "  <gray>├</gray> <white>MaxInst</white>: <yellow>"
                        + number(config, "maxInstances") + "</yellow>"
                        + " <dark_gray>|</dark_gray> <white>Shared</white>: "
                        + (shared ? "<green>true</green>" : "<red>false</red>"));
                send(sender, "  " + layerStatus(config, id));
            }
            default -> {
                sendYamlHeader(sender, label, name);
                sendConfigTree(sender, config);
            }
        }
    }

    private void sendYamlHeader(CommandSender sender, String label, String name) {
        send(sender, PREFIX + label + " <white><bold>" + esc(name) + "</bold></white>");
    }

    /**
     * 後続セクションの有無に応じてツリーの枝文字を返す。
     */
    private static String treeBranch(boolean hasFollowingSection) {
        return hasFollowingSection ? "  <gray>├</gray>" : "  <gray>└</gray>";
    }

    /**
     * 指定セクション配下の末端キーのうち、値が 0 以外のものだけを展開する。
     * <p>
     * {@code stats} のように 0 が既定値として並ぶセクションを見やすくするため、
     * 0 の項目は表示しない（負の値は 0 とみなさず表示する）。すべて 0 の場合は
     * {@code (all zero)} の 1 行にまとめる。
     */
    private void sendStatsSection(
            CommandSender sender, FileConfiguration config, String key, String branch) {
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section == null || section.getKeys(true).isEmpty()) {
            return;
        }

        List<String> nonZeroPaths = new ArrayList<>();
        int total = 0;
        for (String path : section.getKeys(true)) {
            if (section.isConfigurationSection(path)) {
                continue;
            }
            total++;
            if (!isZeroValue(section.get(path))) {
                nonZeroPaths.add(path);
            }
        }

        if (nonZeroPaths.isEmpty()) {
            send(sender, branch + " <white>" + esc(key) + "</white>: <gray>(all zero)</gray>");
            return;
        }

        send(sender, branch + " <white>" + esc(key) + "</white> <gray>(" + total + ")</gray>");

        int emitted = 0;
        for (String path : nonZeroPaths) {
            if (emitted >= MAX_YAML_DETAIL_LINES) {
                send(sender, "      <gray>…省略</gray>");
                return;
            }
            send(sender, "      " + indentFor(path) + "<gray>" + esc(leafName(path))
                    + "</gray>: " + value(section.get(path)));
            emitted++;
        }
    }

    /**
     * 数値が 0 か判定する。数値以外（文字列など）は表示対象として扱う。
     */
    private static boolean isZeroValue(Object value) {
        return value instanceof Number number && number.doubleValue() == 0.0;
    }

    /**
     * 汎用 YAML の末端キーをインデント付きで展開する。
     */
    private void sendConfigTree(CommandSender sender, FileConfiguration config) {
        int emitted = 0;
        for (String path : config.getKeys(true)) {
            if (config.isConfigurationSection(path)) {
                continue;
            }
            if (emitted >= MAX_YAML_DETAIL_LINES) {
                send(sender, "  <gray>└ …省略</gray>");
                return;
            }
            send(sender, "  " + indentFor(path) + "<gray>" + esc(leafName(path))
                    + "</gray>: " + value(config.get(path)));
            emitted++;
        }
        if (emitted == 0) {
            send(sender, "  <gray>└ キーがありません。</gray>");
        }
    }

    private static String indentFor(String path) {
        int depth = path.split("\\.").length - 1;
        return "  ".repeat(Math.max(0, depth));
    }

    private static String leafName(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static String value(Object value) {
        if (value == null) {
            return "<dark_gray>-</dark_gray>";
        }
        if (value instanceof Boolean bool) {
            return bool ? "<green>true</green>" : "<red>false</red>";
        }
        if (value instanceof Number number) {
            return "<yellow>" + numberText(number) + "</yellow>";
        }
        return "<white>" + esc(String.valueOf(value)) + "</white>";
    }

    private static String number(FileConfiguration config, String key) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return numberText(number);
        }
        return value == null ? "-" : esc(String.valueOf(value));
    }

    /**
     * 指定キーが存在すればその値を、無ければ互換キーの値を数値として返す。
     * <p>
     * 旧形式の {@code defense} と新形式の {@code defense2} のように、
     * 同じ意味のキーが併存する設定を 1 行表示するために使用する。
     */
    private static String numberWithFallback(FileConfiguration config, String key, String fallbackKey) {
        if (config != null && config.isSet(key)) {
            return number(config, key);
        }
        return number(config, fallbackKey);
    }

    private static String numberText(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return number.toString();
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            return esc(String.valueOf(value));
        }
        return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
    }

    /**
     * LayerData の稼働状況を 1 行で返す。
     */
    private String layerStatus(FileConfiguration config, String id) {
        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            return "<gray>└ Status: <red>[UNAVAILABLE]</red> LayerManager が初期化されていません。</gray>";
        }

        String templateId = text(config.getString("templateId"), id);
        String instanceId = findInstanceId(layerManager, templateId);
        if (instanceId == null) {
            return "<gray>└ Status: <yellow>[UNLOADED]</yellow> テンプレート '"
                    + esc(templateId) + "' のインスタンスは未生成</gray>";
        }

        World world = layerManager.getWorld(instanceId);
        if (world == null) {
            return "<gray>└ Status: <yellow>[UNLOADED]</yellow> インスタンス '"
                    + esc(instanceId) + "' は未ロード</gray>";
        }

        return "<gray>└ Status: <green>[LOADED]</green> インスタンス '" + esc(instanceId)
                + "' が稼働中 (Players: " + world.getPlayers().size() + ")</gray>";
    }

    /**
     * テンプレート ID に対応する稼働中インスタンス ID を探す。
     * Hub は固定 ID を使用し、それ以外は {@code <templateId>_instance} 接頭辞で走査する。
     */
    private static String findInstanceId(LayerManager layerManager, String templateId) {
        if (Zeloria.HUB_TEMPLATE_ID.equalsIgnoreCase(templateId)) {
            return Zeloria.HUB_INSTANCE_ID;
        }

        String prefix = templateId.toLowerCase(Locale.ROOT) + "_instance";
        for (String instanceId : layerManager.getLoadedWorlds().keySet()) {
            if (instanceId.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return instanceId;
            }
        }
        return null;
    }

    /**
     * {@code /admin test db}: 接続プールとテーブル作成を検証する。
     */
    private void testDb(CommandSender sender) {
        DatabaseService databaseService = plugin.getDatabaseService();
        if (databaseService == null) {
            send(sender, PREFIX + "<red>DatabaseService が初期化されていません。</red>");
            return;
        }

        HikariDataSource dataSource = databaseService.getDataSource();
        if (dataSource == null || dataSource.isClosed()) {
            send(sender, PREFIX + "<red>データベースに接続されていません。sql.yml の設定を確認してください。</red>");
            return;
        }

        String jdbcUrl = dataSource.getJdbcUrl();

        send(sender, PREFIX + "<green>[OK]</green> <white>接続プールは有効です。</white>");
        // クエリパラメータを含むフル URL は折り返しの原因になるため、ホバー時のみ表示する。
        send(sender, "<gray>└ Target: </gray>"
                + hover("<yellow>" + esc(jdbcTarget(jdbcUrl)) + "</yellow>", jdbcUrl)
                + "<dark_gray> | </dark_gray><gray>Pool: </gray><yellow>"
                + esc(dataSource.getPoolName()) + "</yellow>"
                + "<gray> (Max: </gray><yellow>" + dataSource.getMaximumPoolSize()
                + "</yellow><gray>)</gray>");

        CompletableFuture<Void> tableCreation;
        if (plugin.getPlayerRepository() != null) {
            tableCreation = plugin.getPlayerRepository().createTableIfNotExists();
        } else if (plugin.getDatabaseManager() != null) {
            tableCreation = plugin.getDatabaseManager().initialize();
        } else {
            send(sender, PREFIX + "<red>PlayerRepository が初期化されていません。</red>");
            return;
        }

        tableCreation
                .thenRun(() -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    send(sender, PREFIX
                            + "<green>[OK]</green> <white>players テーブルの作成／確認に成功しました。</white>");
                    reportDatabaseTables(sender, dataSource);
                }))
                .exceptionally(throwable -> {
                    send(sender, PREFIX + "<red>[NG] テーブル作成に失敗しました: "
                            + esc(rootMessage(throwable)) + "</red>");
                    return null;
                });
    }

    /**
     * {@code SHOW TABLES} でテーブル一覧を取得し、各テーブルのカラム数を表示する。
     * <p>
     * DB アクセスは非同期タスクで行い、メインスレッドをブロックしない。
     */
    private void reportDatabaseTables(CommandSender sender, HikariDataSource dataSource) {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW TABLES")) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
            tables.sort(String.CASE_INSENSITIVE_ORDER);
        } catch (SQLException e) {
            send(sender, PREFIX + "<red>[NG] テーブル一覧の取得に失敗しました: "
                    + esc(e.getMessage()) + "</red>");
            return;
        }

        send(sender, PREFIX + "<green>[OK]</green> <white>テーブル一覧</white>"
                + " <gray>(" + tables.size() + ")</gray>");
        if (tables.isEmpty()) {
            send(sender, "  <gray>└ テーブルがありません。</gray>");
            return;
        }

        int shown = Math.min(tables.size(), MAX_DB_TABLE_LINES);
        try (Connection connection = dataSource.getConnection()) {
            for (int i = 0; i < shown; i++) {
                String table = tables.get(i);
                boolean last = i == shown - 1 && tables.size() <= MAX_DB_TABLE_LINES;
                int columns = countColumns(connection, table);
                send(sender, "  <dark_gray>" + (last ? "└" : "├") + "</dark_gray> "
                        + "<yellow>" + esc(table) + "</yellow>"
                        + " <gray>(</gray><white>" + columns + "</white>"
                        + "<gray> columns)</gray>");
            }
            if (tables.size() > shown) {
                send(sender, "  <dark_gray>└</dark_gray> <gray>…ほか "
                        + (tables.size() - shown) + " テーブル</gray>");
            }
        } catch (SQLException e) {
            send(sender, PREFIX + "<red>[NG] カラム数の取得に失敗しました: "
                    + esc(e.getMessage()) + "</red>");
        }
    }

    /**
     * 指定テーブルのカラム数を {@code SHOW COLUMNS} で取得する。
     */
    private static int countColumns(Connection connection, String table) throws SQLException {
        String escaped = table.replace("`", "``");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW COLUMNS FROM `" + escaped + "`")) {
            int columns = 0;
            while (resultSet.next()) {
                columns++;
            }
            return columns;
        }
    }

    /**
     * フォルダ指定・id 指定から対象 YAML を解決する。
     * <p>
     * ファイル名・拡張子なしファイル名・YAML 内 {@code id} のいずれでも照合する。
     */
    private YamlEntry resolve(YamlLoader yamlLoader, String folderFilter, String idInput) {
        String id = stripExtension(normalizeFolder(idInput));
        String folder = normalizeFolder(folderFilter);
        if (isExcludedFolder(folder)) {
            return null;
        }

        // フォルダ + ファイル名、または config/ からの相対パスの完全一致を優先する。
        // これにより WeaponData/archer test と WeaponData archer/test の両方を受け付ける。
        String target = (folder.isEmpty() ? id : folder + "/" + id).toLowerCase(Locale.ROOT);
        for (String key : yamlLoader.getKeys()) {
            if (isExcludedFolder(folderOf(key))) {
                continue;
            }
            String relative = stripExtension(key).replace('\\', '/');
            if (relative.toLowerCase(Locale.ROOT).equals(target)) {
                return new YamlEntry(key, folderOf(key), stripExtension(fileNameOf(key)), yamlLoader.getConfig(key));
            }
        }

        // ファイル名・拡張子なしファイル名での照合。
        for (String key : yamlLoader.getKeys()) {
            if (isExcludedFolder(folderOf(key))) {
                continue;
            }
            String keyFolder = folderOf(key);
            if (!folder.isEmpty() && !keyFolder.equalsIgnoreCase(folder)) {
                continue;
            }

            String fileName = fileNameOf(key);
            if (fileName.equalsIgnoreCase(id) || stripExtension(fileName).equalsIgnoreCase(id)) {
                return new YamlEntry(key, keyFolder, stripExtension(fileName), yamlLoader.getConfig(key));
            }
        }

        // ファイル名ではなく YAML 内の id で指定された場合のフォールバック。
        String lookup = folder.isEmpty() ? id : folder + "/" + id;
        FileConfiguration config = yamlLoader.getConfig(lookup);
        if (config == null && !folder.isEmpty()) {
            config = yamlLoader.getConfig(id);
        }
        if (config == null) {
            return null;
        }

        for (Map.Entry<String, FileConfiguration> entry : yamlLoader.getConfigs().entrySet()) {
            if (entry.getValue() == config) {
                String key = entry.getKey();
                if (isExcludedFolder(folderOf(key))) {
                    return null;
                }
                return new YamlEntry(key, folderOf(key), stripExtension(fileNameOf(key)), config);
            }
        }
        return null;
    }

    /**
     * {@code /admin test yaml} のフォルダ引数を補完する。
     * <p>
     * 第 1 階層では {@code config/} 直下のフォルダ名のみを返す。
     * {@code /} を含む入力では、その階層のサブフォルダ（末尾 {@code /}）と
     * ファイル名（拡張子なし）を返し、深いパスでも絞り込めるようにする。
     */
    private static List<String> completeYamlFolder(YamlLoader yamlLoader, String input) {
        String typed = completionPath(input);
        int slash = typed.lastIndexOf('/');
        if (slash < 0) {
            List<String> topLevel = new ArrayList<>();
            for (String folder : allFoldersOf(yamlLoader)) {
                if (!folder.contains("/")) {
                    topLevel.add(folder);
                }
            }
            return partial(typed, topLevel);
        }

        String parentPart = typed.substring(0, slash);
        String prefix = parentPart.isEmpty() ? "" : parentPart + "/";
        String parent = canonicalFolder(yamlLoader, parentPart);
        if (parent == null) {
            return List.of();
        }
        return partial(typed, prefixed(prefix, childEntries(yamlLoader, parent, true)));
    }

    /**
     * {@code /admin test yaml <folder>} の id 引数を補完する。
     * <p>
     * 指定フォルダ直下のファイル名（拡張子なし）とサブフォルダ名を返す。
     * サブフォルダ名と完全一致した場合は、その配下のパスへ自動的に進む。
     */
    private static List<String> completeYamlId(YamlLoader yamlLoader, String folderInput, String input) {
        String base = canonicalFolder(yamlLoader, folderInput);
        if (base == null || base.isEmpty()) {
            return List.of();
        }

        String typed = completionPath(input);
        int slash = typed.lastIndexOf('/');
        if (slash < 0) {
            if (!typed.isEmpty()) {
                String nested = canonicalFolder(yamlLoader, base + "/" + typed);
                if (nested != null) {
                    return partial(typed + "/",
                            prefixed(typed + "/", childEntries(yamlLoader, nested, true)));
                }
            }
            return partial(typed, childEntries(yamlLoader, base, false));
        }

        String parentPart = typed.substring(0, slash);
        String prefix = parentPart.isEmpty() ? "" : parentPart + "/";
        String parent = parentPart.isEmpty() ? base : canonicalFolder(yamlLoader, base + "/" + parentPart);
        if (parent == null) {
            return List.of();
        }
        return partial(typed, prefixed(prefix, childEntries(yamlLoader, parent, true)));
    }

    /**
     * config/ 配下のフォルダ相対パスを、読み込み済み YAML から階層ごとに列挙する。
     */
    private static List<String> allFoldersOf(YamlLoader yamlLoader) {
        Set<String> folders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : yamlLoader.getKeys()) {
            String folder = folderOf(key);
            if (isExcludedFolder(folder)) {
                continue;
            }
            while (!folder.isEmpty()) {
                folders.add(folder);
                folder = folderOf(folder);
            }
        }
        return new ArrayList<>(folders);
    }

    /**
     * 入力フォルダ名を、実際に存在するフォルダの表記へ解決する。
     *
     * @return 正規化されたフォルダパス。存在しない場合は {@code null}
     */
    private static String canonicalFolder(YamlLoader yamlLoader, String folder) {
        String normalized = normalizeFolder(folder);
        if (normalized.isEmpty()) {
            return "";
        }
        for (String candidate : allFoldersOf(yamlLoader)) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 指定フォルダ直下のフォルダ名とファイル名（拡張子なし）を列挙する。
     */
    private static List<String> childEntries(YamlLoader yamlLoader, String folder, boolean directoriesWithSlash) {
        String base = folder == null ? "" : folder;
        List<String> entries = new ArrayList<>();
        for (String candidate : allFoldersOf(yamlLoader)) {
            if (!candidate.equalsIgnoreCase(base) && folderOf(candidate).equalsIgnoreCase(base)) {
                entries.add(directoriesWithSlash ? fileNameOf(candidate) + "/" : fileNameOf(candidate));
            }
        }
        for (String key : yamlLoader.getKeys()) {
            if (!isExcludedFolder(folderOf(key)) && folderOf(key).equalsIgnoreCase(base)) {
                entries.add(stripExtension(fileNameOf(key)));
            }
        }
        // 重複を除去し、大文字小文字を無視して整列する。
        List<String> sorted = entries.stream()
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new ArrayList<>(sorted);
    }

    /**
     * 補完候補の先頭に入力済みパスを付与する。
     */
    private static List<String> prefixed(String prefix, List<String> entries) {
        if (prefix.isEmpty()) {
            return entries;
        }
        List<String> result = new ArrayList<>(entries.size());
        for (String entry : entries) {
            result.add(prefix + entry);
        }
        return result;
    }

    /**
     * 補完用にパス区切りを統一する（末尾の {@code /} は階層指定として保持する）。
     */
    private static String completionPath(String input) {
        String value = input == null ? "" : input.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private static List<String> idsOf(YamlLoader yamlLoader, String folderFilter) {
        Set<String> ids = new LinkedHashSet<>();
        for (String key : yamlLoader.getKeys()) {
            String folder = folderOf(key);
            if (folderFilter != null && !folder.equalsIgnoreCase(folderFilter)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            String id = config == null ? null : config.getString("id");
            ids.add(id == null || id.isBlank() ? stripExtension(fileNameOf(key)) : id);
        }
        return new ArrayList<>(ids);
    }

    private static String folderOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? "" : key.substring(0, slash);
    }

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    private static String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml")) {
            return name.substring(0, name.length() - ".yaml".length());
        }
        return lower.endsWith(".yml")
                ? name.substring(0, name.length() - ".yml".length())
                : name;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * JDBC URL からチャット表示用のターゲット（{@code host:port/database}）を抽出する。
     * <p>
     * クエリパラメータ（{@code ?} 以降）はチャット欄で折り返しの原因になるため除去し、
     * フル URL は {@link #hover(String, String)} のホバーテキストでのみ確認できるようにする。
     * <pre>
     * jdbc:mysql://localhost:3306/zeloria?useSSL=false&amp;serverTimezone=UTC
     *   → localhost:3306/zeloria
     * </pre>
     *
     * @param jdbcUrl JDBC URL
     * @return 表示用ターゲット。解釈できない場合はクエリ除去後の文字列
     */
    private static String jdbcTarget(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "-";
        }

        String withoutQuery = stripQuery(jdbcUrl);

        // "jdbc:mysql://" のようなスキーム部を落とし、authority + path のみを残す。
        int authority = withoutQuery.indexOf("//");
        if (authority >= 0) {
            String target = withoutQuery.substring(authority + 2);
            if (!target.isBlank()) {
                return target;
            }
        }
        return withoutQuery;
    }

    /**
     * {@code ?} 以降のクエリパラメータを取り除く。
     */
    private static String stripQuery(String value) {
        if (value == null) {
            return null;
        }
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query);
    }

    /**
     * カーソルを合わせたときだけ表示されるホバーテキスト付きの要素を生成する。
     *
     * @param display   常時表示する MiniMessage 文字列
     * @param hoverText ホバー時に表示する生テキスト
     * @return ホバー付きの MiniMessage 文字列
     */
    private static String hover(String display, String hoverText) {
        return "<hover:show_text:'" + hoverArgument(hoverText) + "'>" + display + "</hover>";
    }

    /**
     * ホバー引数を MiniMessage のタグ引数として安全な形式に変換する。
     * <p>
     * 引数はシングルクォートで囲むため、クォートとバックスラッシュを除去したうえで
     * タグとして解釈されないようエスケープする。
     */
    private static String hoverArgument(String value) {
        String sanitized = value == null ? "" : value.replace("'", "").replace("\\", "");
        return MINI.escapeTags(sanitized);
    }

    /** MiniMessage のタグとして解釈されないよう、ユーザー由来の文字列をエスケープする。 */
    private static String esc(String value) {
        return MINI.escapeTags(value == null ? "" : value);
    }

    /**
     * {@code admin.yml} からメッセージを取得し、プレースホルダーを置換して返す。
     * <p>
     * メッセージが見つからない場合は {@code fallback}（MiniMessage 文字列）を使用する。
     * いずれも {@link #PREFIX} が付与される。
     *
     * @param key          メッセージキー
     * @param placeholders プレースホルダー
     * @param fallback     フォールバックの MiniMessage 文字列
     * @return 整形済みの MiniMessage 文字列
     */
    private String message(String key, Map<String, String> placeholders, String fallback) {
        MessageService messageService = plugin.getMessageService();
        if (messageService == null) {
            return PREFIX + fallback;
        }
        String template = messageService.findRaw(MESSAGE_FILE, key).orElse(fallback);
        return PREFIX + messageService.format(template, placeholders);
    }

    /**
     * {@code /admin} の使用方法メッセージを返す。
     */
    private String usage() {
        return message("command.usage", Map.of(), FALLBACK_USAGE);
    }

    /**
     * {@code /admin npc} の使用方法メッセージを返す。
     */
    private String npcUsage() {
        return message("npc.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin npc list</white> <dark_gray>|</dark_gray> "
                        + "<white>/admin npc spawn npcId</white> <dark_gray>|</dark_gray> "
                        + "<white>/admin npc remove</white>");
    }

    /**
     * {@code /admin mob} の使用方法メッセージを返す。
     */
    private String mobUsage() {
        return message("mob.spawn.usage", Map.of(),
                "<gray>使い方:</gray> <white>/admin mob spawn \\<mobId> [count]</white>");
    }

    /**
     * 権限不足メッセージを返す。
     *
     * @param permission 必要な権限
     */
    private String noPermission(String permission) {
        return message("command.no_permission", Map.of("permission", permission),
                "<red>この操作には権限 <white>{permission}</white> が必要です。</red>");
    }

    private void send(CommandSender sender, String miniMessage) {
        plugin.getLogger().info(MINI.stripTags(miniMessage));

        if (Bukkit.isPrimaryThread()) {
            sender.sendMessage(MINI.deserialize(miniMessage));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(MINI.deserialize(miniMessage)));
    }

    private static List<String> partial(String input, List<String> candidates) {
        String lower = input.toLowerCase(Locale.ROOT);
        // 候補は大文字小文字を無視してアルファベット順に返す。
        Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                sorted.add(candidate);
            }
        }
        return new ArrayList<>(sorted);
    }

    private static List<String> partial(String input, Set<String> candidates) {
        return partial(input, new ArrayList<>(candidates));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * 解決済みの YAML エントリ。
     *
     * @param path     キャッシュキー（config/ からの相対パス）
     * @param folder   親フォルダ名（ルート直下の場合は空文字）
     * @param fileName 拡張子なしファイル名
     * @param config   読み込み済み設定
     */
    private record YamlEntry(String path, String folder, String fileName, FileConfiguration config) {

        /**
         * 表示用ラベル（例: {@code WeaponData/archer/test}）。
         */
        String label(String id) {
            return folder.isEmpty() ? id : folder + "/" + id;
        }
    }
}
