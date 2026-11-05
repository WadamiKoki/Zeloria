package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.world.LayerConfigService;
import com.zeloria.world.LayerData;
import com.zeloria.world.LayerEffectService;
import com.zeloria.world.LayerManager;
import com.zeloria.world.PortalDefinition;
import com.zeloria.core.LoggerService;
import com.zeloria.core.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ポータルワープ（スニーク発動）を処理するリスナー。
 * <p>
 * プレイヤーがしゃがんだ際、足元が {@code LayerData} の {@code portals} で定義された
 * 範囲（足元座標から半径 {@link PortalDefinition#PORTAL_RADIUS} m）内にあるかを判定し、
 * 該当すれば転送先レイヤーへテレポートする。
 * 転送先インスタンスが未生成の場合は {@link LayerManager#getOrCreateInstance(String)}
 * （内部で {@link LayerManager#createLayer(String, String)}）により生成する。
 * <p>
 * 転送音は全ポータル共通で {@code block.amethyst_block.chime}（volume 1.0 / pitch 1.0）に固定する。
 * <p>
 * 連続発動を防ぐためプレイヤーごとにクールダウン（既定 2 秒）を設け、
 * クールタイム中の操作には {@code MessageData/gate.yml} のメッセージを表示する。
 */
public class PortalListener implements Listener {

    /** ポータル再発動までのクールダウン（ミリ秒）。 */
    private static final long COOLDOWN_MILLIS = 2_000L;

    private static final String MESSAGE_FILE = "gate";

    /** 全ポータル共通の転送音。 */
    private static final String WARP_SOUND = "block.amethyst_block.chime";

    /** 転送音の音量（全ポータル共通）。 */
    private static final float WARP_SOUND_VOLUME = 1.0f;

    /** 転送音のピッチ（全ポータル共通）。 */
    private static final float WARP_SOUND_PITCH = 1.0f;

    private final Zeloria plugin;

    /**
     * プレイヤー UUID → 最後にポータルを発動した時刻（ミリ秒）。
     */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public PortalListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }

        Player player = event.getPlayer();
        LayerConfigService configService = plugin.getLayerConfigService();
        if (configService == null) {
            return;
        }

        Optional<LayerConfigService.PortalMatch> match = configService.findPortal(player.getLocation());
        if (match.isEmpty()) {
            return;
        }

        LayerConfigService.PortalMatch portalMatch = match.get();
        PortalDefinition portal = portalMatch.portal();

        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long remainingMillis = COOLDOWN_MILLIS - (now - last);
        if (remainingMillis > 0) {
            long remainingSeconds = (remainingMillis + 999L) / 1000L;
            actionBar(player, "portal.cooldown", Map.of("time", String.valueOf(remainingSeconds)));
            return;
        }

        if (!portal.hasTargetTemplate()) {
            plugin.getLogger().warning(
                    "ポータル '" + portal.id() + "' に target_template が設定されていません。");
            messageService().send(player, MESSAGE_FILE, "portal.creation_failed",
                    Map.of("layer_name", portal.id()));
            return;
        }

        String targetTemplate = portal.targetTemplate();
        String targetName = configService.findByTemplate(targetTemplate)
                .map(LayerData::getName)
                .orElse(targetTemplate);

        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            plugin.getLogger().severe("LayerManager が初期化されていないためポータルを使用できません。");
            return;
        }

        World targetWorld;
        try {
            targetWorld = layerManager.getOrCreateInstance(targetTemplate);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ポータルの転送先レイヤーを生成できませんでした: "
                    + targetTemplate + " (" + e.getMessage() + ")");
            messageService().send(player, MESSAGE_FILE, "portal.creation_failed",
                    Map.of("layer_name", targetName));
            logWarning("ポータル転送先の生成失敗: player=" + player.getName()
                    + ", portal=" + portal.id() + ", target=" + targetTemplate
                    + ", error=" + e.getMessage());
            return;
        }

        cooldowns.put(player.getUniqueId(), now);
        actionBar(player, "portal.warping", Map.of("layer_name", targetName));

        Location destination = buildDestination(player, portal, targetWorld);
        World sourceWorld = player.getWorld();
        String sourceLayer = portalMatch.layer().getId();

        // teleportAsync の完了コールバックは実行スレッドが保証されないため、
        // プレイヤー操作を伴う処理はメインスレッドへ戻してから実行する。
        player.teleportAsync(destination).whenComplete((success, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> handleTeleportResult(
                        player, portal, targetTemplate, targetName,
                        targetWorld, sourceWorld, sourceLayer, destination, success, throwable)));
    }

    private void handleTeleportResult(Player player, PortalDefinition portal,
                                      String targetTemplate, String targetName,
                                      World targetWorld, World sourceWorld, String sourceLayer,
                                      Location destination, Boolean success, Throwable throwable) {
        if (!player.isOnline()) {
            return;
        }

        if (throwable != null) {
            cooldowns.remove(player.getUniqueId());
            messageService().send(player, MESSAGE_FILE, "portal.teleport_failed", Map.of());
            logWarning("ポータル転送エラー: player=" + player.getName()
                    + ", portal=" + portal.id() + ", error=" + rootMessage(throwable));
            return;
        }
        if (Boolean.FALSE.equals(success)) {
            cooldowns.remove(player.getUniqueId());
            messageService().send(player, MESSAGE_FILE, "portal.teleport_failed", Map.of());
            logWarning("ポータル転送失敗: player=" + player.getName()
                    + ", portal=" + portal.id() + ", target=" + targetTemplate);
            return;
        }

        // 転送成功時は全ポータル共通の固定サウンドを再生する。
        player.playSound(player.getLocation(), WARP_SOUND, SoundCategory.MASTER,
                WARP_SOUND_VOLUME, WARP_SOUND_PITCH);

        messageService().actionBar(player, MESSAGE_FILE, "portal.used",
                Map.of("layer_name", targetName));

        LoggerService logger = plugin.getLoggerService();
        if (logger != null) {
            logger.log(LoggerService.Category.PORTAL,
                    "ポータル使用: player=" + player.getName()
                            + ", portal=" + portal.id()
                            + ", source_layer=" + sourceLayer
                            + ", source_world=" + (sourceWorld == null ? "-" : sourceWorld.getName())
                            + ", target_template=" + targetTemplate
                            + ", target_world=" + targetWorld.getName()
                            + ", destination=" + formatLocation(destination));
        }

        // 同一ワールド内の移動では PlayerChangedWorldEvent が発火しないため、明示的に演出を再生する。
        if (sourceWorld == null || !sourceWorld.equals(targetWorld)) {
            return;
        }
        LayerEffectService effectService = plugin.getLayerEffectService();
        if (effectService != null) {
            effectService.playEntry(player, targetWorld);
        }
    }

    /**
     * ログアウト時にクールダウン情報を破棄する。
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private Location buildDestination(Player player, PortalDefinition portal, World targetWorld) {
        if (portal.hasTargetLocation()) {
            float yaw = portal.targetYaw() != null
                    ? portal.targetYaw() : player.getLocation().getYaw();
            float pitch = portal.targetPitch() != null
                    ? portal.targetPitch() : player.getLocation().getPitch();
            return new Location(targetWorld,
                    portal.targetX(), portal.targetY(), portal.targetZ(), yaw, pitch);
        }

        Location spawn = targetWorld.getSpawnLocation().toBlockLocation();
        spawn.add(0.5, 0.0, 0.5);
        spawn.setYaw(player.getLocation().getYaw());
        spawn.setPitch(player.getLocation().getPitch());
        return spawn;
    }

    private void actionBar(Player player, String key, Map<String, String> placeholders) {
        messageService().actionBar(player, MESSAGE_FILE, key, placeholders);
    }

    private MessageService messageService() {
        return plugin.getMessageService();
    }

    private void logWarning(String message) {
        LoggerService logger = plugin.getLoggerService();
        if (logger != null) {
            logger.log(LoggerService.Level.WARN, LoggerService.Category.PORTAL, message);
        }
    }

    private static String formatLocation(Location location) {
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                location.getX(), location.getY(), location.getZ());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
