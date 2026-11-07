package com.zeloria.gui;

import com.zeloria.player.EquipSlot;
import com.zeloria.player.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code /menu} で開く独自メニュー GUI。
 * <p>
 * この画面の責務は次の 2 点だけに限定する。
 * <ul>
 *     <li>仮想装備の着脱（装備枠の表示・クリックで外す）</li>
 *     <li>今後追加される各種メニュー機能（{@link MenuFeature}）の呼び出しボタン</li>
 * </ul>
 * <p>
 * {@link InventoryHolder} を実装しており、{@code InventoryClickEvent} から
 * 独自メニューかどうかを判別できる。描画はすべてメインスレッドで行う。
 */
public final class MenuGui implements InventoryHolder {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 機能アイコンのフォールバック Material。 */
    private static final Material FEATURE_FALLBACK_MATERIAL = Material.PAPER;

    private final UUID playerId;
    private final MenuLayout layout;

    /** GUI スロット番号 → 仮想装備スロット。 */
    private final Map<Integer, EquipSlot> equipmentSlots;

    /** GUI スロット番号 → メニュー機能。 */
    private final Map<Integer, MenuFeature> featureSlots;

    private final Inventory inventory;

    /**
     * メニュー GUI を生成する。
     *
     * @param player   対象プレイヤー
     * @param layout   {@code gui/menu.yml} のレイアウト設定（装備枠・装飾枠の位置を参照）
     * @param settings {@code gui/inventory.yml} の設定（menu.yml に装備枠定義が無い場合の後方互換）
     */
    public MenuGui(Player player, MenuLayout layout, InventorySettings settings) {
        this.playerId = Objects.requireNonNull(player, "player").getUniqueId();
        this.layout = Objects.requireNonNull(layout, "layout");
        this.equipmentSlots = buildEquipmentSlots(layout, settings);
        this.featureSlots = buildFeatureSlots(layout);
        this.inventory = Bukkit.createInventory(this, layout.totalSlots(),
                MINI.deserialize(layout.title()));
    }

    /* ------------------------------------------------------------------
     * スロット判定
     * ------------------------------------------------------------------ */

    /**
     * 指定 GUI スロットが仮想装備枠か判定する。
     */
    public boolean isEquipmentSlot(int guiSlot) {
        return equipmentSlots.containsKey(guiSlot);
    }

    /**
     * 指定 GUI スロットに対応する仮想装備スロットを取得する。
     *
     * @return 装備スロット。装備枠でない場合は {@code null}
     */
    public EquipSlot equipSlotAt(int guiSlot) {
        return equipmentSlots.get(guiSlot);
    }

    /**
     * 指定 GUI スロットがメニュー機能ボタンか判定する。
     */
    public boolean isFeatureSlot(int guiSlot) {
        return featureSlots.containsKey(guiSlot);
    }

    /**
     * 指定 GUI スロットが装飾専用アイテム（区切り線）か判定する。
     */
    public boolean isDecorationSlot(int guiSlot) {
        return layout.isDecorationSlot(guiSlot);
    }

    /**
     * 指定 GUI スロットのメニュー機能を取得する。
     *
     * @return 機能定義。ボタンでない場合は {@code null}
     */
    public MenuFeature featureAt(int guiSlot) {
        return featureSlots.get(guiSlot);
    }

    /* ------------------------------------------------------------------
     * 描画
     * ------------------------------------------------------------------ */

    /**
     * プレイヤーデータの内容を GUI へ反映する。
     * <p>
     * 前回の表示が残らないようインベントリを空にしてから、装飾アイテム・仮想装備枠・
     * メニュー機能ボタン・閉じるボタンのみを描画する。装飾と装備枠以外のスロットは
     * 空気のままにする。
     *
     * @param player 描画対象プレイヤー（機能ボタンの権限判定に使用）
     * @param data   プレイヤーデータ
     */
    public void render(Player player, PlayerData data) {
        Objects.requireNonNull(data, "data");

        inventory.clear();

        renderDecorations();
        renderEquipment(data);
        renderFeatures(player);
        renderCloseButton();
    }

    /**
     * 装飾専用アイテム（区切り線）を描画する。
     * <p>
     * 表示専用で、クリックしても何も起きない（{@link MenuService} 側で無視する）。
     */
    private void renderDecorations() {
        for (Map.Entry<Integer, String> entry : layout.decorations().entrySet()) {
            int guiSlot = entry.getKey();
            if (!isValidSlot(guiSlot)) {
                continue;
            }
            Material material = matchMaterial(entry.getValue());
            inventory.setItem(guiSlot, new ItemStack(
                    material == null ? Material.LIGHT : material));
        }
    }

    /**
     * 仮想装備枠を描画する。
     */
    private void renderEquipment(PlayerData data) {
        for (Map.Entry<Integer, EquipSlot> entry : equipmentSlots.entrySet()) {
            int guiSlot = entry.getKey();
            if (!isValidSlot(guiSlot)) {
                continue;
            }

            EquipSlot equipSlot = entry.getValue();
            ItemStack equipped = data.getEquipment(equipSlot);
            if (equipped == null) {
                // 空き装備枠はバリアで示し、装備すると表示されることを案内する。
                inventory.setItem(guiSlot, createEmptySlotItem());
            } else {
                inventory.setItem(guiSlot, decorateEquipped(equipped, equipSlot));
            }
        }
    }

    /**
     * メニュー機能ボタンを描画する。
     * <p>
     * 権限を持たない機能は表示しない。ここでは表示のみを担当し、
     * クリック時の実処理は {@link MenuService#handleFeatureClick} が担う。
     */
    private void renderFeatures(Player player) {
        for (Map.Entry<Integer, MenuFeature> entry : featureSlots.entrySet()) {
            int guiSlot = entry.getKey();
            MenuFeature feature = entry.getValue();
            if (!isValidSlot(guiSlot) || !feature.isAvailableTo(player)) {
                continue;
            }

            GuiIcon icon = layout.icon(feature.icon());
            ItemStack item = icon == null
                    ? simpleItem(FEATURE_FALLBACK_MATERIAL,
                    "<!italic><white>" + feature.id() + "</white>")
                    : icon.createItem(Map.of("id", feature.id(), "action", feature.action()));
            inventory.setItem(guiSlot, item);
        }
    }

    /**
     * 閉じるボタンを描画する。
     */
    private void renderCloseButton() {
        setIfValid(layout.closeSlot(), icon("close", Map.of(),
                Material.BARRIER, "<!italic><red>閉じる</red>"));
    }

    /* ------------------------------------------------------------------
     * アイテム生成ヘルパ
     * ------------------------------------------------------------------ */

    /**
     * 空の装備枠に表示するダミーアイテムを生成する。
     */
    private ItemStack createEmptySlotItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MINI.deserialize("<!italic><gray>空きスロット</gray>"));
            meta.lore(List.of(MINI.deserialize(
                    "<!italic><dark_gray>装備するとここに表示されます</dark_gray>")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 装備中のアイテムに外し方のヒントを付与した表示用コピーを返す。
     */
    private ItemStack decorateEquipped(ItemStack item, EquipSlot slot) {
        ItemStack display = item.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return display;
        }

        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(MINI.deserialize("<!italic><dark_gray>" + slot.getDisplayName() + "</dark_gray>"));
        lore.add(MINI.deserialize("<!italic><gray>クリックで外す</gray>"));
        lore.add(MINI.deserialize("<!italic><dark_gray>カーソル装備でクリックすると入れ替え</dark_gray>"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    /**
     * 設定されたアイコンを生成する。未定義の場合は代替アイテムを返す。
     */
    private ItemStack icon(String key, Map<String, String> placeholders,
                           Material fallbackMaterial, String fallbackName) {
        GuiIcon icon = layout.icon(key);
        if (icon != null) {
            return icon.createItem(placeholders);
        }
        return simpleItem(fallbackMaterial, fallbackName);
    }

    /**
     * Material 名を解決する。不正な名前・アイテム以外の場合は {@code null} を返す。
     */
    private static Material matchMaterial(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(name.trim());
        return material == null || !material.isItem() ? null : material;
    }

    private ItemStack simpleItem(Material material, String displayName) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MINI.deserialize(displayName));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void setIfValid(int guiSlot, ItemStack item) {
        if (isValidSlot(guiSlot)) {
            inventory.setItem(guiSlot, item);
        }
    }

    private boolean isValidSlot(int guiSlot) {
        return guiSlot >= 0 && guiSlot < inventory.getSize();
    }

    /* ------------------------------------------------------------------
     * マップ構築
     * ------------------------------------------------------------------ */

    private static Map<Integer, EquipSlot> buildEquipmentSlots(
            MenuLayout layout, InventorySettings settings) {
        Map<Integer, EquipSlot> result = new TreeMap<>();

        // menu.yml の equipment_slots を優先する。
        for (EquipSlot slot : EquipSlot.values()) {
            int guiSlot = layout.equipmentSlot(slot);
            if (guiSlot >= 0) {
                result.putIfAbsent(guiSlot, slot);
            }
        }
        if (!result.isEmpty()) {
            return Collections.unmodifiableMap(result);
        }

        // 後方互換: menu.yml に装備枠定義が無い場合は inventory.yml（既定値）を使う。
        if (settings != null) {
            for (EquipSlot slot : EquipSlot.values()) {
                int guiSlot = settings.equipmentSlot(slot);
                if (guiSlot >= 0) {
                    result.putIfAbsent(guiSlot, slot);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<Integer, MenuFeature> buildFeatureSlots(MenuLayout layout) {
        Map<Integer, MenuFeature> result = new TreeMap<>();
        for (MenuFeature feature : layout.features()) {
            if (feature.slot() >= 0) {
                result.putIfAbsent(feature.slot(), feature);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /* ------------------------------------------------------------------
     * アクセサ
     * ------------------------------------------------------------------ */

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * この GUI を開いているプレイヤーの UUID を取得する。
     */
    public UUID playerId() {
        return playerId;
    }

    /**
     * レイアウト設定を取得する。
     */
    public MenuLayout layout() {
        return layout;
    }
}
