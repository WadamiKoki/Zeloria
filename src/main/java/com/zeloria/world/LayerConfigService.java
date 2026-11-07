package com.zeloria.world;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code config/LayerData/*.yml} を解析して {@link LayerData} の一覧を提供するサービス。
 * <p>
 * {@link YamlLoader} が読み込んだ YAML から {@code LayerData/} 配下のものを抽出し、
 * 表示演出・ポータル定義を含むモデルへ変換してキャッシュする。
 * {@code /admin reload} 時は {@link #reload()} を呼ぶことで最新の設定を反映できる。
 */
public class LayerConfigService {

    private static final String KEY_PREFIX = "LayerData/";

    /**
     * ポータル判定における垂直方向の許容差（ブロック）。
     * <p>
     * 判定仕様を「足元座標から半径 {@link PortalDefinition#PORTAL_RADIUS} m」に統一するため、
     * 水平方向の半径と同じ値を許容差として使用する。
     */
    private static final double PORTAL_VERTICAL_TOLERANCE = PortalDefinition.PORTAL_RADIUS;

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private final List<LayerData> layers = new CopyOnWriteArrayList<>();
    private final Map<String, LayerData> byId = new ConcurrentHashMap<>();
    private final Map<String, LayerData> byTemplate = new ConcurrentHashMap<>();

    public LayerConfigService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * {@code LayerData/} 配下の YAML を再解析し、キャッシュを置き換える。
     */
    public synchronized void reload() {
        layers.clear();
        byId.clear();
        byTemplate.clear();

        for (String key : yamlLoader.getKeys()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }

            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            LayerData data;
            try {
                data = LayerData.fromConfig(key, config);
            } catch (RuntimeException e) {
                plugin.getLogger().warning(
                        "LayerData の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
                continue;
            }

            layers.add(data);
            register(byId, data.getId(), data, key);
            register(byTemplate, data.getTemplateId(), data, key);
        }

        plugin.getLogger().info("LayerData を " + layers.size() + " 件読み込みました。");
    }

    /**
     * すべてのレイヤーデータを取得する（不変ビュー）。
     */
    public List<LayerData> getLayers() {
        return List.copyOf(layers);
    }

    /**
     * ID（またはファイル名）からレイヤーデータを取得する。
     */
    public Optional<LayerData> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    /**
     * テンプレート ID からレイヤーデータを取得する。
     */
    public Optional<LayerData> findByTemplate(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTemplate.get(templateId.toLowerCase(Locale.ROOT)));
    }

    /**
     * ワールド名（インスタンス ID）からレイヤーデータを取得する。
     * <p>
     * {@link LayerManager} が保持するインスタンス→テンプレートの対応を優先し、
     * 取得できない場合はワールド名の接頭辞から推定する。
     *
     * @param worldName ワールド名（例: {@code hub_instance_1}）
     */
    public Optional<LayerData> findByWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Optional.empty();
        }

        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager != null) {
            String templateId = layerManager.getTemplateId(worldName);
            if (templateId != null) {
                Optional<LayerData> byMappedTemplate = findByTemplate(templateId);
                if (byMappedTemplate.isPresent()) {
                    return byMappedTemplate;
                }
            }
        }

        Optional<LayerData> direct = findByTemplate(worldName);
        if (direct.isPresent()) {
            return direct;
        }

        // テンプレート ID が長いものから確認し、接頭辞の衝突を避ける。
        return layers.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getTemplateId().length(), a.getTemplateId().length()))
                .filter(layer -> layer.matchesWorld(worldName))
                .findFirst();
    }

    /**
     * 指定座標の足元がポータルの範囲内か判定し、該当するポータルを返す。
     *
     * @param location プレイヤーの位置（足元）
     * @return 該当するポータル。無い場合は空
     */
    public Optional<PortalMatch> findPortal(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }

        World world = location.getWorld();
        // レイヤーインスタンスは zeloria_build:<instanceId> というキーでロードされるため、
        // 名前空間を除いたキー（= インスタンス ID）で照合する。
        String worldName = world.getKey().getKey();

        for (LayerData layer : layers) {
            if (!layer.matchesWorld(worldName)) {
                continue;
            }
            for (PortalDefinition portal : layer.getPortals().values()) {
                if (!portalAppliesTo(portal, layer, worldName)) {
                    continue;
                }
                if (isInside(location, portal)) {
                    return Optional.of(new PortalMatch(layer, portal));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * ポータルが対象ワールドに属するか判定する。
     * <p>
     * {@code world} が空の場合はそのレイヤーの全インスタンスで有効。
     * テンプレート ID が指定された場合は {@code <template>_instance...} も対象とする。
     */
    private static boolean portalAppliesTo(PortalDefinition portal, LayerData layer, String worldName) {
        String portalWorld = portal.world();
        if (portalWorld.isBlank()) {
            return true;
        }
        if (portalWorld.equalsIgnoreCase(worldName) || portalWorld.equalsIgnoreCase(layer.getTemplateId())) {
            return true;
        }
        String prefix = portalWorld.toLowerCase(Locale.ROOT) + "_instance";
        return worldName.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    /**
     * 指定座標がポータルの発動範囲内か判定する。
     * <p>
     * 判定基準は「足元座標から半径 {@link PortalDefinition#PORTAL_RADIUS} m」で統一する。
     * 水平方向は {@link PortalDefinition#PORTAL_RADIUS} の円、垂直方向は
     * {@link #PORTAL_VERTICAL_TOLERANCE}（同じ 1.0 ブロック）以内を範囲とする。
     */
    private static boolean isInside(Location location, PortalDefinition portal) {
        double dx = location.getX() - portal.x();
        double dz = location.getZ() - portal.z();
        double radius = PortalDefinition.PORTAL_RADIUS;
        if (dx * dx + dz * dz > radius * radius) {
            return false;
        }
        return Math.abs(location.getY() - portal.y()) <= PORTAL_VERTICAL_TOLERANCE;
    }

    /**
     * 稼働中ワールドに紐づくポータルの一覧を取得する。
     * <p>
     * パーティクル描画タスクが「どのワールドの、どの座標に円を描くか」を解決するために使用する。
     * {@code portals.world} が空のポータルは、所有レイヤーの全インスタンスへ展開される。
     *
     * @return ワールドとポータル定義の組（不変ビュー）
     */
    public List<PortalPlacement> getPortalPlacements() {
        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            return List.of();
        }

        Map<String, org.bukkit.World> loaded = layerManager.getLoadedWorlds();
        if (loaded.isEmpty()) {
            return List.of();
        }

        List<PortalPlacement> placements = new java.util.ArrayList<>();
        for (LayerData layer : layers) {
            if (layer.getPortals().isEmpty()) {
                continue;
            }
            for (Map.Entry<String, org.bukkit.World> entry : loaded.entrySet()) {
                String worldName = entry.getKey();
                org.bukkit.World world = entry.getValue();
                if (world == null || !layer.matchesWorld(worldName)) {
                    continue;
                }
                for (PortalDefinition portal : layer.getPortals().values()) {
                    if (portalAppliesTo(portal, layer, worldName)) {
                        placements.add(new PortalPlacement(world, layer, portal));
                    }
                }
            }
        }
        return List.copyOf(placements);
    }

    private static void register(Map<String, LayerData> target, String key,
                                 LayerData data, String sourceKey) {
        if (key == null || key.isBlank()) {
            return;
        }
        LayerData previous = target.putIfAbsent(key.toLowerCase(Locale.ROOT), data);
        if (previous != null && previous != data) {
            // 重複は先勝ち。運用者が気づけるよう警告のみ行う。
            Bukkit.getLogger().warning(
                    "LayerData のキーが重複しています: '" + key + "' (" + sourceKey + ")");
        }
    }

    /**
     * ポータル判定の結果。
     *
     * @param layer  ポータルを所有するレイヤー
     * @param portal 該当したポータル
     */
    public record PortalMatch(LayerData layer, PortalDefinition portal) {
    }

    /**
     * 稼働中ワールドへ展開済みのポータル。
     *
     * @param world  ポータルが属するワールド
     * @param layer  ポータルを所有するレイヤー
     * @param portal ポータル定義
     */
    public record PortalPlacement(World world, LayerData layer, PortalDefinition portal) {
    }
}
