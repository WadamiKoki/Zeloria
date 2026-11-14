package com.zeloria.gui;

import com.zeloria.core.ConfigCache;
import com.zeloria.core.TextComponents;
import com.zeloria.core.Zeloria;
import com.zeloria.item.ItemData;
import com.zeloria.item.ItemService;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.item.VirtualItem;
import com.zeloria.player.PlayerData;
import com.zeloria.player.VirtualInventoryTab;
import com.zeloria.player.VirtualItemEntry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 買取屋 NPC が開く売却 GUI。
 * <p>
 * 売却枠は 4 行×7 列（10〜16, 19〜25, 28〜34, 37〜43）で、最下段に操作ボタンを配置する。
 * 売却枠にはアイテムを物理移動せず、仮想ストレージのアイテムを「売却予約」として
 * {@link VirtualItem#mark(ItemStack)} 済みの仮想表示だけを置く。仮想ストレージの実体は
 * 売却確定まで減らさず、GUI を閉じた場合は予約を破棄するだけとする（返却・ドロップは行わない）。
 * 数量切替ボタンは、1 回のクリックで予約する個数を 1 → 10 → 100 → 1000 → 10000 の順に切り替える。
 */
public final class ShopBuyGui implements InventoryHolder {

    /** 売却枠（4 行×7 列）。YAML に sell_slots が無い場合の既定値。 */
    private static final List<Integer> DEFAULT_SELL_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    /** 装飾枠。YAML に filler.slots が無い場合の既定値。 */
    private static final List<Integer> DEFAULT_FILLER_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 18, 27, 36,
            17, 26, 35, 44);
    private static final Material DEFAULT_FILLER_MATERIAL = Material.WHITE_STAINED_GLASS_PANE;

    private static final long[] DEFAULT_AMOUNT_STEPS = {1L, 10L, 100L, 1_000L, 10_000L};
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,###");

    private final Zeloria plugin;
    private final Player player;
    private final Inventory inventory;
    private final ItemService itemService;
    private final long[] amountSteps;
    private final Map<String, Button> buttons = new LinkedHashMap<>();

    /** 売却予約。キーは売却枠のスロット番号。 */
    private final Map<Integer, Reservation> reservations = new LinkedHashMap<>();
    /** 売却枠として使用するスロット番号。 */
    private final List<Integer> sellSlots;
    /** 装飾枠として使用するスロット番号。 */
    private final List<Integer> fillerSlots;
    /** 装飾枠のマテリアル。 */
    private final Material fillerMaterial;

    private GuiSound clickSound = GuiSound.NONE;
    private GuiSound denySound = GuiSound.NONE;
    private GuiSound openSound = GuiSound.NONE;
    private GuiSound closeSound = GuiSound.NONE;
    private GuiSound slotClickSound = GuiSound.NONE;
    private GuiSound slotReserveSound = GuiSound.NONE;
    private ShopGuiSettings settings;
    private boolean closeSoundPlayed;
    private long amountStep;

    private ShopBuyGui(Zeloria plugin, Player player, int rows, String title,
                       long[] amountSteps, FileConfiguration config) {
        this.plugin = plugin;
        this.player = player;
        this.itemService = plugin.getItemService();
        this.amountSteps = amountSteps == null || amountSteps.length == 0
                ? DEFAULT_AMOUNT_STEPS.clone() : amountSteps.clone();
        this.amountStep = this.amountSteps[0];
        String resolvedTitle = title == null || title.isBlank()
                ? "<dark_gray>買取屋</dark_gray> <gray>- 売却</gray>" : title;
        this.inventory = Bukkit.createInventory(this, Math.max(6, rows) * 9,
                MiniMessage.miniMessage().deserialize(resolvedTitle));
        this.fillerMaterial = readFillerMaterial(config);
        this.fillerSlots = readSlots(config, "filler.slots", inventory.getSize(),
                DEFAULT_FILLER_SLOTS);
        loadButtons(config);
        this.sellSlots = readSellSlots(config, inventory.getSize());
    }

    /**
     * 設定から GUI を生成して開く。
     *
     * @return 生成した GUI
     */
    public static ShopBuyGui open(Zeloria plugin, Player player, FileConfiguration config) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");

        ConfigurationSection legacy = config == null ? null : config.getConfigurationSection("gui");
        int defaultRows = legacy == null ? 6 : legacy.getInt("rows", 6);
        int rows = config == null ? 6 : Math.max(1, Math.min(6, config.getInt("rows", defaultRows)));
        String title = config == null ? null : config.getString("title");
        if ((title == null || title.isBlank()) && legacy != null) {
            title = legacy.getString("title");
        }

        ShopBuyGui shop = new ShopBuyGui(plugin, player, rows, title, readAmountSteps(config), config);

        ShopConfigService service = plugin.getShopConfigService();
        ShopGuiSettings settings;
        if (service != null) {
            settings = service.buyGui();
        } else {
            ShopGuiSettings fallback = new ShopGuiSettings(
                    ShopConfigService.SHOP_BUY_KEY,
                    "<gold>買取屋</gold> <dark_gray>|</dark_gray> <white>アイテム売却</white>",
                    rows,
                    DEFAULT_FILLER_MATERIAL,
                    DEFAULT_FILLER_SLOTS,
                    DEFAULT_SELL_SLOTS,
                    "",
                    Map.of(),
                    Map.of());
            settings = ShopConfigService.parseGui(config,
                    ShopConfigService.SHOP_BUY_KEY, plugin.getConfigCache(), fallback);
        }
        shop.applySettings(settings);

        shop.renderFiller();
        shop.renderSaleSlots();
        shop.renderButtons();
        player.openInventory(shop.inventory);
        shop.play(shop.openSound);
        return shop;
    }

    /**
     * YAML の効果音設定とボタン別効果音を適用する。
     */
    private void applySettings(ShopGuiSettings settings) {
        this.settings = settings;
        if (settings == null) {
            return;
        }
        this.openSound = settings.sound("open");
        this.closeSound = settings.sound("close");
        this.clickSound = settings.sound("click");
        this.denySound = settings.sound("error", "deny");
        this.slotClickSound = settings.sound("slot_click", "click");
        this.slotReserveSound = settings.sound("slot_reserve", "slot_click");
        if (!this.slotReserveSound.isPresent()) {
            this.slotReserveSound = this.slotClickSound;
        }
    }

    /**
     * 数量切替の候補を設定から読み込む。
     */
    private static long[] readAmountSteps(FileConfiguration config) {
        if (config == null) {
            return DEFAULT_AMOUNT_STEPS.clone();
        }
        List<Long> values = new ArrayList<>();
        for (Object raw : config.getList("amount_steps", List.of())) {
            if (raw instanceof Number number && number.longValue() > 0L) {
                values.add(number.longValue());
            }
        }
        if (values.isEmpty()) {
            return DEFAULT_AMOUNT_STEPS.clone();
        }
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    /**
     * 装飾枠のマテリアルを設定から読み込む。未設定・不正時は白ガラスを使う。
     */
    private static Material readFillerMaterial(FileConfiguration config) {
        ConfigurationSection filler = config == null ? null : config.getConfigurationSection("filler");
        Material material = filler == null ? null
                : ConfigCache.resolveMaterial(filler.getString("material"));
        return material == null ? DEFAULT_FILLER_MATERIAL : material;
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

    private void loadButtons(FileConfiguration config) {
        ConfigurationSection items = config == null ? null : config.getConfigurationSection("buttons");
        if (items == null && config != null) {
            items = config.getConfigurationSection("items");
        }
        if (items == null) {
            return;
        }
        for (String key : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            int slot = section.getInt("slot", -1);
            Material material = ConfigCache.resolveMaterial(section.getString("material"));
            if (slot < 0 || slot >= inventory.getSize() || material == null) {
                continue;
            }
            List<String> lore = section.getStringList("lore");
            buttons.put(key, new Button(slot, material, Math.max(1, section.getInt("amount", 1)),
                    section.getString("name", ""), lore));
        }
    }

    /**
     * 売却枠を設定から読み込む。装飾枠・ボタンと重なるスロットは除外する。
     */
    private List<Integer> readSellSlots(FileConfiguration config, int size) {
        List<Integer> candidates = readSlots(config, "sell_slots", size, DEFAULT_SELL_SLOTS);
        Set<Integer> buttonSlots = buttonSlots();
        List<Integer> result = new ArrayList<>();
        for (int slot : candidates) {
            if (!fillerSlots.contains(slot) && !buttonSlots.contains(slot)) {
                result.add(slot);
            }
        }
        if (result.isEmpty()) {
            for (int slot : DEFAULT_SELL_SLOTS) {
                if (slot < size && !fillerSlots.contains(slot) && !buttonSlots.contains(slot)) {
                    result.add(slot);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * ボタンとして使用するスロット番号を返す。
     */
    private Set<Integer> buttonSlots() {
        Set<Integer> slots = new LinkedHashSet<>();
        for (Button button : buttons.values()) {
            slots.add(button.slot());
        }
        return slots;
    }

    /**
     * 売却確定・数量切替・所持金・閉じるボタンを再描画する。
     */
    public void renderButtons() {
        long units = availableUnits();
        long price = calculatePrice();
        long balance = currentBalance();

        for (Button button : buttons.values()) {
            inventory.setItem(button.slot(), button.toItemStack(Map.of(
                    "price", format(price),
                    "money", format(balance),
                    "balance", format(balance),
                    "quantity", format(currentStep()),
                    "amount", format(currentStep()),
                    "count", format(units))));
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
                case "sell", "sell_confirm" -> confirmSell();
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
        if (isSellSlot(rawSlot)) {
            cancelReservation(rawSlot);
            return true;
        }
        if (fillerSlots.contains(rawSlot) || isBottomRow(rawSlot)) {
            deny();
            return true;
        }
        return rawSlot >= 0 && rawSlot < inventory.getSize();
    }

    /**
     * ボタン固有の効果音を返す。未設定時は {@code sounds.click} にフォールバックする。
     */
    private GuiSound buttonSound(String id) {
        if (settings == null) {
            return clickSound;
        }
        return settings.button(id)
                .filter(button -> button.sound().isPresent())
                .map(ShopGuiSettings.ButtonConfig::sound)
                .orElse(clickSound);
    }

    /**
     * 指定スロットに割り当てられたボタン ID を返す。未定義の場合は {@code null}。
     */
    private String buttonId(int slot) {
        for (Map.Entry<String, Button> entry : buttons.entrySet()) {
            if (entry.getValue().slot() == slot) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 仮想ストレージのアイテムを売却予約へ追加する。
     * <p>
     * 数量切替で選択中の個数だけ予約する。仮想ストレージの所持数が選択数より
     * 少ない場合は所持数分だけ予約し、既に予約済みの数を差し引いた残数が対象となる。
     * この時点では仮想ストレージの実体を減らさない。
     *
     * @param clicked クリックされた仮想ストレージの表示アイテム
     */
    public void reserveFromInventory(ItemStack clicked) {
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        String key = VirtualInventoryService.bagEntryKey(clicked);
        VirtualInventoryTab tab = VirtualInventoryService.bagTabOf(clicked);
        if (key == null || tab == null) {
            return;
        }

        Integer existingSlot = findReservationSlot(tab, key);
        if (existingSlot == null && reservations.size() >= sellSlots.size()) {
            deny();
            message("reservation_full", Map.of());
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            deny();
            message("not_ready", Map.of());
            return;
        }
        VirtualItemEntry entry = data.getVirtualBagEntry(tab, key);
        if (entry == null || entry.amount() <= 0L) {
            deny();
            message("already_reserved", Map.of());
            return;
        }

        Optional<ItemData> definition = itemService == null
                ? Optional.empty() : itemService.findItem(clicked);
        if (definition.isEmpty() || definition.get().sellPrice() <= 0L) {
            deny();
            message("unsellable", Map.of("item", displayName(clicked)));
            return;
        }

        long available = entry.amount() - reservedAmount(tab, key);
        if (available <= 0L) {
            deny();
            message("already_reserved", Map.of());
            return;
        }

        long amount = Math.min(currentStep(), available);
        if (existingSlot != null) {
            Reservation previous = reservations.get(existingSlot);
            reservations.put(existingSlot, new Reservation(tab, key, entry.itemId(),
                    safeAdd(previous.amount(), amount), previous.display()));
        } else {
            int slot = firstEmptySellSlot();
            if (slot < 0) {
                deny();
                message("reservation_full", Map.of());
                return;
            }
            reservations.put(slot, new Reservation(tab, key, entry.itemId(), amount,
                    clicked.clone()));
        }
        play(slotReserveSound);
        renderSaleSlots();
        renderButtons();
    }

    /**
     * 売却枠の予約をキャンセルする。仮想ストレージの実体は変更しない。
     *
     * @param slot 売却枠のスロット番号
     */
    public void cancelReservation(int slot) {
        play(slotClickSound);
        if (reservations.remove(slot) == null) {
            return;
        }
        renderSaleSlots();
        renderButtons();
    }

    /**
     * 数量設定を次の候補へ進める。
     */
    private void cycleAmount() {
        int index = 0;
        for (int i = 0; i < amountSteps.length; i++) {
            if (amountSteps[i] == currentStep()) {
                index = i;
                break;
            }
        }
        amountStep = amountSteps[(index + 1) % amountSteps.length];
        play(buttonSound("quantity"));
        renderButtons();
    }


    /**
     * 予約されたアイテムをすべて売却する。仮想ストレージからの減算はここでのみ行う。
     */
    private void confirmSell() {
        if (reservations.isEmpty()) {
            deny();
            message("nothing_to_sell", Map.of());
            return;
        }

        VirtualInventoryService virtualInventory = plugin.getVirtualInventoryService();
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (virtualInventory == null || data == null) {
            deny();
            message("not_ready", Map.of());
            return;
        }

        // 予約時点で売却可否を確認しているが、定義変更などに備えて再検証する。
        for (Reservation reservation : reservations.values()) {
            Optional<ItemData> definition = itemService == null
                    ? Optional.empty() : itemService.findItem(reservation.display());
            if (definition.isEmpty() || definition.get().sellPrice() <= 0L) {
                deny();
                message("unsellable", Map.of("item", displayName(reservation.display())));
                return;
            }
        }

        long total = 0L;
        for (Reservation reservation : reservations.values()) {
            VirtualItemEntry entry = data.getVirtualBagEntry(reservation.tab(), reservation.key());
            if (entry == null || entry.amount() <= 0L) {
                continue;
            }
            long amount = Math.min(reservation.amount(), entry.amount());
            if (amount <= 0L) {
                continue;
            }
            if (!entry.itemId().equals(reservation.itemId())) {
                continue;
            }
            Optional<ItemData> definition = itemService == null
                    ? Optional.empty() : itemService.findItem(reservation.display());
            if (definition.isEmpty() || definition.get().sellPrice() <= 0L) {
                continue;
            }
            if (virtualInventory.consumeItem(player, reservation.tab(), reservation.key(), amount)) {
                total = safeAdd(total, safeMultiply(definition.get().sellPrice(), amount));
            }
        }

        reservations.clear();
        renderSaleSlots();
        renderButtons();

        if (total <= 0L) {
            deny();
            message("nothing_to_sell", Map.of());
            return;
        }

        long next = safeAdd(data.getMoney(), total);
        data.setMoney(next);

        play(buttonSound("sell"));
        message("sold", Map.of("price", format(total), "balance", format(next)));

        var playerService = plugin.getPlayerService();
        if (playerService != null) {
            playerService.save(player.getUniqueId(), player.getName());
        }
    }

    /**
     * GUI を閉じたときの後処理。売却予約を破棄するだけで、仮想ストレージは変更しない。
     */
    public void handleClose() {
        if (!closeSoundPlayed) {
            play(closeSound);
        }
        reservations.clear();
        for (int slot : sellSlots) {
            inventory.setItem(slot, null);
        }
    }

    /**
     * この GUI を開いているプレイヤーの UUID。
     */
    public UUID ownerId() {
        return player.getUniqueId();
    }

    /**
     * 指定スロットが売却枠かどうか。
     */
    private boolean isSellSlot(int slot) {
        return sellSlots.contains(slot);
    }


    /* ------------------------------------------------------------------ */
    /* 内部処理                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 装飾枠を白ガラスで埋める。ボタン・売却枠と重なるスロットは描画しない。
     */
    private void renderFiller() {
        if (fillerMaterial != null) {
            Set<Integer> buttonSlots = buttonSlots();
            for (int slot : fillerSlots) {
                if (!buttonSlots.contains(slot) && !sellSlots.contains(slot)) {
                    inventory.setItem(slot, new ItemStack(fillerMaterial));
                }
            }
        }
        renderBottomFiller();
    }

    /**
     * 一番下の列（45〜53）のうち、ボタンが定義されていないスロットを白ガラスで埋める。
     */
    private void renderBottomFiller() {
        int start = Math.max(0, inventory.getSize() - 9);
        Set<Integer> buttonSlots = buttonSlots();
        for (int slot = start; slot < inventory.getSize(); slot++) {
            if (buttonSlots.contains(slot) || sellSlots.contains(slot)) {
                continue;
            }
            inventory.setItem(slot, new ItemStack(Material.WHITE_STAINED_GLASS_PANE));
        }
    }

    /**
     * 一番下の列（45〜53）かどうか。
     */
    private boolean isBottomRow(int slot) {
        int start = Math.max(0, inventory.getSize() - 9);
        return slot >= start && slot < inventory.getSize();
    }

    /**
     * 予約を売却枠へ仮想表示する。売却枠以外のボタンには触れない。
     */
    private void renderSaleSlots() {
        for (int slot : sellSlots) {
            Reservation reservation = reservations.get(slot);
            inventory.setItem(slot, reservation == null
                    ? null : createReservationDisplay(reservation));
        }
    }

    /**
     * 予約 1 件分の仮想表示アイテムを生成する。
     */
    private ItemStack createReservationDisplay(Reservation reservation) {
        ItemData definition = itemService == null
                ? null : itemService.findItem(reservation.display()).orElse(null);
        ItemStack stack = definition == null
                ? reservation.display().clone()
                : itemService.createItem(definition);
        stack.setAmount(1);

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.lore() == null
                    ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(TextComponents.mini(MiniMessage.miniMessage(),
                    "<gray>予約数: <white>" + format(reservation.amount())
                            + "</white></gray>"));
            lore.add(TextComponents.mini(MiniMessage.miniMessage(),
                    "<dark_gray>クリックで予約を解除</dark_gray>"));
            meta.lore(TextComponents.noItalic(lore));
            stack.setItemMeta(meta);
        }
        return VirtualItem.mark(stack);
    }

    /**
     * 同一タブ・同一枠の予約スロットを返す。存在しない場合は {@code null}。
     */
    private Integer findReservationSlot(VirtualInventoryTab tab, String key) {
        for (Map.Entry<Integer, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (reservation.tab() == tab && reservation.key().equals(key)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 空いている売却枠の先頭スロットを返す。空きがない場合は {@code -1}。
     */
    private int firstEmptySellSlot() {
        for (int slot : sellSlots) {
            if (!reservations.containsKey(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 同一タブ・同一枠に対して既に予約されている個数を返す。
     */
    private long reservedAmount(VirtualInventoryTab tab, String key) {
        long total = 0L;
        for (Reservation reservation : reservations.values()) {
            if (reservation.tab() == tab && reservation.key().equals(key)) {
                total = safeAdd(total, reservation.amount());
            }
        }
        return total;
    }

    /** 売却予約に含まれるアイテムの合計個数。 */
    private long availableUnits() {
        long count = 0L;
        for (Reservation reservation : reservations.values()) {
            count = safeAdd(count, reservation.amount());
        }
        return count;
    }

    /**
     * 予約されたすべてのアイテムを売却した場合の売却額を返す。
     */
    private long calculatePrice() {
        long total = 0L;
        for (Reservation reservation : reservations.values()) {
            Optional<ItemData> definition = itemService == null
                    ? Optional.empty() : itemService.findItem(reservation.display());
            if (definition.isEmpty() || definition.get().sellPrice() <= 0L) {
                continue;
            }
            total = safeAdd(total, safeMultiply(definition.get().sellPrice(), reservation.amount()));
        }
        return total;
    }

    private long currentBalance() {
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        return data == null ? 0L : data.getMoney();
    }

    private long currentStep() {
        return amountStep <= 0L ? amountSteps[0] : amountStep;
    }


    private void click() {
        play(clickSound);
    }

    private void deny() {
        play(denySound);
    }

    private void play(GuiSound sound) {
        if (sound == null || sound.sound() == null) {
            return;
        }
        player.playSound(player.getLocation(), sound.sound(), sound.volume(), sound.pitch());
    }

    private void message(String key, Map<String, String> placeholders) {
        var messageService = plugin.getMessageService();
        if (messageService != null) {
            messageService.send(player, "shop.yml", "shop_buy." + key, placeholders);
        }
    }

    private static String displayName(ItemStack stack) {
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                var name = meta.displayName();
                if (name != null) {
                    return MiniMessage.miniMessage().serialize(name);
                }
            }
        }
        return stack.getType().name();
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        return parent == null ? null : parent.getConfigurationSection(key);
    }

    private static String format(long value) {
        return NUMBER_FORMAT.format(Math.max(0L, value));
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 売却予約 1 件分。仮想ストレージの実体は保持せず、タブ・枠キー・数量だけを記録する。
     */
    private record Reservation(VirtualInventoryTab tab, String key, String itemId,
                               long amount, ItemStack display) {
    }

    /**
     * 設定から生成するボタン定義。
     */
    private record Button(int slot, Material material, int amount,
                          String name, List<String> lore) {

        private ItemStack toItemStack(Map<String, String> placeholders) {
            ItemStack stack = new ItemStack(material, amount);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.displayName(TextComponents.mini(
                        MiniMessage.miniMessage(), replace(name, placeholders)));
                if (!lore.isEmpty()) {
                    List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
                    for (String line : lore) {
                        lines.add(TextComponents.mini(
                                MiniMessage.miniMessage(), replace(line, placeholders)));
                    }
                    meta.lore(TextComponents.noItalic(lines));
                }
                stack.setItemMeta(meta);
            }
            return stack;
        }

        private static String replace(String template, Map<String, String> placeholders) {
            String result = template == null ? "" : template;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }
    }
}

