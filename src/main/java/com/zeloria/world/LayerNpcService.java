package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.npc.NpcManager;
import com.zeloria.npc.NpcPlacement;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LayerData} の {@code npcs} 定義に従い、レイヤーワールドへ NPC を配置するサービス。
 * <p>
 * {@link LayerManager} がインスタンスワールドを生成した直後に呼び出される。
 * 座標はレイヤーテンプレートのワールド座標をそのまま使用する。
 */
public final class LayerNpcService {

    private final Zeloria plugin;
    private final NpcManager npcManager;
    private final LayerConfigService layerConfigService;

    public LayerNpcService(Zeloria plugin, NpcManager npcManager,
                           LayerConfigService layerConfigService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager");
        this.layerConfigService = Objects.requireNonNull(layerConfigService, "layerConfigService");
    }

    /**
     * 指定ワールドへレイヤーの NPC を配置する。
     *
     * @param world      配置先ワールド
     * @param instanceId インスタンス ID（ワールド名）
     * @param templateId レイヤーテンプレート ID
     * @return スポーンに成功した NPC 数
     */
    public int spawnFor(World world, String instanceId, String templateId) {
        if (world == null) {
            return 0;
        }

        LayerData layer = resolveLayer(instanceId, templateId);
        if (layer == null) {
            return 0;
        }

        List<NpcPlacement> placements = layer.getNpcs();
        if (placements.isEmpty()) {
            return 0;
        }

        int spawned = 0;
        for (NpcPlacement placement : placements) {
            Location location = new Location(
                    world,
                    placement.x(),
                    placement.y(),
                    placement.z(),
                    placement.yaw(),
                    placement.pitch()
            );
            if (npcManager.spawnNpc(placement.npcId(), location).isPresent()) {
                spawned++;
            } else {
                plugin.getLogger().warning(
                        "レイヤー NPC の配置に失敗しました: layer=" + layer.getId()
                                + " npc=" + placement.npcId()
                                + " world=" + world.getName()
                                + " at=" + format(placement));
            }
        }

        if (spawned > 0) {
            plugin.getLogger().info(
                    "レイヤー NPC を配置しました: layer=" + layer.getId()
                            + " world=" + world.getName()
                            + " count=" + spawned + "/" + placements.size());
        }
        return spawned;
    }

    /**
     * インスタンス ID → テンプレート ID の順でレイヤー定義を解決する。
     */
    private LayerData resolveLayer(String instanceId, String templateId) {
        Optional<LayerData> found = layerConfigService.findByTemplate(templateId);
        if (found.isEmpty() && instanceId != null && !instanceId.isBlank()) {
            found = layerConfigService.findByTemplate(instanceId)
                    .or(() -> layerConfigService.findById(instanceId));
        }
        return found.orElse(null);
    }

    private static String format(NpcPlacement placement) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f",
                placement.x(), placement.y(), placement.z());
    }
}
