package com.zeloria.gui;

import com.zeloria.core.ConfigCache;
import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 店舗 GUI（買取屋・販売屋）の設定を読み込むサービス。
 * <p>
 * 次の設定を保持する。
 * <ul>
 *     <li>{@code GuiData/shop_buy.yml}: 買取屋 GUI のボタン・効果音</li>
 *     <li>{@code GuiData/shop_sell.yml}: 販売屋 GUI の商品枠・ボタン・効果音</li>
 *     <li>{@code ShopData/*.yml}: 店舗ごとの商品リスト</li>
 * </ul>
 * {@code /admin reload} から {@link #reload()} を呼ぶと最新の設定を反映できる。
 */
public final class ShopConfigService {

    /** 買取屋 GUI 設定のキャッシュキー。 */
    public static final String SHOP_BUY_KEY = "GuiData/shop_buy.yml";

    /** 販売屋 GUI 設定のキャッシュキー。 */
    public static final String SHOP_SELL_KEY = "GuiData/shop_sell.yml";

    /** 店舗データを配置するディレクトリ。 */
    private static final String SHOP_DATA_DIR = "ShopData";

    /** 装飾枠の既定スロット。 */
    private static final List<Integer> DEFAULT_FILLER_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 18, 27, 36,
            17, 26, 35, 44);

    /** 商品／売却枠の既定スロット（4 行×7 列）。 */
    private static final List<Integer> DEFAULT_PRODUCT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;
    private final ConfigCache cache;

    private volatile ShopGuiSettings buyGui;
    private volatile ShopGuiSettings sellGui;
    private volatile Map<String, ShopDefinition> shops = Map.of();

    public ShopConfigService(Zeloria plugin, YamlLoader yamlLoader, ConfigCache cache) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        this.cache = cache;
        reload();
    }


    /**
     * 店舗関連の設定を再読み込みする。
     */
    public synchronized void reload() {
        ShopGuiSettings buyFallback = new ShopGuiSettings(
                SHOP_BUY_KEY,
                "<gold>買取屋</gold> <dark_gray>|</dark_gray> <white>アイテム売却</white>",
                6,
                Material.WHITE_STAINED_GLASS_PANE,
                DEFAULT_FILLER_SLOTS,
                DEFAULT_PRODUCT_SLOTS,
                "",
                Map.of(),
                Map.of());

        ShopGuiSettings sellFallback = new ShopGuiSettings(
                SHOP_SELL_KEY,
                "<gold>販売屋</gold> <dark_gray>|</dark_gray> <white>アイテム購入</white>",
                6,
                Material.WHITE_STAINED_GLASS_PANE,
                DEFAULT_FILLER_SLOTS,
                DEFAULT_PRODUCT_SLOTS,
                "weapon_shop",
                Map.of(),
                Map.of());

        FileConfiguration buyConfig = yamlLoader.getConfig(SHOP_BUY_KEY);
        FileConfiguration sellConfig = yamlLoader.getConfig(SHOP_SELL_KEY);
        if (buyConfig == null) {
            plugin.getLogger().warning(
                    "買取屋 GUI 設定が見つかりません: " + SHOP_BUY_KEY + "（既定値で動作します）");
        }
        if (sellConfig == null) {
            plugin.getLogger().warning(
                    "販売屋 GUI 設定が見つかりません: " + SHOP_SELL_KEY + "（既定値で動作します）");
        }

        this.buyGui = parseGui(buyConfig, SHOP_BUY_KEY, cache, buyFallback);
        this.sellGui = parseGui(sellConfig, SHOP_SELL_KEY, cache, sellFallback);
        this.shops = loadShops();

        plugin.getLogger().info("店舗設定を読み込みました (ShopData: " + uniqueShopCount()
                + " 件, 販売ボタン: " + sellGui.buttons().size()
                + ", 買取ボタン: " + buyGui.buttons().size() + ")。");
    }

    /**
     * 買取屋 GUI の設定を取得する。
     */
    public ShopGuiSettings buyGui() {
        return buyGui;
    }

    /**
     * 販売屋 GUI の設定を取得する。
     */
    public ShopGuiSettings sellGui() {
        return sellGui;
    }

    /**
     * 店舗 ID（またはファイル名）から店舗定義を取得する。
     */
    public Optional<ShopDefinition> findShop(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(shops.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 読み込み済みの店舗定義を取得する（不変ビュー）。
     */
    public Map<String, ShopDefinition> shops() {
        return shops;
    }

    /**
     * YAML から GUI 設定を解析する。
     * <p>
     * {@code ShopBuyGui} など、サービス未初期化時にも同じ解析結果を使えるよう
     * public static として公開する。
     *
     * @param config    設定
     * @param sourceKey 設定ファイルのキャッシュキー
     * @param cache     Material / Sound の解決キャッシュ
     * @param fallback  設定が無い場合の既定値
     * @return 解析済み GUI 設定
     */
    public static ShopGuiSettings parseGui(FileConfiguration config, String sourceKey,
                                          ConfigCache cache, ShopGuiSettings fallback) {
        if (config == null) {
            return fallback;
        }

        int rows = Math.max(1, Math.min(6,
                config.getInt("rows", fallback.rows())));

        String title = config.getString("title");
        if (title == null || title.isBlank()) {
            title = fallback.title();
        }

        ConfigurationSection filler = config.getConfigurationSection("filler");
        Material fillerMaterial = filler == null ? null
                : ConfigCache.resolveMaterial(filler.getString("material"));
        if (fillerMaterial == null) {
            fillerMaterial = fallback.fillerMaterial();
        }
        List<Integer> fillerSlots = readSlots(
                config, "filler.slots", rows * 9, fallback.fillerSlots());
        List<Integer> productSlots = readSlots(
                config, "product_slots", rows * 9, fallback.productSlots());

        String shopId = text(config.getString("shop"));
        if (shopId.isBlank()) {
            shopId = text(config.getString("shop-id"));
        }
        if (shopId.isBlank()) {
            shopId = text(config.getString("shop_id"));
        }
        if (shopId.isBlank()) {
            shopId = fallback.shopId();
        }

        Map<String, GuiSound> sounds = readSounds(
                config.getConfigurationSection("sounds"), sourceKey, cache);
        Map<String, ShopGuiSettings.ButtonConfig> buttons = readButtons(
                config, rows * 9, cache, sounds);

        return new ShopGuiSettings(sourceKey, title, rows, fillerMaterial,
                fillerSlots, productSlots, shopId, buttons, sounds);
    }

    /**
     * {@code ShopData/*.yml} を読み込み、店舗 ID・ファイル名の両方で引けるようにする。
     */
    private Map<String, ShopDefinition> loadShops() {
        Map<String, ShopDefinition> result = new LinkedHashMap<>();
        for (String key : yamlLoader.getKeysUnder(SHOP_DATA_DIR)) {
            FileConfiguration config = yamlLoader.getConfig(key);
            if (config == null) {
                continue;
            }

            List<ShopItemConfig> items = new ArrayList<>();
            for (Map<?, ?> raw : config.getMapList("items")) {
                ShopItemConfig item = ShopItemConfig.fromMap(raw);
                if (item.isValid()) {
                    items.add(item);
                } else {
                    plugin.getLogger().warning(
                            "ShopData の商品設定が不正です: " + key + " (item-id="
                                    + item.itemId() + ", price=" + item.price()
                                    + ", slot=" + item.slot() + ")");
                }
            }

            String fileName = fileNameOf(key);
            String id = text(config.getString("id"));
            if (id.isBlank()) {
                id = fileName;
            }
            ShopDefinition definition = new ShopDefinition(id, items);
            result.put(id.toLowerCase(Locale.ROOT), definition);
            result.put(fileName.toLowerCase(Locale.ROOT), definition);
        }
        return Map.copyOf(new LinkedHashMap<>(result));
    }


    private static Map<String, ShopGuiSettings.ButtonConfig> readButtons(
            FileConfiguration config, int size, ConfigCache cache,
            Map<String, GuiSound> sounds) {
        ConfigurationSection section = config.getConfigurationSection("buttons");
        if (section == null) {
            // 旧形式（items）との互換。
            section = config.getConfigurationSection("items");
        }
        if (section == null) {
            return Map.of();
        }

        GuiSound clickSound = sounds.getOrDefault("click", GuiSound.NONE);
        Map<String, ShopGuiSettings.ButtonConfig> result = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection button = section.getConfigurationSection(id);
            if (button == null) {
                continue;
            }

            int slot = button.getInt("slot", -1);
            Material material = ConfigCache.resolveMaterial(button.getString("material"));
            if (slot < 0 || slot >= size || material == null) {
                continue;
            }

            String soundKey = text(button.getString("sound"));
            GuiSound sound = soundKey.isBlank() ? clickSound
                    : sounds.getOrDefault(soundKey, clickSound);

            result.put(id, new ShopGuiSettings.ButtonConfig(
                    id,
                    slot,
                    material,
                    Math.max(1, button.getInt("amount", 1)),
                    button.getString("name", ""),
                    button.getStringList("lore"),
                    soundKey,
                    sound));
        }
        return Map.copyOf(new LinkedHashMap<>(result));
    }

    private static Map<String, GuiSound> readSounds(ConfigurationSection section,
                                                    String sourceKey,
                                                    ConfigCache cache) {
        if (section == null) {
            return Map.of();
        }

        Map<String, GuiSound> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            GuiSound sound = GuiSound.from(
                    section.getConfigurationSection(key), GuiSound.NONE, cache, sourceKey);
            if (sound.isPresent()) {
                result.put(key, sound);
            }
        }
        return Map.copyOf(new LinkedHashMap<>(result));
    }

    /**
     * スロット番号のリストを設定から読み込む。入れ子のリストにも対応する。
     */
    private static List<Integer> readSlots(FileConfiguration config, String path, int size,
                                           List<Integer> fallback) {
        List<Integer> slots = new ArrayList<>();
        if (config != null) {
            for (Object raw : config.getList(path, List.of())) {
                collectSlots(slots, raw, size);
            }
        }
        if (slots.isEmpty()) {
            for (int slot : fallback) {
                if (slot >= 0 && slot < size) {
                    slots.add(slot);
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(slots));
    }


    private static void collectSlots(List<Integer> slots, Object raw, int size) {
        if (raw instanceof List<?> nested) {
            for (Object value : nested) {
                collectSlots(slots, value, size);
            }
            return;
        }
        int slot = parseSlot(raw);
        if (slot >= 0 && slot < size && !slots.contains(slot)) {
            slots.add(slot);
        }
    }

    private static int parseSlot(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        String fileName = slash >= 0 ? key.substring(slash + 1) : key;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private int uniqueShopCount() {
        return (int) shops.values().stream().map(ShopDefinition::id).distinct().count();
    }
}

