package com.zeloria.gui;

import com.zeloria.core.Zeloria;
import com.zeloria.player.PlayerData;
import com.zeloria.player.PlayerService;
import com.zeloria.player.PlayerStatsService;
import com.zeloria.skill.ClassTreeData;
import com.zeloria.skill.ClassTreeManager;
import com.zeloria.skill.ClassTreeNode;
import com.zeloria.skill.ClassTreeService;
import com.zeloria.skill.ClassTreeService.AllocationResult;
import com.zeloria.skill.ClassTreeService.AllocationStatus;
import com.zeloria.skill.TreeData;
import com.zeloria.skill.TreeDataService;
import com.zeloria.skill.TreeShapeNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クラスツリー GUI の描画・クリック処理を担う。
 * <p>
 * ノードの位置とアイコンは {@code config/TreeData/tree.yml}（{@link TreeData}）、
 * 表示名・説明・コスト・解放条件・効果は
 * {@code config/ClassTree/<classId>.yml}（{@link ClassTreeData}）から取得する。
 * 進行状況は {@link PlayerData} のクラス別データへ即時反映し、DB への保存は
 * 退出時の {@link PlayerService} に任せる。
 * <p>
 * ノード ID はアイテムの {@code PersistentDataContainer}（PDC）へ格納し、
 * displayName / lore をデータ判定に使用しない。スクロール位置はプレイヤー UUID
 * ごとに保持する。
 */
public final class ClassTreeGUI implements Listener {

    /** ノード ID を格納する PDC キー。 */
    private static final String PDC_NODE_ID = "class_tree_node_id";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 固定ボタン列を埋める背景アイテム。 */
    private static final Material FILLER_MATERIAL = Material.GRAY_STAINED_GLASS_PANE;

    /** リセット確認 GUI のサイズ（3 行チェスト）。 */
    private static final int RESET_CONFIRM_SIZE = 27;

    /** リセット確認 GUI の「リセットする」スロット。 */
    private static final int RESET_CONFIRM_ACCEPT_SLOT = 11;

    /** リセット確認 GUI の「キャンセル」スロット。 */
    private static final int RESET_CONFIRM_CANCEL_SLOT = 15;

    private static final GuiSound SOUND_OPEN =
            new GuiSound("block.ender_chest.open", 0.8f, 1.2f);
    private static final GuiSound SOUND_CLICK =
            new GuiSound("ui.button.click", 0.6f, 1.4f);
    private static final GuiSound SOUND_ERROR =
            new GuiSound("entity.villager.no", 0.7f, 1.0f);

    private final Zeloria plugin;
    private final TreeDataService treeDataService;
    private final ClassTreeManager classTreeManager;
    private final ClassTreeService classTreeService;
    private final NamespacedKey nodeIdKey;

    /** プレイヤー UUID → スクロール位置（先頭から非表示にする行数）。 */
    private final Map<UUID, Integer> scrollOffsets = new ConcurrentHashMap<>();

    public ClassTreeGUI(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.treeDataService = Objects.requireNonNull(
                plugin.getTreeDataService(), "treeDataService");
        this.classTreeManager = Objects.requireNonNull(
                plugin.getClassTreeManager(), "classTreeManager");
        this.classTreeService = Objects.requireNonNull(
                plugin.getClassTreeService(), "classTreeService");
        this.nodeIdKey = new NamespacedKey(plugin, PDC_NODE_ID);
    }

    /* ------------------------------------------------------------------
     * 開く
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーにクラスツリー GUI を開く。
     * <p>
     * データが未キャッシュの場合は非同期読み込みを開始し、完了後に
     * メインスレッドで GUI を開く。
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
                            "クラスツリー用のプレイヤーデータ読み込みに失敗しました: "
                                    + player.getName() + " (" + rootMessage(throwable) + ")");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            notify(player, "<red>プレイヤーデータの読み込みに失敗しました。</red>");
                        }
                    });
                    return null;
                });
    }

    /**
     * キャッシュ済みデータを用いて GUI を生成・表示する（メインスレッド専用）。
     */
    private void openNow(Player player, PlayerData data) {
        if (!player.isOnline()) {
            return;
        }

        String classId = data.getClassData().currentClassIdOrNull();
        if (classId == null || classId.isBlank()) {
            SOUND_ERROR.play(player);
            notify(player, "<red>先にクラスを選択してください。</red>");
            return;
        }

        ClassTreeData classTree = classTreeManager.find(classId).orElse(null);
        if (classTree == null) {
            SOUND_ERROR.play(player);
            notify(player, "<red>このクラスのツリーは設定されていません。</red>");
            return;
        }

        TreeData tree = treeDataService.tree();
        data.mirrorCurrentClassProgress();

        int offset = clamp(scrollOffsets.getOrDefault(player.getUniqueId(), 0),
                0, tree.maxScrollOffset());
        scrollOffsets.put(player.getUniqueId(), offset);

        Component title = classTree.title() != null
                ? classTree.title()
                : MINI.deserialize(tree.title());

        Holder holder = new Holder(this);
        Inventory inventory = Bukkit.createInventory(holder, tree.totalSlots(), title);
        holder.inventory = inventory;
        render(inventory, data, classId, classTree, tree, offset);

        player.openInventory(inventory);
        SOUND_OPEN.play(player);
    }


    /* ------------------------------------------------------------------
     * 描画
     * ------------------------------------------------------------------ */

    /**
     * GUI の内容を描画する。
     */
    private void render(Inventory inventory, PlayerData data, String classId,
                        ClassTreeData classTree, TreeData tree, int offset) {
        inventory.clear();

        // 固定UI列（col 7）を灰色ガラスで埋める。スクロールの影響を受けない。
        if (tree.fixedColumn() >= 0 && tree.fixedColumn() <= 8) {
            ItemStack filler = createFiller();
            for (int row = 0; row < tree.rows(); row++) {
                int slot = row * 9 + tree.fixedColumn();
                if (slot >= 0 && slot < inventory.getSize()) {
                    inventory.setItem(slot, filler);
                }
            }
        }

        for (TreeShapeNode shape : tree.nodes().values()) {
            ClassTreeNode node = classTree.node(shape.id());
            if (node == null || !shape.visible(offset, tree.visibleRows())) {
                continue;
            }
            int slot = shape.slot(offset);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            // 固定UI列（col 7）はスクロールの影響を受けない灰色ガラス専用。
            // 計算結果がその列になるノードは描画しない。
            if (tree.fixedColumn() >= 0 && slot % 9 == tree.fixedColumn()) {
                continue;
            }
            inventory.setItem(slot, createNodeItem(data, classId, classTree, node, shape));
        }

        int points = data.getClassTreePoints(classId);
        int level = data.getClassLevel(classId);

        placeButton(inventory, tree.resetSlot(),
                createResetButton(points, level));
        placeButton(inventory, tree.upSlot(),
                createScrollButton(true, offset, tree.maxScrollOffset()));
        placeButton(inventory, tree.downSlot(),
                createScrollButton(false, offset, tree.maxScrollOffset()));
        placeButton(inventory, tree.closeSlot(), createCloseButton());
    }

    private void placeButton(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(FILLER_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.lore(List.of());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * ツリーノードのアイコンを生成する。
     * <p>
     * 状態（解放済み／解放可能／前提未達／ポイント不足）は表示名の先頭記号と
     * lore で示す。アイテム本体は共通形状の Material をそのまま使用する。
     */
    private ItemStack createNodeItem(PlayerData data, String classId,
                                     ClassTreeData classTree,
                                     ClassTreeNode node, TreeShapeNode shape) {
        AllocationStatus status = classTreeService.status(data, classId, node.id());

        ItemStack item = new ItemStack(shape.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        // リソースパックのカスタムモデルを適用する（0 は未設定扱い）。
        if (shape.customModelData() > 0) {
            meta.setCustomModelData(shape.customModelData());
        }

        // 解放済み・解放可能ノードは光沢（エンチャント＋HIDE_ENCHANTS）で強調する。
        if (status == AllocationStatus.ALLOCATED
                || status == AllocationStatus.UNLOCKABLE) {
            Enchantment glow = Registry.ENCHANTMENT.get(
                    NamespacedKey.minecraft("unbreaking"));
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.displayName(Component.empty()
                .append(statusPrefix(status))
                .append(node.displayName()));

        int points = data.getClassTreePoints(classId);
        List<Component> lore = new ArrayList<>();
        lore.addAll(node.description());

        if (!node.effectLore().isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(MINI.deserialize("<gray>効果</gray>"));
            lore.addAll(node.effectLore());
        }

        lore.add(Component.empty());
        lore.add(MINI.deserialize(
                "<gray>必要ポイント: <yellow>" + node.cost() + "</yellow></gray>"));
        lore.add(MINI.deserialize(
                "<gray>残りポイント: <yellow>" + points + "</yellow></gray>"));
        lore.add(Component.empty());
        appendStatusLore(lore, data, classId, classTree, node, status);

        meta.lore(lore);
        meta.getPersistentDataContainer().set(
                nodeIdKey, PersistentDataType.STRING, node.id());
        item.setItemMeta(meta);
        return item;
    }


    /**
     * 状態に応じた lore 行を追加する。
     */
    private void appendStatusLore(List<Component> lore, PlayerData data, String classId,
                                  ClassTreeData classTree, ClassTreeNode node,
                                  AllocationStatus status) {
        switch (status) {
            case ALLOCATED -> lore.add(MINI.deserialize("<green>✔ 解放済み</green>"));
            case UNLOCKABLE -> lore.add(MINI.deserialize(
                    "<yellow>▶ クリックで解放</yellow>"));
            case INSUFFICIENT_POINTS -> lore.add(MINI.deserialize(
                    "<red>✖ ツリーポイントが足りません</red>"));
            case PREREQUISITE -> {
                lore.add(MINI.deserialize("<red>✖ 前提条件を満たしていません</red>"));

                List<String> missing = classTreeService.missingRequirements(
                        data, classId, node);
                if (!missing.isEmpty()) {
                    lore.add(Component.empty()
                            .append(MINI.deserialize("<gray>必要: </gray>"))
                            .append(joinNodeNames(missing, classTree)));
                }

                List<String> missingAny = classTreeService.missingAnyRequirements(
                        data, classId, node);
                if (!missingAny.isEmpty()) {
                    lore.add(Component.empty()
                            .append(MINI.deserialize("<gray>いずれか: </gray>"))
                            .append(joinNodeNames(missingAny, classTree)));
                }
            }
            case UNKNOWN -> lore.add(MINI.deserialize("<dark_red>不明なノード</dark_red>"));
            case NO_CLASS -> lore.add(MINI.deserialize(
                    "<red>クラスが選択されていません</red>"));
        }
    }

    private static Component joinNodeNames(List<String> ids, ClassTreeData classTree) {
        Component result = Component.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                result = result.append(MINI.deserialize("<gray>, </gray>"));
            }
            ClassTreeNode node = classTree.node(ids.get(i));
            result = result.append(node == null
                    ? Component.text(ids.get(i), NamedTextColor.WHITE)
                    : node.displayName());
        }
        return result;
    }

    private static Component statusPrefix(AllocationStatus status) {
        return switch (status) {
            case ALLOCATED -> MINI.deserialize("<green>✔ </green>");
            case UNLOCKABLE -> MINI.deserialize("<yellow>▶ </yellow>");
            default -> MINI.deserialize("<dark_gray>✖ </dark_gray>");
        };
    }

    private ItemStack createResetButton(int points, int level) {
        return createButton(Material.BARRIER, "<red><bold>ツリーをリセット</bold></red>",
                List.of(
                        "<gray>クリックで確認画面を開き、解放済みノードをすべて戻して</gray>",
                        "<gray>消費したツリーポイントを返還します。</gray>",
                        "",
                        "<yellow>残りツリーポイント: " + points + "</yellow>",
                        "<dark_gray>クラスレベル: " + level + "</dark_gray>"));
    }

    private ItemStack createScrollButton(boolean up, int offset, int maxOffset) {
        String label = up ? "上へスクロール" : "下へスクロール";
        String materialName = up ? "ARROW" : "SPECTRAL_ARROW";
        Material material = Material.matchMaterial(materialName);

        boolean enabled = up ? offset > 0 : offset < maxOffset;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>表示位置: <yellow>" + (offset + 1)
                + "</yellow> / <yellow>" + (maxOffset + 1) + "</yellow></gray>");
        lore.add(enabled
                ? "<aqua>クリックでスクロールします。</aqua>"
                : "<dark_gray>これ以上スクロールできません。</dark_gray>");

        return createButton(material == null ? Material.PAPER : material,
                (enabled ? "<aqua>" : "<dark_gray>") + label
                        + (enabled ? "</aqua>" : "</dark_gray>"),
                lore);
    }

    private ItemStack createCloseButton() {
        return createButton(Material.COMPASS, "<red>閉じる</red>",
                List.of("<gray>クリックでメニューを閉じます。</gray>"));
    }

    private ItemStack createButton(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(MINI.deserialize(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(line == null || line.isBlank()
                    ? Component.empty()
                    : MINI.deserialize(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }



    /* ------------------------------------------------------------------
     * イベント処理
     * ------------------------------------------------------------------ */

    /**
     * クラスツリー GUI のクリックを処理する。
     * <p>
     * 上部 GUI のクリックはすべてキャンセルし、スロットと PDC のノード ID だけで
     * 操作を判定する。プレイヤーインベントリ側は GUI への移動を伴う
     * シフトクリックのみ拒否する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        Holder holder = resolveHolder(event.getView().getTopInventory());
        if (holder == null || holder.gui != this) {
            return;
        }

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (holder.mode == HolderMode.RESET_CONFIRM) {
            handleResetConfirm(player, event.getRawSlot());
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            SOUND_ERROR.play(player);
            notify(player, "<red>プレイヤーデータが読み込まれていません。</red>");
            return;
        }

        TreeData tree = treeDataService.tree();
        String classId = data.getClassData().currentClassIdOrNull();
        ClassTreeData classTree = classId == null
                ? null
                : classTreeManager.find(classId).orElse(null);
        if (classId == null || classTree == null) {
            SOUND_ERROR.play(player);
            notify(player, "<red>クラスツリーを表示できません。</red>");
            return;
        }

        int slot = event.getRawSlot();
        int offset = clamp(scrollOffsets.getOrDefault(player.getUniqueId(), 0),
                0, tree.maxScrollOffset());

        if (slot == tree.resetSlot()) {
            handleReset(player, data, classId);
            return;
        }
        if (slot == tree.closeSlot()) {
            SOUND_CLICK.play(player);
            player.closeInventory();
            return;
        }
        if (slot == tree.upSlot()) {
            handleScroll(player, top, data, classId, classTree, tree, offset, -1);
            return;
        }
        if (slot == tree.downSlot()) {
            handleScroll(player, top, data, classId, classTree, tree, offset, 1);
            return;
        }

        String nodeId = readNodeId(event.getCurrentItem());
        if (nodeId == null) {
            return;
        }
        handleNodeClick(player, top, data, classId, classTree, tree, offset, nodeId);
    }

    /**
     * リセット確認 GUI を開く。
     */
    private void handleReset(Player player, PlayerData data, String classId) {
        SOUND_CLICK.play(player);

        int allocated = data.getAllocatedClassNodes(classId).size();
        Holder holder = new Holder(this, HolderMode.RESET_CONFIRM);
        Inventory inventory = Bukkit.createInventory(
                holder,
                RESET_CONFIRM_SIZE,
                MINI.deserialize("<dark_red><bold>ツリーのリセット確認</bold></dark_red>"));
        holder.inventory = inventory;

        ItemStack filler = createFiller();
        for (int slot = 0; slot < RESET_CONFIRM_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(RESET_CONFIRM_ACCEPT_SLOT, createButton(
                Material.RED_CONCRETE,
                "<red><bold>リセットする</bold></red>",
                List.of(
                        "<gray>解放済みノード: <yellow>" + allocated + "</yellow></gray>",
                        "<gray>消費したツリーポイントをすべて返還します。</gray>",
                        "<dark_red>この操作は取り消せません。</dark_red>")));
        inventory.setItem(RESET_CONFIRM_CANCEL_SLOT, createButton(
                Material.LIME_CONCRETE,
                "<green><bold>キャンセル</bold></green>",
                List.of("<gray>ツリー画面に戻ります。</gray>")));

        player.openInventory(inventory);
    }

    /**
     * リセット確認 GUI のクリックを処理する。
     */
    private void handleResetConfirm(Player player, int slot) {
        if (slot == RESET_CONFIRM_CANCEL_SLOT) {
            SOUND_CLICK.play(player);
            PlayerData data = PlayerData.getCached(player.getUniqueId());
            player.closeInventory();
            reopenTree(player, data);
            return;
        }
        if (slot != RESET_CONFIRM_ACCEPT_SLOT) {
            return;
        }

        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (data == null) {
            SOUND_ERROR.play(player);
            notify(player, "<red>プレイヤーデータが読み込まれていません。</red>");
            player.closeInventory();
            return;
        }

        String classId = data.getClassData().currentClassIdOrNull();
        if (classId == null || classId.isBlank()) {
            SOUND_ERROR.play(player);
            notify(player, "<red>クラスが選択されていません。</red>");
            player.closeInventory();
            return;
        }

        if (data.getAllocatedClassNodes(classId).isEmpty()) {
            SOUND_ERROR.play(player);
            notify(player, "<gray>リセットするノードがありません。</gray>");
            player.closeInventory();
            reopenTree(player, data);
            return;
        }

        int refund = classTreeService.reset(data, classId);
        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.apply(player);
        }

        SOUND_CLICK.play(player);
        notify(player, "<green>ツリーをリセットしました。</green> <gray>返還: <yellow>"
                + refund + "</yellow></gray>");
        player.closeInventory();
        reopenTree(player, data);
    }

    /**
     * ツリー GUI を次のティックで再描画する。
     */
    private void reopenTree(Player player, PlayerData data) {
        if (data == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                openNow(player, data);
            }
        });
    }

    private void handleScroll(Player player, Inventory top, PlayerData data,
                              String classId, ClassTreeData classTree, TreeData tree,
                              int offset, int delta) {
        int next = clamp(offset + delta, 0, tree.maxScrollOffset());
        if (next == offset) {
            SOUND_ERROR.play(player);
            notify(player, "<dark_gray>これ以上スクロールできません。</dark_gray>");
            return;
        }

        scrollOffsets.put(player.getUniqueId(), next);
        SOUND_CLICK.play(player);
        render(top, data, classId, classTree, tree, next);
    }
    private void handleNodeClick(Player player, Inventory top, PlayerData data,
                                 String classId, ClassTreeData classTree, TreeData tree,
                                 int offset, String nodeId) {
        ClassTreeNode node = classTree.node(nodeId);
        if (node == null) {
            SOUND_ERROR.play(player);
            notify(player, "<red>不明なノードです。</red>");
            return;
        }

        AllocationStatus status = classTreeService.status(data, classId, node.id());
        switch (status) {
            case ALLOCATED -> {
                SOUND_CLICK.play(player);
                notify(player, "<gray>すでに解放済みです。</gray>");
            }
            case INSUFFICIENT_POINTS -> {
                SOUND_ERROR.play(player);
                notify(player, "<red>ツリーポイントが足りません。</red>");
            }
            case PREREQUISITE -> {
                SOUND_ERROR.play(player);
                notify(player, "<red>前提条件を満たしていません。</red>");
            }
            case UNLOCKABLE -> {
                AllocationResult result = classTreeService.allocate(
                        data, classId, node.id());
                if (result == AllocationResult.SUCCESS) {
                    SOUND_CLICK.play(player);
                    PlayerStatsService statsService = plugin.getPlayerStatsService();
                    if (statsService != null) {
                        statsService.apply(player);
                    }
                    player.sendActionBar(Component.empty()
                            .append(MINI.deserialize("<green>解放しました: </green>"))
                            .append(node.displayName()));
                    render(top, data, classId, classTree, tree, offset);
                } else {
                    SOUND_ERROR.play(player);
                    notify(player, resultMessage(result));
                }
            }
            case UNKNOWN, NO_CLASS -> {
                SOUND_ERROR.play(player);
                notify(player, "<red>このノードは解放できません。</red>");
            }
        }
    }

    /**
     * クラスツリー GUI へのドラッグを拒否する。
     * <p>
     * プレイヤーインベントリ内で完結するドラッグは通常どおり許可する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        Holder holder = resolveHolder(event.getView().getTopInventory());
        if (holder == null || holder.gui != this) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * GUI を閉じたときにスクロール位置を破棄する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Holder holder = resolveHolder(event.getView().getTopInventory());
        if (holder == null || holder.gui != this
                || holder.mode != HolderMode.TREE) {
            return;
        }
        scrollOffsets.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------
     * 補助
     * ------------------------------------------------------------------ */

    private String readNodeId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String stored = meta.getPersistentDataContainer().get(
                nodeIdKey, PersistentDataType.STRING);
        return stored == null || stored.isBlank()
                ? null
                : stored.trim().toLowerCase(Locale.ROOT);
    }

    private static String resultMessage(AllocationResult result) {
        return switch (result) {
            case SUCCESS -> "<green>ノードを解放しました。</green>";
            case ALREADY_ALLOCATED -> "<gray>すでに解放済みです。</gray>";
            case PREREQUISITE_NOT_MET -> "<red>前提条件を満たしていません。</red>";
            case NOT_ENOUGH_POINTS -> "<red>ツリーポイントが足りません。</red>";
            case UNKNOWN -> "<red>不明なノードです。</red>";
            case NO_CLASS -> "<red>クラスが選択されていません。</red>";
        };
    }

    private static void notify(Player player, String message) {
        player.sendActionBar(MINI.deserialize(message));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Holder resolveHolder(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder
                ? holder
                : null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    /**
     * この GUI のインベントリを識別するホルダー。
     */
    private enum HolderMode {
        /** クラスツリー本体。 */
        TREE,
        /** リセット確認画面。 */
        RESET_CONFIRM
    }

    private static final class Holder implements InventoryHolder {

        private final ClassTreeGUI gui;
        private final HolderMode mode;
        private Inventory inventory;

        private Holder(ClassTreeGUI gui) {
            this(gui, HolderMode.TREE);
        }

        private Holder(ClassTreeGUI gui, HolderMode mode) {
            this.gui = gui;
            this.mode = mode;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}


