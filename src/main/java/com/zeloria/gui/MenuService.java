package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import com.zeloria.item.ArmorData;
import com.zeloria.item.EquipmentService;
import com.zeloria.item.ItemCategory;
import com.zeloria.item.ItemData;
import com.zeloria.item.ItemService;
import com.zeloria.item.VirtualInventoryService;
import com.zeloria.item.VirtualItem;
import com.zeloria.item.WeaponData;
import com.zeloria.player.PlayerSettings;
import com.zeloria.core.MessageService;
import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 独自メニュー GUI（{@code /menu}）の開閉・操作を担うサービス。
 * <p>
 * 本クラスの動的処理は次の 2 つに限定する。
 * <ul>
 *     <li><b>装備の着脱</b>: 装備枠クリックで外す／カーソルで持った装備を装備枠へ置く／
 *         プレイヤーインベントリの装備品をクリックで装着する</li>
 *     <li><b>メニュー機能呼び出しの基盤</b>: {@link MenuFeature} のクリックを
 *         {@link #registerFeatureAction} で登録されたハンドラへ振り分ける</li>
 * </ul>
 * <p>
 * すべての操作はメインスレッドで実行され、変更は即座に {@link PlayerData} へ反映したうえで
 * 再描画する。DB への書き込みは退出時（{@link PlayerService}）にまとめて行われる。
 */
public class MenuService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 効果音キー（{@code GuiData/menu.yml} / {@code GuiData/inventory.yml} の {@code sounds}）。 */
    private static final String SOUND_OPEN = "open";
    private static final String SOUND_CLICK = "click";
    private static final String SOUND_EQUIP = "equip";
    private static final String SOUND_ERROR = "error";

    /**
     * 装備枠クリックの連打防止時間（ミリ秒）。
     * <p>
     * Geyser / Bedrock のタップは 1 回の操作で複数のクリックイベントを送ることがあるため、
     * 約 2 ティック分の間隔を空けて二重着脱を防ぐ。
     */
    private static final long EQUIPMENT_CLICK_COOLDOWN_MILLIS = 100L;

    private final Zeloria plugin;

    /**
     * メニュー機能アクション名 → 実行ハンドラ。
     * <p>
     * 今後追加される各種メニュー機能は、ここへハンドラを登録するだけで
     * {@code GuiData/menu.yml} の {@code features} から呼び出せる。
     */
    private final Map<String, Consumer<Player>> featureActions = new ConcurrentHashMap<>();

    /** 装備枠クリックの最終受付時刻（プレイヤー UUID → epoch millis）。 */
    private final Map<UUID, Long> equipmentClickAt = new ConcurrentHashMap<>();

    public MenuService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /* ------------------------------------------------------------------
     * メニュー機能の基盤
     * ------------------------------------------------------------------ */

    /**
     * メニュー機能アクションを登録する。
     * <p>
     * {@code GuiData/menu.yml} の {@code features.<id>.action} と同じ名前で登録すると、
     * 対応するボタンのクリック時にハンドラが呼ばれる。
     *
     * @param action  アクション名
     * @param handler 実行ハンドラ（メインスレッド）
     */
    public void registerFeatureAction(String action, Consumer<Player> handler) {
        if (action == null || action.isBlank() || handler == null) {
            return;
        }
        featureActions.put(action.trim().toLowerCase(java.util.Locale.ROOT), handler);
    }

    /**
     * メニュー機能アクションの登録を解除する。
     */
    public void unregisterFeatureAction(String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        featureActions.remove(action.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * メニュー機能ボタンのクリックを処理する。
     * <p>
     * 権限判定を行ったうえで、登録済みハンドラへ委譲する。
     *
     * @param player  実行者
     * @param feature クリックされた機能
     * @return ハンドラが実行された場合は {@code true}
     */
    public boolean handleFeatureClick(Player player, MenuFeature feature) {
        if (player == null || feature == null) {
            return false;
        }
        if (!feature.isAvailableTo(player)) {
            playSound(player, SOUND_ERROR);
            notify(player, "<red>この機能を利用する権限がありません。</red>");
            return false;
        }

        Consumer<Player> handler = featureActions.get(feature.action().toLowerCase(java.util.Locale.ROOT));
        if (handler == null) {
            plugin.getLogger().warning("メニュー機能のアクションハンドラが未登録です: "
                    + feature.id() + " (action=" + feature.action() + ")");
            playSound(player, SOUND_ERROR);
            notify(player, "<red>この機能は現在利用できません。</red>");
            return false;
        }

        handler.accept(player);
        return true;
    }

    /* ------------------------------------------------------------------
     * 開く
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーのメニュー GUI を開く。
     * <p>
     * データが未キャッシュの場合は非同期読み込みを開始し、完了後にメインスレッドで開く。
     *
     * @param player 対象プレイヤー
     */
    public void open(Player player) {
        Objects.requireNonNull(player, "player");

        PlayerData cached = PlayerData.getCached(player.getUniqueId());
        if (cached != null) {
            openNow(player, cached);
            return;
        }

        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            notify(player, "<red>プレイヤーデータが読み込まれていません。</red>");
            return;
        }

        playerService.loadIfAbsent(player)
                .thenAccept(data -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        openNow(player, data);
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "メニュー用のプレイヤーデータ読み込みに失敗しました: "
                                    + player.getName() + " (" + rootMessage(throwable) + ")");
                    Bukkit.getScheduler().runTask(plugin, () ->
                            notify(player, "<red>プレイヤーデータの読み込みに失敗しました。</red>"));
                    return null;
                });
    }

    /**
     * 指定名の GUI を開く（NPC の {@code interactGui} などから呼び出される汎用入り口）。
     * <p>
     * {@code gui.yml} の {@code id} が {@code menu} の場合は既存のメニュー GUI を、
     * {@code class_select} の場合はクラス選択 GUI を開く。それ以外は
     * {@code rows} / {@code items} を持つ汎用 YAML として {@link SimpleGui} を生成する。
     *
     * @param player  対象プレイヤー
     * @param guiName GUI の YAML 名（例: {@code guide_menu}）
     * @return 開けた場合は {@code true}
     */
    public boolean openGui(Player player, String guiName) {
        if (player == null || guiName == null || guiName.isBlank()) {
            return false;
        }

        String key = guiName.trim();
        String sourceKey = key.startsWith("GuiData/") ? key : "GuiData/" + key;
        FileConfiguration config = null;
        YamlLoader yamlLoader = plugin.getYamlLoader();
        if (yamlLoader != null) {
            config = yamlLoader.getConfig(sourceKey);
            if (config == null) {
                sourceKey = key;
                config = yamlLoader.getConfig(sourceKey);
            }
        }

        if (config == null) {
            plugin.getLogger().warning("GUI 設定が見つかりません: " + guiName);
            notify(player, "<red>GUI 設定が見つかりません: <white>" + guiName + "</white></red>");
            return false;
        }

        String id = config.getString("id");
        String resolvedId = id == null || id.isBlank() ? key : id.trim();
        String normalized = resolvedId.toLowerCase(Locale.ROOT);

        if ("menu".equals(normalized)) {
            open(player);
            return true;
        }

        if ("class_select".equals(normalized)) {
            ClassSelectGUI classSelectGui = plugin.getClassSelectGUI();
            if (classSelectGui == null) {
                return false;
            }
            classSelectGui.open(player);
            return true;
        }

        SimpleGui gui;
        try {
            gui = SimpleGui.from(config, resolvedId, plugin.getConfigCache(), sourceKey);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "GUI の生成に失敗しました: " + guiName + " (" + e.getMessage() + ")");
            return false;
        }

        player.openInventory(gui.getInventory());
        playSound(player, SOUND_OPEN);
        return true;
    }

    /**
     * キャッシュ済みデータを用いてメニュー GUI を生成・表示する（メインスレッド専用）。
     */
    private void openNow(Player player, PlayerData data) {
        GuiConfigService gui = gui();
        if (gui == null) {
            plugin.getLogger().warning("GuiConfigService が未初期化のためメニューを開けません。");
            return;
        }

        MenuGui menu = new MenuGui(player, gui.getMenu(), gui.getInventory(),
                plugin.getLevelService(), plugin.getGatheringService(), plugin.getClassManager());
        equipmentClickAt.remove(player.getUniqueId());
        menu.render(player, data);
        player.openInventory(menu.getInventory());
        playSound(player, SOUND_OPEN);
    }

    /**
     * プレイヤーがメニュー GUI を開いているか判定する。
     *
     * @param player 対象プレイヤー
     * @return メニュー GUI を開いている場合は {@code true}
     */
    public boolean isMenuOpen(Player player) {
        if (player == null) {
            return false;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof MenuGui;
    }

    /**
     * プレイヤーがメニュー GUI を開いていれば、現在の {@link PlayerData} で再描画する。
     * <p>
     * メニューを開いていない場合は何もしない。装備変更などの再計算から呼ばれるため、
     * 開いていないプレイヤーへの不要な描画処理を避ける。
     *
     * @param player 対象プレイヤー
     * @param data   再描画に使用するプレイヤーデータ
     */
    public void refresh(Player player, PlayerData data) {
        if (player == null || data == null || !isMenuOpen(player)) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof MenuGui menu) {
            menu.render(player, data);
        }
    }

    /* ------------------------------------------------------------------
     * クリック処理
     * ------------------------------------------------------------------ */

    /**
     * メニュー GUI の {@link InventoryClickEvent} を処理する。
     * <p>
     * 上部 GUI のクリックはすべてキャンセルし、装備枠・メニュー機能・閉じるボタン
     * 以外の持ち出しを防止する。プレイヤーインベントリ側では装備品の右クリック装着のみ
     * 横取りし、それ以外はバニラ操作に委ねる。
     * <p>
     * ただしダブルクリック（{@link InventoryAction#COLLECT_TO_CURSOR}）は、クリック元が
     * プレイヤーインベントリであっても同じビュー内の上部 GUI（表示用アイテム）から
     * 同種アイテムを一括回収してしまうため、インベントリを問わず常に拒否する。
     *
     * @param event クリックイベント
     * @param menu  対象のメニュー GUI
     */
    public void handleClick(InventoryClickEvent event, MenuGui menu) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            GuiClickSupport.deny(event);
            return;
        }

        // 一括回収（ダブルクリック）は表示用アイテムの持ち出し・複製に直結するため、
        // データ有無やクリック元の判定より先に無条件でキャンセルする。
        if (isCollectToCursor(event)) {
            GuiClickSupport.deny(event);
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        Inventory clicked = event.getClickedInventory();
        if (data == null || clicked == null) {
            GuiClickSupport.deny(event);
            return;
        }

        if (clicked.getHolder() instanceof MenuGui) {
            GuiClickSupport.deny(event);
            handleTopClick(event, player, data, menu);
        } else {
            handleBottomClick(event, player, data, menu);
        }
    }

    /**
     * 上部メニュー GUI 内のクリックを処理する。
     */
    private void handleTopClick(InventoryClickEvent event, Player player,
                                PlayerData data, MenuGui menu) {
        int slot = event.getSlot();
        MenuLayout layout = menu.layout();

        if (slot == layout.closeSlot()) {
            playSound(player, SOUND_CLICK);
            player.closeInventory();
            return;
        }

        // 装飾専用アイテム（区切り線）。クリックしても何も起こさない。
        if (menu.isDecorationSlot(slot)) {
            return;
        }

        if (menu.isEquipmentSlot(slot)) {
            handleEquipmentSlotClick(event, player, data, menu, menu.equipSlotAt(slot));
            return;
        }

        if (menu.isFeatureSlot(slot)) {
            MenuFeature feature = menu.featureAt(slot);
            if (toggleChatLogSetting(player, data, menu, feature)) {
                return;
            }
            playSound(player, SOUND_CLICK);
            handleFeatureClick(player, feature);
            return;
        }

        // 空き枠（持ち出しは行わない）。
        playSound(player, SOUND_CLICK);
    }

    /**
     * レベルアップ通知設定のトグルクリックを処理する。
     *
     * @return 設定トグルのクリックとして処理した場合は {@code true}
     */
    private boolean toggleChatLogSetting(Player player, PlayerData data,
                                         MenuGui menu, MenuFeature feature) {
        if (feature == null || !feature.isAvailableTo(player)) {
            return false;
        }
        String action = feature.action().toLowerCase(java.util.Locale.ROOT);
        boolean self = MenuGui.ACTION_CHAT_LOG_SELF.equals(action);
        boolean others = MenuGui.ACTION_CHAT_LOG_OTHERS.equals(action);
        if (!self && !others) {
            return false;
        }

        PlayerSettings settings = data.getSettings();
        PlayerSettings updated = self
                ? settings.withChatLogSelf(!settings.chatLogSelf())
                : settings.withChatLogOthers(!settings.chatLogOthers());
        data.setSettings(updated);

        com.zeloria.player.PlayerService playerService = plugin.getPlayerService();
        if (playerService != null) {
            playerService.save(player.getUniqueId(), player.getName());
        }

        boolean enabled = self ? updated.chatLogSelf() : updated.chatLogOthers();
        String target = self ? "自分のレベルアップログ" : "他人のレベルアップログ";
        playSound(player, SOUND_CLICK);
        notify(player, (enabled ? "<green>" : "<red>") + target
                + "を " + (enabled ? "ON" : "OFF") + " にしました。</"
                + (enabled ? "green" : "red") + ">");
        menu.render(player, data);
        return true;
    }

    /**
     * 装備枠クリックを処理する。
     * <p>
     * カーソルに装備品がある場合は装着（既存装備とは入れ替え）、空の場合は取り外す。
     * Bedrock のタップ連打による二重着脱を防ぐため、短時間の連続クリックは無視する。
     */
    private void handleEquipmentSlotClick(InventoryClickEvent event, Player player,
                                          PlayerData data, MenuGui menu, EquipSlot equipSlot) {
        if (equipSlot == null) {
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            if (!acceptsSlot(equipSlot, cursor)) {
                playSound(player, SOUND_ERROR);
                notify(player, "<red>この枠には " + equipSlot.getDisplayName() + " しか装着できません。</red>");
                return;
            }
            if (!equipSlot.isToolSlot()) {
                EquipmentService equipmentService = plugin.getEquipmentService();
                if (equipmentService != null && !equipmentService.canEquip(player, cursor)) {
                    playSound(player, SOUND_ERROR);
                    notify(player, "<red>現在のクラスではこの武器を装着できません。</red>");
                    return;
                }
            }
            if (!beginEquipmentClick(player)) {
                return;
            }
            equipFromCursor(player, data, menu, equipSlot, cursor);
            return;
        }

        if (!beginEquipmentClick(player)) {
            return;
        }
        unequip(player, data, menu, equipSlot);
    }

    /**
     * 装備枠クリックの連打防止判定を行う。
     *
     * @return 受け付けた場合は {@code true}
     */
    private boolean beginEquipmentClick(Player player) {
        long now = System.currentTimeMillis();
        Long previous = equipmentClickAt.put(player.getUniqueId(), now);
        return previous == null || now - previous >= EQUIPMENT_CLICK_COOLDOWN_MILLIS;
    }

    /**
     * プレイヤーインベントリ（下部）のクリックを処理する。
     * <p>
     * 装備可能アイテムのクリックを「装着」として横取りする。Java の右クリックだけでなく
     * Geyser / Bedrock のタップ（左クリック）でも装着できるようにする。
     */
    private void handleBottomClick(InventoryClickEvent event, Player player,
                                   PlayerData data, MenuGui menu) {
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }

        // 仮想ストレージの表示アイテムは VirtualInventoryListener が
        // 装備処理まで担当するため、ここでは二重処理しない。
        if (VirtualInventoryService.isBagSlot(event.getSlot())
                || VirtualItem.isVirtual(item)) {
            return;
        }

        // カーソルに別のアイテムを持っている間は、バニラの入れ替え操作を優先する。
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            return;
        }

        EquipSlot equipSlot = resolveEquipSlot(data, item);
        if (equipSlot == null) {
            return;
        }

        GuiClickSupport.deny(event);
        if (!beginEquipmentClick(player)) {
            return;
        }
        equipFromPlayerInventory(player, data, menu, event.getSlot(), item, equipSlot);
    }

    /**
     * 装備枠が指定アイテムを受け入れられるか判定する。
     * <p>
     * 道具枠は {@code ItemCategory.TOOL} のプラグイン製ツールのみを許可する。
     * それ以外の枠ではアイテム定義（防具の装着位置・武器種別）を優先し、
     * 定義が無いバニラアイテムは素材から判定する。
     */
    private boolean acceptsSlot(EquipSlot equipSlot, ItemStack item) {
        if (equipSlot == null || item == null || item.getType().isAir()) {
            return false;
        }
        ItemService itemService = plugin.getItemService();
        ItemData definition = itemService == null
                ? null
                : itemService.findItem(item).orElse(null);
        if (equipSlot.isToolSlot()) {
            return definition != null && definition.category() == ItemCategory.TOOL;
        }
        if (definition != null) {
            if (definition.category() == ItemCategory.TOOL) {
                return false;
            }
            if (definition instanceof ArmorData armor) {
                return armor.equipmentSlot() == equipSlot;
            }
            if (definition instanceof WeaponData) {
                return equipSlot == EquipSlot.MAIN_WEAPON;
            }
        }
        return equipSlot.accepts(item);
    }

    /**
     * 物理インベントリのアイテムから装着先スロットを解決する。
     */
    private EquipSlot resolveEquipSlot(PlayerData data, ItemStack item) {
        ItemService itemService = plugin.getItemService();
        ItemData definition = itemService == null
                ? null
                : itemService.findItem(item).orElse(null);
        if (definition == null) {
            return EquipSlot.match(item);
        }
        if (definition.category() == ItemCategory.TOOL) {
            return resolveToolSlot(data, definition);
        }
        if (definition instanceof ArmorData armor) {
            return armor.equipmentSlot();
        }
        if (definition instanceof WeaponData) {
            return EquipSlot.MAIN_WEAPON;
        }
        return EquipSlot.match(item);
    }

    /**
     * 採取ツールを配置する道具枠を解決する。
     */
    private EquipSlot resolveToolSlot(PlayerData data, ItemData definition) {
        EquipSlot[] toolSlots = {EquipSlot.TOOL_1, EquipSlot.TOOL_2};
        ItemService itemService = plugin.getItemService();
        for (EquipSlot slot : toolSlots) {
            ItemStack equipped = data.getEquipment(slot);
            if (equipped == null || itemService == null) {
                continue;
            }
            ItemData equippedDefinition = itemService.findItem(equipped).orElse(null);
            if (equippedDefinition != null && equippedDefinition.id().equals(definition.id())) {
                return slot;
            }
        }
        for (EquipSlot slot : toolSlots) {
            if (data.getEquipment(slot) == null) {
                return slot;
            }
        }
        return EquipSlot.TOOL_1;
    }

    /**
     * ダブルクリックによる一括回収（{@link InventoryAction#COLLECT_TO_CURSOR}）か判定する。
     * <p>
     * サーバー実装やクリック状況によっては {@link InventoryClickEvent#getAction()} が
     * {@link InventoryAction#UNKNOWN} を返すことがあるため、クリック種別が
     * {@link ClickType#DOUBLE_CLICK} の場合も同一視して拒否対象とする。
     *
     * @param event クリックイベント
     * @return 一括回収とみなす場合は {@code true}
     */
    private static boolean isCollectToCursor(InventoryClickEvent event) {
        return event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getClick() == ClickType.DOUBLE_CLICK;
    }

    /* ------------------------------------------------------------------
     * 装備の着脱
     * ------------------------------------------------------------------ */

    /**
     * カーソルで持っている装備品を仮想装備へ装着する。
     * <p>
     * 既に装備がある場合は、元の装備をカーソルへ戻す（入れ替え）。
     */
    private void equipFromCursor(Player player, PlayerData data, MenuGui menu,
                                 EquipSlot equipSlot, ItemStack cursor) {
        ItemStack equipped = cursor.clone();
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (!equipSlot.isToolSlot() && equipmentService != null
                && !equipmentService.canEquip(player, equipped)) {
            playSound(player, SOUND_ERROR);
            notify(player, "<red>現在のクラスではこの武器を装着できません。</red>");
            return;
        }
        if (!equipSlot.isToolSlot() && equipmentService != null
                && !equipmentService.meetsRequiredLevel(player, equipped)) {
            playSound(player, SOUND_ERROR);
            MessageService messages = plugin.getMessageService();
            if (messages != null) {
                messages.actionBar(player, "equipment", "level_required",
                        Map.of("required", String.valueOf(equipmentService.requiredLevel(equipped)),
                                "level", String.valueOf(data.getCurrentClassLevel())));
            }
            return;
        }

        ItemStack previous = data.getEquipment(equipSlot);
        data.setEquipment(equipSlot, equipped);
        player.setItemOnCursor(previous);

        playSound(player, SOUND_EQUIP);
        recompute(player);
        menu.render(player, data);
    }

    /**
     * プレイヤーインベントリのアイテムを仮想装備へ装着する。
     * <p>
     * 既に装備がある場合は、クリックしたスロットへ元の装備を戻す（入れ替え）。
     */
    private void equipFromPlayerInventory(Player player, PlayerData data, MenuGui menu,
                                          int playerSlot, ItemStack item, EquipSlot equipSlot) {
        EquipmentService equipmentService = plugin.getEquipmentService();
        boolean toolSlot = equipSlot.isToolSlot();
        if (!toolSlot && equipmentService != null && !equipmentService.canEquip(player, item)) {
            playSound(player, SOUND_ERROR);
            notify(player, "<red>現在のクラスではこの武器を装着できません。</red>");
            return;
        }
        if (!toolSlot && equipmentService != null
                && !equipmentService.meetsRequiredLevel(player, item)) {
            playSound(player, SOUND_ERROR);
            MessageService messages = plugin.getMessageService();
            if (messages != null) {
                messages.actionBar(player, "equipment", "level_required",
                        Map.of("required", String.valueOf(equipmentService.requiredLevel(item)),
                                "level", String.valueOf(data.getCurrentClassLevel())));
            }
            return;
        }

        ItemStack previous = data.getEquipment(equipSlot);
        data.setEquipment(equipSlot, item);
        player.getInventory().setItem(playerSlot, previous);

        playSound(player, SOUND_EQUIP);
        recompute(player);
        menu.render(player, data);
    }

    /**
     * 仮想装備を外して仮想装備ストレージへ戻す。
     * <p>
     * 物理インベントリには戻さず、必ず {@link VirtualInventoryService#addItem} 経由で
     * 仮想装備ストレージへ格納する。満杯で格納できない場合は装備枠に残す。
     * <p>
     * プラグイン製アイテムではない装備（バニラの防具・プレイヤーの頭など）は
     * 仮想ストレージへ戻せないため、ホットバーの空き枠へ直接返す。
     */
    private void unequip(Player player, PlayerData data, MenuGui menu, EquipSlot equipSlot) {
        if (equipSlot == null) {
            playSound(player, SOUND_CLICK);
            return;
        }

        ItemStack equipped = data.getEquipment(equipSlot);
        if (equipped == null) {
            playSound(player, SOUND_CLICK);
            return;
        }

        VirtualInventoryService virtualInventory = plugin.getVirtualInventoryService();
        ItemService itemService = plugin.getItemService();
        ItemData definition = itemService == null
                ? null
                : itemService.findItem(equipped).orElse(null);
        if (virtualInventory == null) {
            playSound(player, SOUND_ERROR);
            notify(player, "<red>この装備は外せません。運営に報告してください。</red>");
            return;
        }

        if (definition == null) {
            // プラグイン管理外の装備は仮想ストレージへ格納できない。
            if (!returnToPlayerInventory(player, equipped)) {
                playSound(player, SOUND_ERROR);
                notify(player, "<red>ホットバーに空きがありません。装備を外す場所を空けてください。</red>");
                return;
            }
            data.setEquipment(equipSlot, null);
            playSound(player, SOUND_EQUIP);
            recompute(player);
            menu.render(player, data);
            return;
        }

        if (virtualInventory.addItem(player, definition.id(), 1L) <= 0L) {
            playSound(player, SOUND_ERROR);
            notify(player, "<red>装備保管庫がいっぱいです。不要な装備を捨てるか、保管庫へ移動してから外してください。</red>");
            return;
        }

        data.setEquipment(equipSlot, null);

        playSound(player, SOUND_EQUIP);
        recompute(player);
        menu.render(player, data);
    }

    /**
     * プラグイン管理外の装備をホットバーの空き枠へ戻す。
     * <p>
     * 武器表示枠（0）・パネル枠（6〜8）は操作対象外のため、1〜5 のみを対象とする。
     *
     * @return 戻せた場合は {@code true}
     */
    private static boolean returnToPlayerInventory(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) {
            return false;
        }

        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 1; slot <= 5; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(slot, item.clone());
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------
     * ヘルパ
     * ------------------------------------------------------------------ */

    /**
     * 装備変更後にステータス補正を再計算する。
     */
    private void recompute(Player player) {
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (equipmentService != null) {
            equipmentService.recompute(player);
        }
    }

    private GuiConfigService gui() {
        return plugin.getGuiConfigService();
    }

    private void playSound(Player player, String name) {
        GuiConfigService gui = gui();
        if (gui != null) {
            gui.playSound(player, name);
        }
    }

    private static void notify(Player player, String miniMessage) {
        if (player.isOnline()) {
            player.sendActionBar(MINI.deserialize(miniMessage));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
