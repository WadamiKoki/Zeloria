package com.zeloria.npc;

import com.zeloria.gui.ShopItemConfig;
import com.zeloria.mob.MobData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@code config/EntityData/npc/*.yml} から読み込んだ NPC 定義。
 * <p>
 * 1 ファイルが 1 種類の NPC に対応する。クリック時の動作は
 * {@code shop} と {@code interact} のどちらか一方のみを定義する（排他）。
 * 両方ある場合は {@code shop} を優先し、{@code interact} は無視する。
 * <pre>
 * id: "guide"                     # 省略時はファイル名（拡張子なし）
 * name: "&lt;green&gt;案内人&lt;/green&gt;" # 表示名（MiniMessage 可）
 * mobType: VILLAGER               # スポーンさせるエンティティ種別
 * alwaysShowName: true            # 名前を常時表示するか
 * equipment:
 *   mainHand: AIR
 *   helmet: AIR
 *   chestplate: AIR
 *   leggings: AIR
 *   boots: AIR
 *
 * # パターンA: ショップ NPC（買取屋・販売屋）
 * shop:
 *   template: "sell"              # "buy"（買取屋）または "sell"（販売屋）
 *   items:                        # 販売屋のみ。記述順に商品エリアへ自動配置する
 *     - item-id: "sword_test_sword"
 *       price: 500
 *
 * # パターンB: インタラクト NPC
 * interact:
 *   type: "gui"                   # "gui" / "command" / "none"
 *   gui-id: "menu"                # type=gui の場合
 *   commands:                     # type=command の場合（コンソール実行）
 *     - "say hello {player}"
 * </pre>
 * どちらも定義されていない NPC は通常 NPC として扱い、クリックしても何もしない。
 *
 * @param id               NPC ID（コマンド引数・識別子）
 * @param name             表示名（MiniMessage 形式）
 * @param mobType          スポーンさせるエンティティ種別
 * @param alwaysShowName   名前を常時表示するか
 * @param equipment        装備設定
 * @param shopTemplate     店舗テンプレート（{@code "buy"} / {@code "sell"}。未指定なら空文字）
 * @param shopItems        店舗で扱う商品一覧（記述順）
 * @param interactType     汎用アクション種別（{@code "gui"} / {@code "command"} / {@code "none"}）
 * @param interactGuiId    {@code type=gui} のときに開く GUI ID
 * @param interactCommands {@code type=command} のときコンソール実行するコマンド
 */
public record NpcData(
        String id,
        String name,
        EntityType mobType,
        boolean alwaysShowName,
        NpcEquipment equipment,
        String shopTemplate,
        List<ShopItemConfig> shopItems,
        String interactType,
        String interactGuiId,
        List<String> interactCommands
) {

    /** {@code mobType} が未指定・解決不能のときに使用する既定のエンティティ種別。 */
    public static final EntityType DEFAULT_MOB_TYPE = EntityType.VILLAGER;

    /** 買取屋テンプレート。 */
    public static final String SHOP_TEMPLATE_BUY = "buy";

    /** 販売屋テンプレート。 */
    public static final String SHOP_TEMPLATE_SELL = "sell";

    /** GUI を開くインタラクト種別。 */
    public static final String INTERACT_TYPE_GUI = "gui";

    /** コマンドを実行するインタラクト種別。 */
    public static final String INTERACT_TYPE_COMMAND = "command";

    /** 何もしないインタラクト種別。 */
    public static final String INTERACT_TYPE_NONE = "none";

    /** コマンド内でプレイヤー名に置換するプレースホルダー。 */
    public static final String PLAYER_PLACEHOLDER = "{player}";

    private static final String YAML_EXTENSION = ".yml";

    public NpcData {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name;
        mobType = mobType == null ? DEFAULT_MOB_TYPE : mobType;
        equipment = equipment == null ? NpcEquipment.EMPTY : equipment;

        shopTemplate = normalizeShopTemplate(shopTemplate);
        shopItems = shopItems == null ? List.of() : List.copyOf(shopItems);

        interactType = normalizeInteractType(interactType);
        if (interactType.isEmpty()) {
            interactType = INTERACT_TYPE_NONE;
        }
        interactGuiId = interactGuiId == null ? "" : interactGuiId.trim();
        interactCommands = interactCommands == null ? List.of() : List.copyOf(interactCommands);

        // shop と interact は排他。shop が有効な場合は interact を無効化する。
        if (isShopTemplate(shopTemplate)) {
            interactType = INTERACT_TYPE_NONE;
            interactGuiId = "";
            interactCommands = List.of();
        }
    }

    /**
     * YAML 設定から NPC 定義を生成する。
     * <p>
     * {@code shop} と {@code interact} が同時に定義されている場合は {@code shop} を優先し、
     * {@code interact} は解析しない。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code EntityData/npc/guide.yml}）
     * @param config    読み込み済み設定
     * @param warning   警告ログの出力先（{@code null} の場合は出力しない）
     * @return NPC 定義
     */
    public static NpcData fromConfig(String sourceKey, FileConfiguration config,
                                     Consumer<String> warning) {
        Objects.requireNonNull(config, "config");
        Consumer<String> warn = warning;
        if (warn == null) {
            warn = message -> { };
        }

        String fallbackId = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallbackId);
        String name = text(config.getString("name"), id);

        ConfigurationSection shop = config.getConfigurationSection("shop");
        ConfigurationSection interact = config.getConfigurationSection("interact");
        if (shop != null && interact != null) {
            warn.accept("NPC の shop と interact が同時に定義されています。"
                    + "shop を優先し、interact を無視します: " + sourceKey);
            interact = null;
        }

        String shopTemplate = readShopTemplate(shop, sourceKey, warn);
        List<ShopItemConfig> shopItems = readShopItems(shop);
        String interactType = readInteractType(interact, sourceKey, warn);
        String interactGuiId = readInteractGuiId(interact);
        List<String> interactCommands = readInteractCommands(interact);
        if (INTERACT_TYPE_GUI.equals(interactType) && interactGuiId.isBlank()) {
            warn.accept("interact.type=gui ですが gui-id が空です: " + sourceKey);
        }
        if (INTERACT_TYPE_COMMAND.equals(interactType) && interactCommands.isEmpty()) {
            warn.accept("interact.type=command ですが commands が空です: " + sourceKey);
        }

        return new NpcData(
                id,
                name,
                resolveMobType(config.getString("mobType")),
                config.getBoolean("alwaysShowName", true),
                NpcEquipment.fromConfig(config.getConfigurationSection("equipment")),
                shopTemplate,
                shopItems,
                interactType,
                interactGuiId,
                interactCommands
        );
    }

    /**
     * スポーンに使用するエンティティ種別を返す。
     */
    public EntityType spawnableMobType() {
        return mobType == null ? DEFAULT_MOB_TYPE : mobType;
    }

    /**
     * 汎用アクション（interact）が実行可能な設定か判定する。
     */
    public boolean hasInteract() {
        return switch (interactType) {
            case INTERACT_TYPE_GUI -> !interactGuiId.isBlank();
            case INTERACT_TYPE_COMMAND -> !interactCommands.isEmpty();
            default -> false;
        };
    }

    /**
     * 店舗 NPC として設定されているか判定する。
     */
    public boolean hasShop() {
        return isShopTemplate(shopTemplate);
    }

    /**
     * 買取屋テンプレートか判定する。
     */
    public boolean isBuyShop() {
        return SHOP_TEMPLATE_BUY.equals(shopTemplate);
    }

    /**
     * 販売屋テンプレートか判定する。
     */
    public boolean isSellShop() {
        return SHOP_TEMPLATE_SELL.equals(shopTemplate);
    }

    /**
     * {@code shop.template} を読み込む。{@code buy} / {@code sell} 以外は未指定として扱う。
     */
    private static String readShopTemplate(ConfigurationSection shop, String sourceKey,
                                           Consumer<String> warn) {
        if (shop == null) {
            return "";
        }
        String normalized = normalizeShopTemplate(shop.getString("template"));
        if (normalized.isEmpty()) {
            warn.accept("shop.template が不正です（buy / sell を指定してください）: " + sourceKey);
        }
        return normalized;
    }

    /**
     * 有効な店舗テンプレートか判定する。
     */
    private static boolean isShopTemplate(String template) {
        return SHOP_TEMPLATE_BUY.equals(template) || SHOP_TEMPLATE_SELL.equals(template);
    }

    /**
     * {@code shop.items} を読み込む。
     * <p>
     * 買取屋では {@code items} を必要としないため、未指定なら空リストとなる。
     */
    private static List<ShopItemConfig> readShopItems(ConfigurationSection shop) {
        if (shop == null) {
            return List.of();
        }

        List<ShopItemConfig> items = new ArrayList<>();
        for (Map<?, ?> raw : shop.getMapList("items")) {
            ShopItemConfig item = ShopItemConfig.fromMap(raw);
            if (item.isValid()) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    /**
     * {@code interact.type} を読み込む。未指定なら {@code none}。
     */
    private static String readInteractType(ConfigurationSection interact, String sourceKey,
                                           Consumer<String> warn) {
        if (interact == null) {
            return INTERACT_TYPE_NONE;
        }
        String raw = interact.getString("type");
        String normalized = normalizeInteractType(raw);
        if (normalized.isEmpty()) {
            if (raw != null && !raw.isBlank()) {
                warn.accept("interact.type が不正です（gui / command / none を指定してください）: "
                        + sourceKey);
            }
            return INTERACT_TYPE_NONE;
        }
        return normalized;
    }

    /**
     * {@code interact.gui-id} を読み込む（{@code gui_id} も受け付ける）。
     */
    private static String readInteractGuiId(ConfigurationSection interact) {
        if (interact == null) {
            return "";
        }
        String guiId = text(interact.getString("gui-id"), "");
        return guiId.isBlank() ? text(interact.getString("gui_id"), "") : guiId;
    }

    /**
     * {@code interact.commands} を読み込む。空行は無視する。
     */
    private static List<String> readInteractCommands(ConfigurationSection interact) {
        if (interact == null) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (String command : interact.getStringList("commands")) {
            if (command != null && !command.isBlank()) {
                commands.add(command.trim());
            }
        }
        return List.copyOf(commands);
    }

    /**
     * {@code interact.type} を正規化する。未指定は {@code none}、不正値は空文字。
     */
    private static String normalizeInteractType(String raw) {
        if (raw == null || raw.isBlank()) {
            return INTERACT_TYPE_NONE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case INTERACT_TYPE_GUI, INTERACT_TYPE_COMMAND, INTERACT_TYPE_NONE -> normalized;
            default -> "";
        };
    }

    /**
     * {@code buy} / {@code sell} 以外を空文字へ正規化する（大文字小文字は区別しない）。
     */
    private static String normalizeShopTemplate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return SHOP_TEMPLATE_BUY.equals(normalized) || SHOP_TEMPLATE_SELL.equals(normalized)
                ? normalized : "";
    }

    /**
     * {@code mobType} を {@link EntityType} へ解決する。
     * <p>
     * {@code villager} / {@code minecraft:villager} / {@code VILLAGER} のいずれも受け付け、
     * 未指定・解決不能の場合は {@link #DEFAULT_MOB_TYPE} を返す。
     */
    private static EntityType resolveMobType(String raw) {
        EntityType resolved = MobData.resolveEntityType(raw);
        return resolved == null ? DEFAULT_MOB_TYPE : resolved;
    }

    /**
     * {@code EntityData/npc/<ファイル名>.yml} から拡張子なしファイル名を取り出す。
     */
    private static String fallbackId(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return "";
        }
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        return fileName.toLowerCase(Locale.ROOT).endsWith(YAML_EXTENSION)
                ? fileName.substring(0, fileName.length() - YAML_EXTENSION.length())
                : fileName;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
