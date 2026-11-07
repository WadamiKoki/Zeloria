package com.zeloria.world;

import com.zeloria.core.YamlLoader;
import com.zeloria.core.Zeloria;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 壁として扱うブロック（{@code config/CollisionData/solid_blocks.yml}）を管理する。
 * <p>
 * モブの壁中判定などで「固体ブロックか」を判定するためのホワイトリスト方式の
 * コリジョン判定を提供する。{@link Material#isSolid()} ではなく YAML に列挙された
 * ブロックのみを壁として扱うため、葉っぱ・水・溶岩・植物・看板などは壁にならない。
 * <p>
 * {@code /admin reload} 時に {@link #reload()} が呼ばれ、最新の設定へ更新される。
 * YAML が未配置、または {@code solid_blocks} が空の場合は既定の壁ブロックを使用する。
 */
public class CollisionManager {

    /** 衝突判定設定の格納ディレクトリ（{@code config/} からの相対パス）。 */
    private static final String COLLISION_DIRECTORY = "CollisionData/";

    /** 壁ブロックの設定ファイル（{@code config/} からの相対パス）。 */
    private static final String SOLID_BLOCKS_FILE = COLLISION_DIRECTORY + "solid_blocks.yml";

    /** 壁ブロック一覧のキー。 */
    private static final String SOLID_BLOCKS_KEY = "solid_blocks";

    /**
     * YAML が未配置・空の場合に使用する既定の壁ブロック。
     * <p>
     * 葉っぱ（{@code *_LEAVES}）、水、溶岩、ライトブロック、植物、看板、感圧板、
     * ボタン、レバーは意図的に含めない。
     */
    private static final List<String> DEFAULT_SOLID_BLOCKS = List.of(
            "STONE",
            "DIRT",
            "GRASS_BLOCK",
            "COBBLESTONE",
            "OAK_PLANKS",
            "OAK_LOG",
            "SAND",
            "GRAVEL",
            "IRON_BLOCK",
            "GOLD_BLOCK",
            "DIAMOND_BLOCK",
            "NETHERRACK",
            "END_STONE"
    );

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    /** 壁として扱うブロック（{@link #reload()} 時に丸ごと差し替える）。 */
    private volatile Set<Material> solidBlocks = Set.of();

    public CollisionManager(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        reload();
    }

    /**
     * {@code CollisionData/solid_blocks.yml} を読み直して壁ブロック一覧を再構築する。
     */
    public synchronized void reload() {
        Set<Material> parsed = EnumSet.noneOf(Material.class);
        int invalid = 0;

        for (String name : loadNames()) {
            Material material = matchMaterial(name);
            if (material == null) {
                invalid++;
                plugin.getLogger().warning(
                        "壁判定ブロックとして解釈できない Material です: '" + name + "' ("
                                + SOLID_BLOCKS_FILE + ")");
                continue;
            }
            parsed.add(material);
        }

        if (parsed.isEmpty()) {
            // 設定が空でも壁判定が壊れないよう、既定値で補完する。
            for (String name : DEFAULT_SOLID_BLOCKS) {
                Material material = matchMaterial(name);
                if (material != null) {
                    parsed.add(material);
                }
            }
        }

        this.solidBlocks = Set.copyOf(parsed);
        plugin.getLogger().info("壁判定ブロックを " + solidBlocks.size() + " 件読み込みました。"
                + (invalid > 0 ? " (無効: " + invalid + " 件)" : ""));
    }

    /**
     * 指定した Material が壁として扱うブロックか判定する。
     *
     * @param material 判定対象（{@code null} は {@code false}）
     * @return 壁ブロックの場合は {@code true}
     */
    public boolean isSolidBlock(Material material) {
        return material != null && solidBlocks.contains(material);
    }

    /**
     * 読み込み済みの壁ブロックを取得する（不変ビュー）。
     */
    public Set<Material> getSolidBlocks() {
        return solidBlocks;
    }

    /** YAML から {@code solid_blocks} の一覧を読み取る。未配置・空の場合は既定値を返す。 */
    private List<String> loadNames() {
        FileConfiguration config = yamlLoader.getConfig(SOLID_BLOCKS_FILE);
        if (config == null) {
            return DEFAULT_SOLID_BLOCKS;
        }
        List<String> names = config.getStringList(SOLID_BLOCKS_KEY);
        return names.isEmpty() ? DEFAULT_SOLID_BLOCKS : names;
    }

    /** Material 名を解決する（大文字小文字・名前空間接頭辞を許容する）。 */
    private static Material matchMaterial(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        Material material = Material.matchMaterial(normalized);
        if (material != null) {
            return material;
        }
        String withoutNamespace = normalized.contains(":")
                ? normalized.substring(normalized.indexOf(':') + 1)
                : normalized;
        return Material.matchMaterial(
                withoutNamespace.toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
