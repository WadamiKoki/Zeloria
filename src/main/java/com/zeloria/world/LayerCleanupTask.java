package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.core.LoggerService;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 空になったレイヤーインスタンスを自動削除する定期タスク。
 * <p>
 * {@link #CHECK_INTERVAL_TICKS}（30 秒）ごとにロード済みインスタンスのプレイヤー数を確認し、
 * 次の条件をすべて満たすインスタンスに対して削除タイマーを開始する。
 * <ul>
 *     <li>LayerData の {@code isShared} が {@code false}</li>
 *     <li>プレイヤー数が 0 人</li>
 * </ul>
 * タイマーは既定 {@link #IDLE_TIMEOUT_SECONDS}（180 秒）で、カウントダウン中に
 * プレイヤーが再入場した場合はキャンセルする。0 人のままタイマーが満了した場合は
 * {@link LoggerService} へ記録したうえで {@link LayerManager#unloadAndDeleteLayer(String)}
 * により安全にアンロード・削除する。
 * <p>
 * ワールド操作を伴うため、{@code runTaskTimer} によりメインスレッドで実行する。
 */
public class LayerCleanupTask extends BukkitRunnable {

    /** プレイヤー数を確認する間隔（ティック）。20 ティック × 30 秒。 */
    public static final long CHECK_INTERVAL_TICKS = 20L * 30L;

    /** 空になってから削除するまでの既定時間（秒）。 */
    public static final long IDLE_TIMEOUT_SECONDS = 180L;

    private final Zeloria plugin;

    /** 削除タイマー開始時刻を保持する（インスタンス ID → 空になった時刻）。 */
    private final Map<String, Long> idleSince = new HashMap<>();

    public LayerCleanupTask(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void run() {
        checkIdleInstances(System.currentTimeMillis());
    }

    /**
     * 空インスタンスの確認処理本体。テストや手動実行のために分離している。
     *
     * @param now 現在時刻（ミリ秒）
     */
    void checkIdleInstances(long now) {
        LayerManager layerManager = plugin.getLayerManager();
        LayerConfigService configService = plugin.getLayerConfigService();
        if (layerManager == null || configService == null) {
            return;
        }

        Map<String, World> loaded = layerManager.getLoadedWorlds();
        if (loaded.isEmpty()) {
            idleSince.clear();
            return;
        }

        // 既に削除済みのインスタンスのタイマー情報を掃除する。
        idleSince.keySet().removeIf(instanceId -> !loaded.containsKey(instanceId));

        for (Map.Entry<String, World> entry : loaded.entrySet()) {
            String instanceId = entry.getKey();
            World world = entry.getValue();
            if (world == null) {
                continue;
            }

            if (!isAutoDeletable(configService, instanceId)) {
                idleSince.remove(instanceId);
                continue;
            }

            if (!world.getPlayers().isEmpty()) {
                // 再入場した場合はタイマーをキャンセルする。
                idleSince.remove(instanceId);
                continue;
            }

            long since = idleSince.computeIfAbsent(instanceId, key -> now);
            long elapsedSeconds = (now - since) / 1000L;
            if (elapsedSeconds < IDLE_TIMEOUT_SECONDS) {
                continue;
            }

            idleSince.remove(instanceId);
            deleteIdleInstance(layerManager, instanceId, elapsedSeconds);
        }
    }

    /**
     * 自動削除の対象か判定する（{@code isShared: false} のインスタンスのみ対象）。
     */
    private boolean isAutoDeletable(LayerConfigService configService, String instanceId) {
        Optional<LayerData> layerData = configService.findByWorldName(instanceId);
        // 設定が不明なインスタンスは誤削除を避けるため対象外とする。
        return layerData.map(data -> !data.isShared()).orElse(false);
    }

    private void deleteIdleInstance(LayerManager layerManager, String instanceId, long elapsedSeconds) {
        String detail = "空インスタンスの自動削除: instance=" + instanceId
                + ", idle=" + elapsedSeconds + "s";

        // 削除は取り消せないため、実行前に必ず記録を残す。
        LoggerService logger = plugin.getLoggerService();
        plugin.getLogger().info(detail + " を開始します。");
        if (logger != null) {
            logger.log(LoggerService.Category.LAYER, detail + " を開始します。");
        }

        boolean deleted = layerManager.unloadAndDeleteLayer(instanceId);
        String result = detail + ", result=" + (deleted ? "SUCCESS" : "FAILED");

        plugin.getLogger().info(result);
        if (logger != null) {
            logger.log(deleted ? LoggerService.Level.INFO : LoggerService.Level.WARN,
                    LoggerService.Category.LAYER,
                    deleted ? result
                            : "空インスタンスの自動削除に失敗しました: instance=" + instanceId);
        }
    }

    /**
     * 現在削除タイマーが進行中のインスタンス数を取得する。
     */
    public int getTrackedCount() {
        return idleSince.size();
    }

    /**
     * タイマー情報をすべて破棄する（プラグイン停止時など）。
     */
    public void clear() {
        idleSince.clear();
    }
}
