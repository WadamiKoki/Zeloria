package com.zeloria.gui;

import com.zeloria.core.TextComponents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GuiData/shop_buy.yml} / {@code GuiData/shop_sell.yml} の共通 GUI 設定。
 * <p>
 * ボタンは YAML のキー（{@code sell} / {@code buy} / {@code quantity} など）で管理し、
 * 各ボタンに再生する効果音を保持する。
 *
 * @param sourceKey     設定ファイルのキャッシュキー
 * @param title         GUI タイトル（MiniMessage）
 * @param rows          行数（1〜6）
 * @param fillerMaterial 装飾枠のマテリアル
 * @param fillerSlots   装飾枠として使用するスロット
 * @param productSlots  商品／売却枠として使用するスロット
 * @param buttons       ボタン ID → ボタン設定
 * @param sounds        効果音キー → 効果音設定
 */
public record ShopGuiSettings(
        String sourceKey,
        String title,
        int rows,
        Material fillerMaterial,
        List<Integer> fillerSlots,
        List<Integer> productSlots,
        Map<String, ButtonConfig> buttons,
        Map<String, GuiSound> sounds) {

    public ShopGuiSettings {
        sourceKey = sourceKey == null ? "" : sourceKey;
        title = title == null ? "" : title;
        rows = Math.max(1, Math.min(6, rows));
        fillerMaterial = fillerMaterial == null
                ? Material.WHITE_STAINED_GLASS_PANE : fillerMaterial;
        fillerSlots = fillerSlots == null ? List.of() : List.copyOf(fillerSlots);
        productSlots = productSlots == null ? List.of() : List.copyOf(productSlots);
        buttons = buttons == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(buttons));
        sounds = sounds == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(sounds));
    }

    /**
     * GUI のインベントリサイズを返す。
     */
    public int size() {
        return rows * 9;
    }

    /**
     * 効果音を取得する。未定義の場合は {@link GuiSound#NONE}。
     */
    public GuiSound sound(String key) {
        if (key == null || key.isBlank()) {
            return GuiSound.NONE;
        }
        return sounds.getOrDefault(key, GuiSound.NONE);
    }

    /**
     * 効果音を取得する。未定義の場合は代替キーを参照する。
     */
    public GuiSound sound(String key, String fallbackKey) {
        GuiSound sound = sound(key);
        return sound.isPresent() ? sound : sound(fallbackKey);
    }

    /**
     * ボタン設定を取得する。
     */
    public Optional<ButtonConfig> button(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(buttons.get(id));
    }

    /**
     * YAML のボタン 1 件分の設定。
     *
     * @param id       ボタン ID
     * @param slot     配置スロット
     * @param material マテリアル
     * @param amount   表示数量
     * @param name     表示名（MiniMessage）
     * @param lore     説明文（MiniMessage）
     * @param soundKey YAML に記述された効果音キー
     * @param sound    解決済みの効果音
     */
    public record ButtonConfig(
            String id,
            int slot,
            Material material,
            int amount,
            String name,
            List<String> lore,
            String soundKey,
            GuiSound sound) {

        public ButtonConfig {
            id = id == null ? "" : id;
            material = material == null ? Material.STONE : material;
            amount = Math.max(1, amount);
            name = name == null ? "" : name;
            lore = lore == null ? List.of() : List.copyOf(lore);
            soundKey = soundKey == null ? "" : soundKey;
            sound = sound == null ? GuiSound.NONE : sound;
        }

        /**
         * プレースホルダーを適用した表示用アイテムを生成する。
         */
        public ItemStack toItemStack(Map<String, String> placeholders) {
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
            if (placeholders == null || placeholders.isEmpty()) {
                return result;
            }
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }
    }
}
