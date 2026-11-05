package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.world.LayerConfigService;
import com.zeloria.world.PortalDefinition;
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
 *     <li>描画対象はポータルから半径 32m 以内（{@code distanceSquared <= 1024}）のプレイヤーのみ</li>
 *     <li>円周上の生成点数を {@link #RING_POINTS} 点に抑え、1 点あたり 1 パーティクルとする</li>
 * </ul>
 * このタスクはメインスレッドで実行する（{@code runTaskTimer}）。
 */
public class PortalParticleTask extends BukkitRunnable {

    /** タスクの実行周期（ティック）。10 ティック＝0.5 秒。 */
    public static final long PERIOD_TICKS = 10L;

    /** パーティクルを送信する最大距離（メートル）。 */
    public static final double VIEW_DISTANCE = 32.0;

    /** 可視判定の二乗値（{@code 32 * 32}）。 */
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;

    /** 円周上に配置するパーティクル点数（密度を抑えた最小構成）。 */
    private static final int RING_POINTS = 8;

    /** ポータル座標から浮かせる高さ（足元の埋没を防ぐ）。 */
    private static final double HEIGHT_OFFSET = 0.1;

    /** 1 点あたりのパーティクル数。 */
    private static final int PARTICLES_PER_POINT = 1;

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

        for (LayerConfigService.PortalPlacement placement : placements) {
            drawRing(placement);
        }
    }

    /**
     * 1 つのポータルについて円周状のパーティクルを送信する。
     */
    private void drawRing(LayerConfigService.PortalPlacement placement) {
        World world = placement.world();
        PortalDefinition portal = placement.portal();
        // 判定と描画の半径を一致させるため、必ず仕様値（1.0m）を使用する。
        double radius = PortalDefinition.PORTAL_RADIUS;

        // 対象ワールドに誰もいなければ座標計算自体を省略する。
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }

        double centerY = portal.y() + HEIGHT_OFFSET;

        for (int index = 0; index < RING_POINTS; index++) {
            double angle = FULL_CIRCLE * index / RING_POINTS;
            double x = portal.x() + Math.cos(angle) * radius;
            double z = portal.z() + Math.sin(angle) * radius;

            Location point = new Location(world, x, centerY, z);
            for (Player player : players) {
                if (!player.isOnline()) {
                    continue;
                }
                if (player.getLocation().distanceSquared(point) > VIEW_DISTANCE_SQUARED) {
                    continue;
                }
                player.spawnParticle(Particle.END_ROD,
                        x, centerY, z, PARTICLES_PER_POINT, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
