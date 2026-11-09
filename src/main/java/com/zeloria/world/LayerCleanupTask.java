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
 * {@link #CHECK_INTERVAL_TICKS}（10 秒）ごとにロード済みインスタンスのプレイヤー数を確認し、
 * 次の条件をすべて満たすインスタンスに対して削除タイマーを開始する。
 * <ul>
 *     <li>LayerData の {@code isShared} が {@code false}、または Hub テンプレートである</li>
 *     <li>プレイヤー数が 0 人</li>
 * </ul>
 * タイマーは既定 {@link #IDLE_TIMEOUT_SECONDS}（30 秒）で、カウントダウン中に
 * プレイヤーが再入場した場合はキャンセルする。0 人のままタイマーが満了した場合は
 * {@link LoggerService} へ記録したうえで {@link LayerManager#unloadAndDeleteLayer(String)}
 * により安全にアンロード・削除する。Hub は無人になると削除されるが、
 * ポータル入場時に {@link LayerManager} が自動で再生成する。
 * <p>
 * 建築ワールド（{@link BuildManager#BUILD_NAMESPACE} 名前空間）はこの通常削除の
 * 対象外とする。建築ワールドは自動保存・自動アンロードを行わず、プレイヤーが
 * 明示的に保存または破棄するまで維持する。
 * <p>
 * ワールド操作を伴うため、{@code runTaskTimer} によりメインスレッドで実行する。
 */
public class LayerCleanupTask extends BukkitRunnable {

    /** プレイヤー数を確認する間隔（ティック）。20 ティック × 10 秒。 */
    public static final long CHECK_INTERVAL_TICKS = 20L * 10L;

    /** 空になってから削除するまでの既定時間（秒）。 */
    public static final long IDLE_TIMEOUT_SECONDS = 30L;

    private final Zeloria plugin;

    /** 削除タイマー開始時刻を保持する（インスタンス ID → 空になった時刻）。 */
    private final Map<String, Long> idleSince = new HashMap<>();

    public LayerCleanupTask(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        checkIdleInstances(now);
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

            // 建築ワールドは専用の自動保存で管理するため、通常削除の対象外とする。
            if (BuildManager.isBuildWorld(world)) {
                idleSince.remove(instanceId);
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
     * 自動削除の対象か判定する。
     * <p>
     * {@code isShared: false} のインスタンスに加え、共有設定でも Hub テンプレートだけは
     * 無人時に削除する（入場時に {@link LayerManager} が再生成する）。
     */
    private boolean isAutoDeletable(LayerConfigService configService, String instanceId) {
        Optional<LayerData> layerData = configService.findByWorldName(instanceId);
        // 設定が不明なインスタンスは誤削除を避けるため対象外とする。
        return layerData.map(data -> !data.isShared()
                || Zeloria.HUB_TEMPLATE_ID.equalsIgnoreCase(data.getTemplateId())).orElse(false);
    }

    private void deleteIdleInstance(LayerManager layerManager, String instanceId, long elapsedSeconds) {
        String detail = "空インスタンスの自動削除: instance=" + instanceId
                + ", idle=" + elapsedSeconds + "s";

        // 成功時の通常ログは LayerManager の「[LAYER] 削除: <template>」に集約する。
        // 実行前の詳細はデバッグ出力に落とし、失敗時のみ警告する。
        plugin.getLogger().fine(detail + " を開始します。");
        boolean deleted = layerManager.unloadAndDeleteLayer(instanceId);
        if (deleted) {
            return;
        }

        String failure = "空インスタンスの自動削除に失敗しました: " + detail;
        plugin.getLogger().warning(failure);
        LoggerService logger = plugin.getLoggerService();
        if (logger != null) {
            logger.log(LoggerService.Level.WARN, LoggerService.Category.LAYER, failure);
        }
    }

    /**
     * 指定インスタンスのアイドルアンロードまでの残り秒数を取得する。
     * <p>
     * タイマーがまだ開始していない（空になってから次のチェック前の）場合は
     * {@link #IDLE_TIMEOUT_SECONDS} を返す。
     *
     * @param instanceId インスタンス ID
     * @param now        現在時刻（ミリ秒）
     * @return 残り秒数（0 以上）
     */
    public long remainingSeconds(String instanceId, long now) {
        Long since = instanceId == null ? null : idleSince.get(instanceId);
        if (since == null) {
            return IDLE_TIMEOUT_SECONDS;
        }
        long elapsedSeconds = Math.max(0L, (now - since) / 1000L);
        return Math.max(0L, IDLE_TIMEOUT_SECONDS - elapsedSeconds);
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
