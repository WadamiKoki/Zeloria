package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.core.LoggerService;
import com.zeloria.core.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * レイヤー入場時の演出（Title / Subtitle / Sound）を再生するサービス。
 * <p>
 * {@code LayerData} の {@code display} セクションに設定された内容を、
 * プレイヤーがレイヤーワールドへ移動したタイミングで表示・再生する。
 * タイトル等は MiniMessage 形式で記述でき、{@code {player}} / {@code {layer_name}} /
 * {@code {layer_id}} / {@code {world}} のプレースホルダーを利用できる。
 */
public class LayerEffectService {

    private final Zeloria plugin;
    private final MessageService messageService;
    private final LayerConfigService layerConfigService;

    public LayerEffectService(Zeloria plugin, MessageService messageService,
                              LayerConfigService layerConfigService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.layerConfigService = Objects.requireNonNull(layerConfigService, "layerConfigService");
    }

    /**
     * プレイヤーがレイヤーワールドへ入場した際の演出を再生する。
     * <p>
     * 対象ワールドがレイヤーでない場合は何もしない。
     *
     * @param player 対象プレイヤー
     * @param world  移動先ワールド
     */
    public void playEntry(Player player, World world) {
        if (player == null || world == null) {
            return;
        }
        playEntry(player, world.getName());
    }

    /**
     * プレイヤーがレイヤーワールドへ入場した際の演出を再生する。
     *
     * @param player    対象プレイヤー
     * @param worldName 移動先ワールド名（インスタンス ID）
     */
    public void playEntry(Player player, String worldName) {
        if (player == null || worldName == null) {
            return;
        }

        Optional<LayerData> layerData = layerConfigService.findByWorldName(worldName);
        if (layerData.isEmpty()) {
            return;
        }

        LayerData layer = layerData.get();
        LayerDisplay display = layer.getDisplay();

        Map<String, String> placeholders = placeholders(player, layer, worldName);

        if (display.hasTitle() || display.hasSubtitle()) {
            Component title = messageService.deserialize(display.title(), placeholders);
            Component subtitle = messageService.deserialize(display.subtitle(), placeholders);
            player.showTitle(Title.title(title, subtitle,
                    display.fadeIn(), display.stay(), display.fadeOut()));
        }

        if (display.hasSound()) {
            player.playSound(player.getLocation(), display.sound(),
                    SoundCategory.MASTER, display.volume(), display.pitch());
        }

        LoggerService logger = plugin.getLoggerService();
        if (logger != null) {
            logger.log(LoggerService.Category.PLAYER,
                    "レイヤー入場: player=" + player.getName()
                            + ", layer=" + layer.getId()
                            + ", template=" + layer.getTemplateId()
                            + ", world=" + worldName);
        }
    }

    private static Map<String, String> placeholders(Player player, LayerData layer, String worldName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("layer_name", layer.getName());
        placeholders.put("layer_id", layer.getId());
        placeholders.put("world", worldName);
        return placeholders;
    }
}
