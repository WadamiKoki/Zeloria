package com.zeloria.item;

import com.zeloria.core.Zeloria;
import com.zeloria.gui.GuiClickSupport;
import com.zeloria.gui.MenuGui;
import com.zeloria.item.VirtualItem;
import com.zeloria.core.MessageService;
import com.zeloria.player.PlayerData;
import com.zeloria.player.VirtualInventoryTab;
import com.zeloria.world.BuildManager;
import com.zeloria.world.BuildModeService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.Objects;

/**
 * 仮想装備表示・仮想インベントリのロックと、アイテム入手経路を担うリスナー。
 * <ul>
 *     <li>ホットバー slot 0・右端 3 枠・バニラ防具 4 枠・戦闘モード中のスキルスロットを保護する</li>
 *     <li>メインインベントリ（slot 9〜35）のクリック・ドラッグをすべて拒否する</li>
 *     <li>スクロール・タブ切り替え・タブ間移動などの仮想インベントリ操作を処理する</li>
 *     <li>ダブルクリック収集・ホットバーキー交換など、ロック枠を経由する操作を拒否する</li>
 *     <li>仮想アイテムのオフハンド移動・ドロップ・耐久値減少・死亡時ドロップを拒否する</li>
 *     <li>ログイン時に仮想インベントリを復元し、地面のアイテム拾得を仮想インベントリへ直接追加する</li>
 * </ul>
 * 表示内容の生成は {@link VirtualInventoryService} に委譲する。
 */
public class VirtualInventoryListener implements Listener {

    /** メッセージ定義ファイル（{@code MessageData/equipment.yml}）。 */
    private static final String MESSAGE_FILE = "equipment";

    private final Zeloria plugin;

    public VirtualInventoryListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * ロック枠へのクリック・ロック枠を経由する移動を拒否する。
     * <p>
     * {@link EventPriority#LOWEST} で処理し、装備 GUI 側のクリック処理より先に
     * ロックを確定させる。仮想表示アイテム自体は {@link com.zeloria.player.EquipSlot#match}
     * でも装備対象外となるため、表示用アイテムが誤って装着されることはない。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // ビルドモード中はバニラのインベントリ操作をそのまま許可する。
        if (isBuildMode(player)) {
            return;
        }

        int playerSlot = clickedPlayerSlot(event);

        // ホットバー右端のメニュー本は、自分のインベントリ画面でのみメニューを開く。
        if (playerSlot == VirtualInventoryService.MENU_BUTTON_SLOT
                && isOwnInventoryView(event.getView())
                && VirtualInventoryService.isMenuButton(event.getCurrentItem())) {
            GuiClickSupport.deny(event);
            openMenu(player);
            return;
        }

        // メインインベントリ（9〜35）はタブ式仮想インベントリのため、
        // シフトクリック・ダブルクリック・数字キー・ドラッグを含めてすべて拒否する。
        if (VirtualInventoryService.isBagSlot(playerSlot)) {
            GuiClickSupport.deny(event);
            handleBagClick(event, player);
            return;
        }

        // 仮想アイテムをオフハンドへ移動しようとした場合は拒否する。
        if (playerSlot == VirtualInventoryService.OFFHAND_SLOT
                && hasVirtualItem(event, player)) {
            GuiClickSupport.deny(event);
            notifyOffhandBlocked(player);
            scheduleRefresh(player);
            return;
        }

        if (VirtualInventoryService.isLockedSlot(playerSlot)) {
            GuiClickSupport.deny(event);
            notifyLocked(player, playerSlot);
            return;
        }

        // 仮想アイテムがカーソル・クリック対象・ホットバー交換先・オフハンドに
        // 現れた場合は、複製や持ち出しを防ぐため拒否する。
        if (hasVirtualItem(event, player)) {
            GuiClickSupport.deny(event);
            scheduleRefresh(player);
            return;
        }

        // ダブルクリック収集は、仮想アイテムをカーソルへ回収してしまうため拒否する。
        if ((event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getClick() == ClickType.DOUBLE_CLICK)
                && canCollectVirtual(player, event.getCursor())) {
            GuiClickSupport.deny(event);
            return;
        }

        // 自分のインベントリ表示中に防具をシフトクリックすると、バニラ防具スロットへ
        // 直接装着されてしまうため拒否する（防具は /menu に一本化）。
        if (event.isShiftClick()
                && isOwnInventoryView(event.getView())
                && isArmor(event.getCurrentItem())) {
            GuiClickSupport.deny(event);
            notifyLocked(player, VirtualInventoryService.HELMET_SLOT);
        }
    }

    /**
     * 仮想インベントリ領域（9〜35）のクリックを処理する。
     * <p>
     * 呼び出し元で {@link InventoryClickEvent#setCancelled(boolean)} 済みのため、
     * ここでは表示データの更新だけを行う。武器・防具の通常クリックは装備、
     * シフトクリックはタブ間移動として処理する。
     */
    private void handleBagClick(InventoryClickEvent event, Player player) {
        VirtualInventoryService service = plugin.getVirtualInventoryService();
        if (service == null) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        if (VirtualInventoryService.isScrollButton(clicked)) {
            String direction = VirtualInventoryService.scrollDirection(clicked);
            service.scroll(player, "up".equalsIgnoreCase(direction) ? -1 : 1);
            return;
        }

        if (VirtualInventoryService.isTabSwitch(clicked)) {
            service.switchTab(player);
            return;
        }

        String key = VirtualInventoryService.bagEntryKey(clicked);
        VirtualInventoryTab tab = VirtualInventoryService.bagTabOf(clicked);
        if (key == null || tab == null) {
            return;
        }

        // タブ画面では装備保管庫のアイテムをシフトクリックでタブ間移動する。
        // 通常クリックでは武器・防具をその場で装備する（装備できた場合のみ）。
        if (event.isShiftClick()) {
            service.moveEntry(player, tab, key);
            return;
        }
        // 装備の着脱はメニュー GUI からのみ行う。メニューを開いていない状態で
        // 仮想インベントリのアイテムをクリックしても装備しない。
        if (!isMenuOpen(player)) {
            return;
        }
        if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) {
            service.useEntry(player, tab, key);
        }
    }

    /**
     * クリック後にロック枠の状態を再同期する。
     * <p>
     * シフトクリックやクラフト結果の移動では、空のロック枠（特に武器未装備時の
     * ホットバー slot 0）へ実アイテムが入り得る。どのインベントリ画面からでも
     * 起こり得るため、プレイヤーのクリック全般を対象に次のティックで表示を更新する。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickMonitor(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (isBuildMode(player) || event.getAction() == InventoryAction.NOTHING) {
            return;
        }

        scheduleRefresh(player);
    }

    /**
     * ロック枠を対象とするドラッグを拒否する。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (isBuildMode(player)) {
            return;
        }
        if (VirtualItem.isVirtual(event.getOldCursor())) {
            event.setCancelled(true);
            scheduleRefresh(player);
            return;
        }

        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                continue;
            }
            int playerSlot = view.convertSlot(rawSlot);
            if (VirtualInventoryService.isBagSlot(playerSlot)) {
                event.setCancelled(true);
                return;
            }
            if (VirtualInventoryService.isLockedSlot(playerSlot)) {
                event.setCancelled(true);
                notifyLocked(player, playerSlot);
                return;
            }
            // スキルスロットなど仮想アイテムの上へ実アイテムを重ねる操作も拒否する。
            if (VirtualItem.isVirtual(player.getInventory().getItem(playerSlot))) {
                event.setCancelled(true);
                scheduleRefresh(player);
                return;
            }
        }
    }

    /**
     * 空きスロット表示アイテム（バリア・ガラス）の右クリック使用を拒否する。
     * <p>
     * 表示用アイテムは見た目専用のため、ブロック設置やアイテム使用として
     * 消費・設置されないようにする。戦闘モード切り替え（スニーク + 下向き +
     * 右クリック）は優先度の高いリスナーが別途処理する。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || isBuildMode(event.getPlayer())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (VirtualInventoryService.isMenuButton(event.getItem())) {
            // メニュー本は見た目専用アイテムのため、バニラの使用処理を止めて
            // メニュー GUI の表示だけを行う。
            event.setCancelled(true);
            openMenu(event.getPlayer());
            return;
        }
        if (VirtualItem.isPlaceholder(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * 仮想アイテムのドロップを拒否する。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (VirtualItem.isVirtual(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * 表示用アイテムの耐久値減少を拒否する。
     * <p>
     * 仮想アイテムは DB の装備データから毎回生成する見た目専用のため、
     * 戦闘などで耐久値が減っても保存すべき実体を持たない。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        if (VirtualItem.isVirtual(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * 死亡時に仮想アイテムを除去し、ドロップ一覧からも除外する。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        VirtualInventoryService service = plugin.getVirtualInventoryService();
        if (service == null) {
            return;
        }
        event.getDrops().removeIf(VirtualItem::isVirtual);
        service.clear(event.getEntity());
    }

    /**
     * ログイン時に仮想インベントリを初期タブ（通常）で復元する。
     * <p>
     * プレイヤーデータは {@code AsyncPlayerPreLoginEvent} で読み込み済みのため、
     * 次のティックで表示を更新すれば DB の 2 タブ分が反映される。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            VirtualInventoryService service = plugin.getVirtualInventoryService();
            if (service != null && event.getPlayer().isOnline()) {
                service.onJoin(event.getPlayer());
            }
        });
    }

    /**
     * 地面のカスタムアイテムを拾得しようとした際、物理インベントリではなく
     * 仮想インベントリへ直接追加する。
     * <p>
     * 仮想表示でメインインベントリ・ホットバーが埋まっているとバニラの拾得判定が
     * 走らないため、{@link PlayerAttemptPickupItemEvent}（拾得試行前）で受け取る。
     * 仮想インベントリが満杯で一部しか追加できなかった場合は、
     * 残りを地面に残してアイテム消失を防ぐ。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttemptPickupItem(PlayerAttemptPickupItemEvent event) {
        Player player = event.getPlayer();
        if (isBuildMode(player)) {
            return;
        }

        ItemService itemService = plugin.getItemService();
        VirtualInventoryService service = plugin.getVirtualInventoryService();
        if (itemService == null || service == null
                || PlayerData.getCached(player.getUniqueId()) == null) {
            return;
        }

        ItemStack stack = event.getItem().getItemStack();
        ItemData definition = itemService.findItem(stack).orElse(null);
        if (definition == null) {
            return;
        }

        event.setCancelled(true);
        int amount = Math.max(1, stack.getAmount());
        long added = service.addItem(player, definition.id(), amount);
        if (added >= amount) {
            event.getItem().remove();
            return;
        }

        if (added > 0) {
            ItemStack remaining = stack.clone();
            remaining.setAmount((int) (amount - added));
            event.getItem().setItemStack(remaining);
        } else {
            // 仮想インベントリが満杯の間は、毎 tick の拾得試行を避ける。
            event.getItem().setPickupDelay(20);
        }
    }

    /**
     * リスポーン後に仮想装備表示を再配置する。
     * <p>
     * リスポーン直後はインベントリが初期化されるため、次のティックで反映する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // ビルドモード中は仮想表示を再適用しないため、タスクを登録する前に除外する。
        if (isBuildMode(player)) {
            return;
        }
        scheduleRefresh(player);
    }

    /**
     * ビルドモード中に建築ワールドから退出した場合、自動的にビルドモードを終了する。
     * <p>
     * 建築ワールドへの入場では切り替えない。テレポート確定後にインベントリを操作するため、
     * 次の tick で実行する。ビルドモードでない場合は何もしない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        boolean leavingBuildWorld = BuildManager.isBuildWorld(event.getFrom())
                && !BuildManager.isBuildWorld(player.getWorld());
        if (!leavingBuildWorld) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            BuildModeService buildModeService = plugin.getBuildModeService();
            if (buildModeService != null && buildModeService.isActive(player)) {
                buildModeService.deactivate(player);
            }
        });
    }

    /**
     * 次のティックでメニュー GUI を開く。
     * <p>
     * インベントリ操作イベントの処理中に別のインベントリを開くと、
     * クリック結果の確定と競合するため 1 ティック遅延させる。
     */
    private void openMenu(Player player) {
        if (player == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            var menuService = plugin.getMenuService();
            if (menuService != null) {
                menuService.open(player);
            }
        });
    }

    /**
     * メニュー GUI を開いているか判定する。
     *
     * @param player 対象プレイヤー
     * @return メニュー GUI を開いている場合は {@code true}
     */
    private static boolean isMenuOpen(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof MenuGui;
    }

    /**
     * 次のティックで仮想表示を再同期する。
     * <p>
     * クリック結果やリスポーンによるインベントリ変更が確定した後に反映するため、
     * タスクとして遅延させる。
     */
    private void scheduleRefresh(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            VirtualInventoryService service = plugin.getVirtualInventoryService();
            if (service == null || !player.isOnline() || isBuildMode(player)) {
                return;
            }
            service.refresh(player);
        });
    }

    /** 対象プレイヤーが手動ビルドモード中か判定する。 */
    private boolean isBuildMode(Player player) {
        if (player == null) {
            return false;
        }
        BuildModeService buildModeService = plugin.getBuildModeService();
        return buildModeService != null && buildModeService.isActive(player);
    }

    /**
     * 退出時に仮想アイテムを除去し、バニラのプレイヤーデータへ保存されないようにする。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        VirtualInventoryService service = plugin.getVirtualInventoryService();
        if (service != null) {
            service.clear(event.getPlayer());
        }
        BuildModeService buildModeService = plugin.getBuildModeService();
        if (buildModeService != null) {
            buildModeService.clear(event.getPlayer().getUniqueId());
        }
    }

    /* ------------------------------------------------------------------
     * ヘルパ
     * ------------------------------------------------------------------ */

    /**
     * クリックされたスロットを、プレイヤーインベントリのスロット番号へ変換する。
     * <p>
     * 下部インベントリ（プレイヤーインベントリ）へのクリックのみを対象とし、
     * 上部インベントリ（GUI）へのクリックは {@code -1} を返す。
     *
     * @param event クリックイベント
     * @return プレイヤーインベントリのスロット番号。対象外の場合は {@code -1}
     */
    private static int clickedPlayerSlot(InventoryClickEvent event) {
        InventoryView view = event.getView();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot < view.getTopInventory().getSize()) {
            return -1;
        }
        return view.convertSlot(rawSlot);
    }

    /**
     * クリック操作に仮想アイテムが関与しているか判定する。
     * <p>
     * クリック対象・カーソル・ホットバーキー交換先・オフハンドのいずれかに
     * 仮想アイテムがあれば {@code true}。スキルスロット表示も仮想アイテムとして扱う。
     */
    private static boolean hasVirtualItem(InventoryClickEvent event, Player player) {
        if (VirtualItem.isVirtual(event.getCurrentItem())
                || VirtualItem.isVirtual(event.getCursor())
                || VirtualItem.isVirtual(player.getInventory().getItemInOffHand())) {
            return true;
        }
        int hotbarButton = event.getHotbarButton();
        return hotbarButton >= 0
                && VirtualItem.isVirtual(player.getInventory().getItem(hotbarButton));
    }

    /**
     * プレイヤー自身のインベントリ画面（2x2 クラフト）か判定する。
     */
    private static boolean isOwnInventoryView(InventoryView view) {
        return view.getTopInventory().getType() == InventoryType.CRAFTING
                && view.getTopInventory().getSize() == 5;
    }

    /**
     * カーソル上のアイテムが、ロック枠の仮想表示アイテムを収集してしまうか判定する。
     * <p>
     * ダブルクリック収集は「カーソルと同種のアイテム」だけを回収するため、
     * 種類が異なる場合は拒否する必要がない（通常の収集操作を妨げない）。
     */
    private static boolean canCollectVirtual(Player player, ItemStack cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (matchesVirtual(inventory.getItem(slot), cursor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesVirtual(ItemStack candidate, ItemStack cursor) {
        return VirtualItem.isVirtual(candidate) && candidate.isSimilar(cursor);
    }

    private static boolean isArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String material = stack.getType().name();
        return material.endsWith("_HELMET")
                || material.endsWith("_CHESTPLATE")
                || material.endsWith("_LEGGINGS")
                || material.endsWith("_BOOTS");
    }

    private void notifyLocked(Player player, int playerSlot) {
        if (playerSlot >= VirtualInventoryService.BOOTS_SLOT
                && playerSlot <= VirtualInventoryService.HELMET_SLOT) {
            message(player, "armor_blocked");
            return;
        }
        if (playerSlot == VirtualInventoryService.WEAPON_HOTBAR_SLOT) {
            message(player, "slot_locked");
        }
    }

    private void notifyOffhandBlocked(Player player) {
        message(player, "offhand_blocked");
    }

    private void message(Player player, String key) {
        MessageService messages = plugin.getMessageService();
        if (messages != null) {
            messages.actionBar(player, MESSAGE_FILE, key, Map.of());
        }
    }
}
