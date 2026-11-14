package com.zeloria.gui;

import com.zeloria.core.TextComponents;
import com.zeloria.core.Zeloria;
import com.zeloria.item.ItemCategory;
import com.zeloria.item.ItemData;
import com.zeloria.item.ItemService;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.player.PlayerData;
import com.zeloria.player.VirtualInventoryTab;
import com.zeloria.player.VirtualItemEntry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 販売屋 NPC が開く購入 GUI。
 * <p>
 * 商品枠には {@code ShopData/*.yml} の商品を仮想表示するだけで、アイテムの物理移動は行わない。
 * 商品をクリックすると選択中スロット（49）へ表示を移し、購入ボタンで選択中の数量を購入する。
 * 購入時は所持金を減算し、仮想ストレージへ追加してから DB 保存する。
 * 数量切替ボタンは 1 → 10 → 100 の順に切り替える。
 */
public final class ShopSellGui implements InventoryHolder {

    private static final List<Integer> DEFAULT_PRODUCT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);

    private static final List<Integer> DEFAULT_FILLER_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 18, 27, 36,
            17, 26, 35, 44);

    private static final Material DEFAULT_FILLER_MATERIAL =
            Material.WHITE_STAINED_GLASS_PANE;

    private static final long[] AMOUNT_STEPS = {1L, 10L, 100L};
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,###");

    private final Zeloria plugin;
    private final Player player;
    private final Inventory inventory;
    private final ItemService itemService;
    private final Map<String, ShopGuiSettings.ButtonConfig> buttons;
    private final List<Integer> productSlots;
    private final List<Integer> fillerSlots;
    private final Material fillerMaterial;

    /** 商品枠 → 商品。 */
    private final Map<Integer, Product> products = new LinkedHashMap<>();

    private Product selected;
    private long amountStep = AMOUNT_STEPS[0];
    private boolean closeSoundPlayed;

    private GuiSound openSound = GuiSound.NONE;
    private GuiSound closeSound = GuiSound.NONE;
    private GuiSound clickSound = GuiSound.NONE;
    private GuiSound errorSound = GuiSound.NONE;
    private GuiSound slotClickSound = GuiSound.NONE;

    private ShopSellGui(Zeloria plugin, Player player,
                        ShopGuiSettings settings, ShopDefinition shop) {
        this.plugin = plugin;
        this.player = player;
        this.itemService = plugin.getItemService();
        this.buttons = settings.buttons();

        String title = settings.title() == null || settings.title().isBlank()
                ? "<gold>販売屋</gold> <dark_gray>|</dark_gray> <white>アイテム購入</white>"
                : settings.title();
        this.inventory = Bukkit.createInventory(this, settings.size(),
                MiniMessage.miniMessage().deserialize(title));
        this.fillerMaterial = settings.fillerMaterial() == null
                ? DEFAULT_FILLER_MATERIAL : settings.fillerMaterial();
        this.fillerSlots = filterSlots(settings.fillerSlots(), DEFAULT_FILLER_SLOTS);
        this.productSlots = filterSlots(settings.productSlots(), DEFAULT_PRODUCT_SLOTS);

        loadSounds(settings);
        loadProducts(shop);
    }

    /**
     * 設定から販売屋 GUI を生成して開く。
     */
    public static ShopSellGui open(Zeloria plugin, Player player) {
        ShopConfigService service = plugin.getShopConfigService();
        ShopGuiSettings settings = service == null
                ? loadFallbackSettings(plugin) : service.sellGui();
        ShopDefinition shop = resolveShop(plugin, service, settings);

        ShopSellGui gui = new ShopSellGui(plugin, player, settings, shop);
        gui.renderFiller();
        gui.renderProducts();
        gui.renderButtons();
        player.openInventory(gui.inventory);
        gui.play(gui.openSound);
        return gui;
    }

    /**
     * サービス未初期化時でも GUI 設定を読み込めるようにする。
     */
    private static ShopGuiSettings loadFallbackSettings(Zeloria plugin) {
        ShopGuiSettings fallback = new ShopGuiSettings(
                ShopConfigService.SHOP_SELL_KEY,
                "<gold>販売屋</gold> <dark_gray>|</dark_gray> <white>アイテム購入</white>",
                6,
                DEFAULT_FILLER_MATERIAL,
                DEFAULT_FILLER_SLOTS,
                DEFAULT_PRODUCT_SLOTS,
                "weapon_shop",
                Map.of(),
                Map.of());
        return ShopConfigService.parseGui(
                plugin.getYamlLoader().getConfig(ShopConfigService.SHOP_SELL_KEY),
                ShopConfigService.SHOP_SELL_KEY,
                plugin.getConfigCache(),
                fallback);
    }

    private static ShopDefinition resolveShop(Zeloria plugin, ShopConfigService service,
                                              ShopGuiSettings settings) {
        String shopId = settings.shopId() == null || settings.shopId().isBlank()
                ? "weapon_shop" : settings.shopId();

        if (service != null) {
            Optional<ShopDefinition> found = service.findShop(shopId);
            if (found.isPresent()) {
                return found.get();
            }
            if (!service.shops().isEmpty()) {
                return service.shops().values().stream().findFirst().orElse(
                        new ShopDefinition(shopId, List.of()));
            }
            return new ShopDefinition(shopId, List.of());
        }

        // サービス未初期化時は YAML を直接読む。
        FileConfiguration config = plugin.getYamlLoader()
                .getConfig("ShopData/" + shopId + ".yml");
        List<ShopItemConfig> items = new ArrayList<>();
        if (config != null) {
            for (Map<?, ?> raw : config.getMapList("items")) {
                ShopItemConfig item = ShopItemConfig.fromMap(raw);
                if (item.isValid()) {
                    items.add(item);
                }
            }
        }
        return new ShopDefinition(shopId, items);
    }

    /**
     * 商品データを読み込み、商品枠へ割り当てる。
     */
    private void loadProducts(ShopDefinition shop) {
        for (ShopItemConfig config : shop.items()) {
            ItemData definition = findItem(config.itemId());
            if (definition == null) {
                plugin.getLogger().warning(
                        "ShopData の商品アイテムが見つかりません: " + config.itemId()
                                + " (shop=" + shop.id() + ")");
                continue;
            }

            int slot = config.slot();
            if (!productSlots.contains(slot) || products.containsKey(slot)) {
                slot = firstFreeProductSlot();
            }
            if (slot < 0) {
                plugin.getLogger().warning(
                        "販売屋の商品枠が不足しているため表示できません: " + config.itemId());
                break;
            }
            products.put(slot, new Product(config, definition));
        }
    }

    private ItemData findItem(String itemId) {
        if (itemService == null || itemId == null || itemId.isBlank()) {
            return null;
        }
        for (ItemCategory category : ItemCategory.values()) {
            Optional<ItemData> found = itemService.find(category, itemId);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }

    private int firstFreeProductSlot() {
        for (int slot : productSlots) {
            if (!products.containsKey(slot) && !isButtonSlot(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * YAML の効果音設定を読み込む。旧キー（success / deny）にもフォールバックする。
     */
    private void loadSounds(ShopGuiSettings settings) {
        openSound = settings.sound("open");
        closeSound = settings.sound("close");
        clickSound = settings.sound("click");
        errorSound = settings.sound("error", "deny");
        slotClickSound = settings.sound("slot_click", "click");
    }

    private List<Integer> filterSlots(List<Integer> candidates, List<Integer> fallback) {
        LinkedHashMap<Integer, Boolean> result = new LinkedHashMap<>();
        if (candidates != null) {
            for (int slot : candidates) {
                if (slot >= 0 && slot < inventory.getSize()) {
                    result.put(slot, Boolean.TRUE);
                }
            }
        }
        if (result.isEmpty() && fallback != null) {
            for (int slot : fallback) {
                if (slot >= 0 && slot < inventory.getSize()) {
                    result.put(slot, Boolean.TRUE);
                }
            }
        }
        return List.copyOf(result.keySet());
    }


    /**
     * 装飾枠を描画する。
     */
    public void renderFiller() {
        if (fillerMaterial != null) {
            for (int slot : fillerSlots) {
                if (!isButtonSlot(slot) && !products.containsKey(slot)) {
                    inventory.setItem(slot, new ItemStack(fillerMaterial));
                }
            }
        }
        renderBottomFiller();
    }

    /**
     * 商品枠を描画する。選択中の商品には「選択中」を付与する。
     */
    public void renderProducts() {
        for (int slot : productSlots) {
            Product product = products.get(slot);
            inventory.setItem(slot, product == null
                    ? null : createProductDisplay(product, product == selected));
        }
    }

    /**
     * ボタンを描画する。選択中スロット（selected_item）は商品表示を優先する。
     */
    public void renderButtons() {
        long balance = currentBalance();
        long price = calculatePrice();
        long quantity = currentStep();
        Map<String, String> placeholders = Map.of(
                "price", format(price),
                "money", format(balance),
                "balance", format(balance),
                "quantity", format(quantity),
                "amount", format(quantity),
                "item", selectedName(),
                "selected", selectedName());

        for (Map.Entry<String, ShopGuiSettings.ButtonConfig> entry : buttons.entrySet()) {
            ShopGuiSettings.ButtonConfig button = entry.getValue();
            if ("selected_item".equals(entry.getKey()) && selected != null) {
                inventory.setItem(button.slot(), createProductDisplay(selected, true));
                continue;
            }
            inventory.setItem(button.slot(), button.toItemStack(placeholders));
        }
    }

    /**
     * クリックを処理する。
     *
     * @param rawSlot クリックされた生スロット番号
     * @return 処理した場合は {@code true}
     */
    public boolean handleClick(int rawSlot) {
        String buttonId = buttonId(rawSlot);
        if (buttonId != null) {
            switch (buttonId) {
                case "buy", "buy_confirm" -> confirmBuy();
                case "quantity" -> cycleAmount();
                case "close" -> {
                    play(buttonSound("close"));
                    closeSoundPlayed = true;
                    player.closeInventory();
                }
                default -> play(buttonSound(buttonId));
            }
            return true;
        }

        Product product = products.get(rawSlot);
        if (product != null) {
            selectProduct(product);
            return true;
        }

        if (fillerSlots.contains(rawSlot) || productSlots.contains(rawSlot)
                || isBottomRow(rawSlot)) {
            play(errorSound);
            return true;
        }
        return rawSlot >= 0 && rawSlot < inventory.getSize();
    }

    private void selectProduct(Product product) {
        if (product == null) {
            return;
        }
        this.selected = product;
        play(slotClickSound);
        renderProducts();
        renderButtons();
    }

    /**
     * 数量を 1 → 10 → 100 の順に切り替える。
     */
    private void cycleAmount() {
        int index = 0;
        for (int i = 0; i < AMOUNT_STEPS.length; i++) {
            if (AMOUNT_STEPS[i] == currentStep()) {
                index = i;
                break;
            }
        }
        amountStep = AMOUNT_STEPS[(index + 1) % AMOUNT_STEPS.length];
        play(buttonSound("quantity"));
        renderButtons();
    }

    /**
     * 選択中の商品を指定数量購入する。
     */
    private void confirmBuy() {
        if (selected == null) {
            play(errorSound);
            message("not_selected", Map.of());
            return;
        }

        VirtualInventoryService virtualInventory = plugin.getVirtualInventoryService();
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (virtualInventory == null || data == null) {
            play(errorSound);
            message("not_ready", Map.of());
            return;
        }

        long quantity = currentStep();
        long total = calculatePrice();
        long balance = data.getMoney();
        if (balance < total) {
            play(errorSound);
            message("insufficient_money", Map.of(
                    "price", format(total),
                    "balance", format(balance)));
            return;
        }

        if (!canStore(data, selected.definition(), quantity)) {
            play(errorSound);
            message("storage_full", Map.of());
            return;
        }

        // 所持金を減算してから仮想ストレージへ追加する。
        data.setMoney(balance - total);
        long added = virtualInventory.addItem(
                player, selected.definition().id(), quantity);
        if (added < quantity) {
            // 事前確認後に失敗した場合は所持金を戻す。
            data.setMoney(balance);
            play(errorSound);
            message("storage_full", Map.of());
            return;
        }

        var playerService = plugin.getPlayerService();
        if (playerService != null) {
            playerService.save(player.getUniqueId(), player.getName());
        }

        play(buttonSound("buy"));
        message("bought", Map.of(
                "item", selectedName(),
                "quantity", format(quantity),
                "price", format(total),
                "balance", format(data.getMoney())));
        renderButtons();
    }


    /**
     * 仮想ストレージへ指定数量を格納できるか判定する。
     * <p>
     * 武器・防具は 1 枠 1 個、素材などは同一 ID の枠へ加算される
     * {@link VirtualInventoryService#addItem(Player, String, long)} の仕様に合わせる。
     */
    private boolean canStore(PlayerData data, ItemData definition, long amount) {
        if (data == null || definition == null || amount <= 0L) {
            return false;
        }
        boolean singleInstance = definition.category() == ItemCategory.ARMOR
                || definition.category() == ItemCategory.WEAPON;
        VirtualInventoryTab tab = singleInstance
                ? VirtualInventoryTab.EQUIPMENT_STORAGE
                : VirtualInventoryTab.STORAGE;
        Map<String, VirtualItemEntry> bag = data.getVirtualBag(tab);

        if (singleInstance) {
            long free = VirtualInventoryService.BAG_MAX_ENTRIES - bag.size();
            return free >= amount;
        }

        VirtualItemEntry existing = bag.get(definition.id());
        if (existing == null && bag.size() >= VirtualInventoryService.BAG_MAX_ENTRIES) {
            return false;
        }
        long current = existing == null ? 0L : existing.amount();
        return current + amount <= VirtualItemEntry.MAX_AMOUNT;
    }

    /**
     * 選択中の商品を現在の数量で購入した場合の合計金額。
     */
    private long calculatePrice() {
        if (selected == null) {
            return 0L;
        }
        return safeMultiply(selected.config().price(), currentStep());
    }

    private long currentBalance() {
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        return data == null ? 0L : data.getMoney();
    }

    private long currentStep() {
        return amountStep <= 0L ? AMOUNT_STEPS[0] : amountStep;
    }

    /**
     * 商品の表示アイテムを生成する。価格と選択状態を Lore に付与する。
     */
    private ItemStack createProductDisplay(Product product, boolean selectedProduct) {
        ItemStack stack = itemService == null
                ? new ItemStack(product.definition().material())
                : itemService.createItem(product.definition());
        stack.setAmount(1);

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.lore() == null
                    ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(TextComponents.mini(MiniMessage.miniMessage(),
                    "<gray>価格: <gold>" + format(product.config().price())
                            + " コイン</gold></gray>"));
            lore.add(TextComponents.mini(MiniMessage.miniMessage(),
                    selectedProduct
                            ? "<green>選択中</green>"
                            : "<dark_gray>クリックで選択</dark_gray>"));
            meta.lore(TextComponents.noItalic(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String selectedName() {
        if (selected == null) {
            return "未選択";
        }
        return MiniMessage.miniMessage().stripTags(selected.definition().name());
    }

    /**
     * 指定スロットに割り当てられたボタン ID を返す。未定義の場合は {@code null}。
     */
    private String buttonId(int slot) {
        for (Map.Entry<String, ShopGuiSettings.ButtonConfig> entry : buttons.entrySet()) {
            if (entry.getValue().slot() == slot) {
                return entry.getKey();
            }
        }
        return null;
    }

    private GuiSound buttonSound(String id) {
        ShopGuiSettings.ButtonConfig button = buttons.get(id);
        if (button != null && button.sound().isPresent()) {
            return button.sound();
        }
        return clickSound;
    }

    private boolean isButtonSlot(int slot) {
        for (ShopGuiSettings.ButtonConfig button : buttons.values()) {
            if (button.slot() == slot) {
                return true;
            }
        }
        return false;
    }

    private boolean isBottomRow(int slot) {
        int start = Math.max(0, inventory.getSize() - 9);
        return slot >= start && slot < inventory.getSize();
    }

    /**
     * 一番下の列（45〜53）のうち、ボタン・商品が無いスロットを白ガラスで埋める。
     */
    private void renderBottomFiller() {
        int start = Math.max(0, inventory.getSize() - 9);
        for (int slot = start; slot < inventory.getSize(); slot++) {
            if (isButtonSlot(slot) || products.containsKey(slot)) {
                continue;
            }
            inventory.setItem(slot, new ItemStack(Material.WHITE_STAINED_GLASS_PANE));
        }
    }


    /**
     * GUI を閉じたときの後処理。
     */
    public void handleClose() {
        if (!closeSoundPlayed) {
            play(closeSound);
        }
        selected = null;
    }

    /**
     * この GUI を開いているプレイヤーの UUID。
     */
    public UUID ownerId() {
        return player.getUniqueId();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void play(GuiSound sound) {
        if (sound != null) {
            sound.play(player);
        }
    }

    private void message(String key, Map<String, String> placeholders) {
        var messageService = plugin.getMessageService();
        if (messageService != null) {
            messageService.send(player, "shop.yml", "shop_sell." + key, placeholders);
        }
    }

    private static String format(long value) {
        return NUMBER_FORMAT.format(Math.max(0L, value));
    }

    private static long safeMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 商品 1 件分。設定とアイテム定義を保持する。
     */
    private record Product(ShopItemConfig config, ItemData definition) {
    }
}

