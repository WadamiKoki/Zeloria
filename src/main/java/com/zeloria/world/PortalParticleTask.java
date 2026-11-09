package com.zeloria.world;

import com.zeloria.core.Zeloria;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Objects;

/**
 * ポータルの外周（半径 {@link PortalDefinition#PORTAL_RADIUS} m の円）に
 * {@link Particle#END_ROD} を表示する視覚演出タスク。
 * <p>
 * 描画負荷対策として次の最適化を行う。
 * <ul>
 *     <li>{@link World#spawnParticle} は使用せず、{@link Player#spawnParticle} で
 *         可視範囲のプレイヤーへ直接パケット送信する（サーバー全体へのブロードキャストを避ける）</li>
 *     <li>描画対象は {@code portal_settings.yml} の {@code view_distance} 以内のプレイヤーのみ</li>
 *     <li>円周上の生成点数を {@code ring_points} 点に抑え、1 点あたりの生成数も設定で制御する</li>
 * </ul>
 * このタスクはメインスレッドで実行する（{@code runTaskTimer}）。
 */
public class PortalParticleTask extends BukkitRunnable {

    /** タスクの実行周期（ティック）。10 ティック＝0.5 秒。 */
    public static final long PERIOD_TICKS = 10L;

    private static final double FULL_CIRCLE = Math.PI * 2.0;

    private final Zeloria plugin;

    public PortalParticleTask(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void run() {
        LayerConfigService configService = plugin.getLayerConfigService();
        if (configService == null) {
            return;
        }

        List<LayerConfigService.PortalPlacement> placements = configService.getPortalPlacements();
        if (placements.isEmpty()) {
            return;
        }

        PortalSettings.PortalParticle settings = plugin.getPortalSettings().portal().particle();
        double viewDistanceSquared = settings.viewDistance() * settings.viewDistance();
        for (LayerConfigService.PortalPlacement placement : placements) {
            drawRing(placement, settings, viewDistanceSquared);
        }
    }

    /**
     * 1 つのポータルについて円周状のパーティクルを送信する。
     */
    private void drawRing(LayerConfigService.PortalPlacement placement,
                          PortalSettings.PortalParticle settings,
                          double viewDistanceSquared) {
        World world = placement.world();
        PortalDefinition portal = placement.portal();
        // 判定と描画の半径を一致させるため、必ず仕様値（1.0m）を使用する。
        double radius = PortalDefinition.PORTAL_RADIUS;

        // 対象ワールドに誰もいなければ座標計算自体を省略する。
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }

        double centerY = portal.y() + settings.heightOffset();
        Particle particle = settings.type();

        for (int index = 0; index < settings.ringPoints(); index++) {
            double angle = FULL_CIRCLE * index / settings.ringPoints();
            double x = portal.x() + Math.cos(angle) * radius;
            double z = portal.z() + Math.sin(angle) * radius;

            Location point = new Location(world, x, centerY, z);
            for (Player player : players) {
                if (!player.isOnline()) {
                    continue;
                }
                if (player.getLocation().distanceSquared(point) > viewDistanceSquared) {
                    continue;
                }
                player.spawnParticle(particle,
                        x, centerY, z, settings.countPerPoint(), 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
