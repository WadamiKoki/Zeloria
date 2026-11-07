package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.world.LayerManager;
import com.zeloria.core.MessageService;
import com.zeloria.player.PlayerStatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独自の死亡・スペクテイター待機・復帰処理を担うリスナー。
 * <p>
 * 真の HP は {@code PlayerStats#currentHealth} であり、バニラの体力（{@code Player#getHealth()}）
 * は表示同期専用として扱う。{@code currentHealth} が 0 以下になった時点で
 * {@link #handleCustomDeath(Player)} が呼ばれ、バニラの死亡画面を介さずに
 * その場でスペクテイターへ移行する。
 * <p>
 * 待機時間・タイトル・サウンド・復帰先の Hub 座標は
 * {@code config/MessageData/death.yml} から読み込む。ファイルやキーが無い場合は
 * 組み込みの既定値を使用する。
 */
public final class DeathListener implements Listener {

    /** 死亡関連メッセージのファイル名（拡張子なし）。 */
    public static final String MESSAGE_FILE = "death";

    /** {@code wait_seconds} が未設定の場合の待機時間（秒）。 */
    private static final long DEFAULT_WAIT_SECONDS = 5L;

    /** 待機時間の上限（秒）。設定ミスによる長時間停止を防ぐ。 */
    private static final long MAX_WAIT_SECONDS = 300L;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Zeloria plugin;

    /** 独自死亡処理が進行中のプレイヤー。 */
    private final Set<UUID> awaitingRespawn = ConcurrentHashMap.newKeySet();

    /** スペクテイター待機を終了するタスク（プレイヤー UUID → タスク）。 */
    private final Map<UUID, BukkitTask> finishTasks = new ConcurrentHashMap<>();

    public DeathListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * {@code PlayerStats#currentHealth} が 0 以下になった際に呼び出す独自死亡処理。
     * <p>
     * バニラの {@code PlayerDeathEvent} は使用しない。呼び出し時点の座標を記憶し、
     * 次のティックでスペクテイターへ変更して待機を開始する。
     *
     * @param player 死亡したプレイヤー
     */
    public void handleCustomDeath(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (awaitingRespawn.contains(playerId)) {
            return;
        }

        cancelTask(playerId);
        awaitingRespawn.add(playerId);

        // 真の HP を 0 にし、戦闘状態を記録して自然回復を止める。
        // バニラの体力バーは死亡しないよう syncPlayerHealth 側で 1.0 に下げ止める。
        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.handleDeath(player);
        }
        if (plugin.getCombatService() != null) {
            plugin.getCombatService().syncPlayerHealth(player);
        }

        // 戦闘モード中のスキル表示・退避アイテムを通常状態へ戻す。
        CombatModeService combatModeService = plugin.getCombatModeService();
        if (combatModeService != null && combatModeService.isActive(player)) {
            combatModeService.deactivate(player);
        }

        // 死亡地点を維持したまま、次のティックでスペクテイターへ移行する。
        Location deathLocation = player.getLocation().clone();
        Bukkit.getScheduler().runTask(plugin, () -> beginSpectatorWait(player, deathLocation));
    }

    /**
     * プレイヤーを死亡地点でスペクテイターに変更し、待機演出を開始する。
     */
    private void beginSpectatorWait(Player player, Location deathLocation) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || !awaitingRespawn.contains(playerId)) {
            return;
        }

        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
        player.setGameMode(GameMode.SPECTATOR);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setVelocity(new Vector(0, 0, 0));

        // スペクテイター中も死亡地点に留める（視点操作のみ許可する）。
        if (deathLocation.getWorld() != null
                && deathLocation.getWorld().equals(player.getWorld())) {
            player.teleport(deathLocation);
        }

        showDeathTitle(player);
        playDeathSound(player);
        startWaitTask(player);
    }

    /**
     * 待機終了タスクを開始する。アクションバーには残り秒数を毎秒表示する。
     */
    private void startWaitTask(Player player) {
        UUID playerId = player.getUniqueId();
        long waitSeconds = waitSeconds();

        // 開始時点の残り秒数を即座に表示する。
        sendWaitingActionBar(player, waitSeconds);

        int[] remaining = {(int) waitSeconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !awaitingRespawn.contains(playerId)) {
                cancelTask(playerId);
                return;
            }

            if (remaining[0] <= 1) {
                finishRespawn(player);
                return;
            }

            remaining[0]--;
            sendWaitingActionBar(player, remaining[0]);
        }, 20L, 20L);

        finishTasks.put(playerId, task);
    }

    /**
     * 待機終了時に Hub の指定座標へ転送し、サバイバルへ戻して全回復する。
     */
    private void finishRespawn(Player player) {
        UUID playerId = player.getUniqueId();
        cancelTask(playerId);
        awaitingRespawn.remove(playerId);

        if (!player.isOnline()) {
            return;
        }

        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
        Location hub = hubLocation(player);
        player.teleport(hub);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setVelocity(new Vector(0, 0, 0));
        player.setGameMode(GameMode.SURVIVAL);

        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.handleRespawn(player);
        }

        player.sendActionBar(Component.empty());
        MessageService messages = plugin.getMessageService();
        if (messages != null) {
            messages.send(player, MESSAGE_FILE, "respawned", Map.of());
        }
    }

    /**
     * 待機中のプレイヤーが退出した場合に、タスクを停止してゲームモードを復元する。
     * <p>
     * {@link PlayerListener} が退出時にスナップショットを作成するより前に実行されるよう
     * {@link EventPriority#LOWEST} で処理する。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!awaitingRespawn.contains(player.getUniqueId())) {
            return;
        }
        restorePendingPlayer(player);
    }

    /**
     * 待機中のプレイヤーの位置を死亡地点に固定する。
     * <p>
     * スペクテイターの移動操作・スニーク・ジャンプによる座標変化を打ち消し、
     * 視点（yaw / pitch）の変更だけを許可する。テレポートは死亡地点への復帰や
     * 他プラグインの処理を妨げないよう対象外とする。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!awaitingRespawn.contains(player.getUniqueId())) {
            return;
        }
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) {
            return;
        }

        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    /**
     * 待機状態を解除し、スペクテイターのまま残らないよう復元する。
     */
    private void restorePendingPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        cancelTask(playerId);
        awaitingRespawn.remove(playerId);

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }

        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.handleRespawn(player);
        }
    }

    /**
     * プラグイン無効化時に待機タスクを停止し、全員のゲームモードを復元する。
     */
    public void shutdown() {
        Set<UUID> pending = new HashSet<>(finishTasks.keySet());
        pending.addAll(awaitingRespawn);

        for (BukkitTask task : finishTasks.values()) {
            task.cancel();
        }
        finishTasks.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (pending.contains(player.getUniqueId())) {
                restorePendingPlayer(player);
            }
        }
        awaitingRespawn.clear();
    }

    /**
     * 指定プレイヤーが独自死亡処理の待機中か判定する。
     *
     * @param player 対象プレイヤー
     * @return 待機中であれば {@code true}
     */
    public boolean isAwaiting(Player player) {
        return player != null && awaitingRespawn.contains(player.getUniqueId());
    }

    /**
     * 指定 UUID が独自死亡処理の待機中か判定する。
     *
     * @param playerId プレイヤー UUID
     * @return 待機中であれば {@code true}
     */
    public boolean isAwaiting(UUID playerId) {
        return playerId != null && awaitingRespawn.contains(playerId);
    }

    /**
     * 待機開始時のタイトルを表示する。
     */
    private void showDeathTitle(Player player) {
        Component title = message("title", "<red><bold>あなたは死亡しました</bold></red>");
        Component subtitle = message("subtitle", "<gray>まもなくリスポーンします…</gray>");
        player.showTitle(Title.title(title, subtitle,
                Title.Times.times(
                        Duration.ofMillis(250L),
                        Duration.ofSeconds(2L),
                        Duration.ofMillis(500L))));
    }

    /**
     * 待機中のアクションバーへ残り秒数を表示する。
     */
    private void sendWaitingActionBar(Player player, long seconds) {
        MessageService messages = plugin.getMessageService();
        if (messages == null) {
            player.sendActionBar(MINI.deserialize(
                    "<gray>リスポーンまで <white>" + seconds + "</white> 秒…</gray>"));
            return;
        }

        String template = messages.raw(MESSAGE_FILE, "waiting",
                "<gray>リスポーンまで <white>{seconds}</white> 秒…</gray>");
        player.sendActionBar(messages.deserialize(
                template, Map.of("seconds", String.valueOf(seconds))));
    }

    /**
     * 待機開始時のサウンドを再生する。
     */
    private void playDeathSound(Player player) {
        FileConfiguration config = deathConfig();
        String soundName = config == null
                ? "entity.player.death"
                : config.getString("sound", "entity.player.death");
        if (soundName == null || soundName.isBlank()) {
            return;
        }

        double volume = config == null ? 1.0 : config.getDouble("sound_volume", 1.0);
        double pitch = config == null ? 1.0 : config.getDouble("sound_pitch", 1.0);
        try {
            player.playSound(player.getLocation(), soundName,
                    (float) clamp(volume, 0.0, 10.0), (float) clamp(pitch, 0.5, 2.0));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(
                    "death.yml のサウンド名が不正です: " + soundName + " (" + e.getMessage() + ")");
        }
    }

    /**
     * メッセージ設定から MiniMessage 文言を取得する。
     */
    private Component message(String key, String fallback) {
        MessageService messages = plugin.getMessageService();
        if (messages == null) {
            return MINI.deserialize(fallback);
        }
        Optional<Component> configured = messages.component(MESSAGE_FILE, key, Map.of());
        return configured.orElseGet(() -> messages.deserialize(fallback, Map.of()));
    }

    /**
     * {@code death.yml} の待機時間を取得する（1〜300 秒にクランプ）。
     */
    private long waitSeconds() {
        FileConfiguration config = deathConfig();
        long configured = config == null
                ? DEFAULT_WAIT_SECONDS
                : config.getLong("wait_seconds", DEFAULT_WAIT_SECONDS);
        return Math.max(1L, Math.min(MAX_WAIT_SECONDS, configured));
    }

    /**
     * 復帰先の Hub 座標を取得する。
     * <p>
     * {@code death.yml} の {@code hub.world} / {@code hub.x} / {@code hub.y} /
     * {@code hub.z} / {@code hub.yaw} / {@code hub.pitch} を優先し、ワールドが
     * 取得できない場合は現在のワールドのスポーンへ安全にフォールバックする。
     */
    private Location hubLocation(Player player) {
        Location fallback = spawnLocation(player.getWorld());
        FileConfiguration config = deathConfig();
        if (config == null) {
            return fallback;
        }

        LayerManager layerManager = plugin.getLayerManager();
        String configuredWorld = config.getString("hub.world", "");
        World world = null;

        if (configuredWorld != null && !configuredWorld.isBlank()) {
            if (layerManager != null) {
                world = layerManager.getWorld(configuredWorld);
            }
            if (world == null) {
                world = Bukkit.getWorld(configuredWorld);
            }
        }

        if (world == null && layerManager != null) {
            world = layerManager.getWorld(Zeloria.HUB_INSTANCE_ID);
        }

        if (world == null) {
            plugin.getLogger().warning(
                    "Hub ワールドが利用できないため、現在のワールドのスポーンへ復帰します: "
                            + player.getName());
            return fallback;
        }

        Location spawn = spawnLocation(world);
        return new Location(
                world,
                config.getDouble("hub.x", spawn.getX()),
                config.getDouble("hub.y", spawn.getY()),
                config.getDouble("hub.z", spawn.getZ()),
                (float) config.getDouble("hub.yaw", spawn.getYaw()),
                (float) config.getDouble("hub.pitch", spawn.getPitch()));
    }

    private static Location spawnLocation(World world) {
        Location spawn = world.getSpawnLocation().toBlockLocation();
        spawn.add(0.5, 0.0, 0.5);
        spawn.setYaw(0.0f);
        spawn.setPitch(0.0f);
        return spawn;
    }

    private FileConfiguration deathConfig() {
        YamlLoader yamlLoader = plugin.getYamlLoader();
        return yamlLoader == null ? null : yamlLoader.getMessageConfig(MESSAGE_FILE);
    }

    private void cancelTask(UUID playerId) {
        BukkitTask previous = finishTasks.remove(playerId);
        if (previous != null) {
            previous.cancel();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
