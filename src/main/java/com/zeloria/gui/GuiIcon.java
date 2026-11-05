package com.zeloria.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI のボタンアイコン設定。
 * <p>
 * {@code config/gui/menu.yml} の {@code icons} セクションと 1 対 1 で対応し、
 * Material 名・MiniMessage 形式の表示名・説明文（lore）を保持する。
 *
 * @param materialName Material 名（例: {@code BARRIER}）
 * @param displayName  MiniMessage 形式の表示名
 * @param lore         MiniMessage 形式の説明文
 */
public record GuiIcon(String materialName, String displayName, List<String> lore) {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public GuiIcon {
        materialName = materialName == null || materialName.isBlank() ? "STONE" : materialName.trim();
        displayName = displayName == null ? "" : displayName;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    /**
     * 設定セクションからアイコンを生成する。
     *
     * @param section 設定セクション
     * @return アイコン設定。セクションが {@code null} の場合は {@code null}
     */
    public static GuiIcon from(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        return new GuiIcon(
                section.getString("material"),
                section.getString("name"),
                section.getStringList("lore")
        );
    }

    /**
     * Material 名を解決する（大文字小文字は区別しない）。
     * 未知の名前の場合は {@link Material#STONE} を返す。
     */
    public Material material() {
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        return material == null ? Material.STONE : material;
    }

    /**
     * この設定から GUI 用の {@link ItemStack} を生成する。
     *
     * @param placeholders 表示名・説明文へ埋め込むプレースホルダ（{@code {key}}）
     * @return 表示名・説明文を適用したアイテム
     */
    public ItemStack createItem(Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        if (!displayName.isBlank()) {
            meta.displayName(MINI.deserialize(apply(displayName, placeholders)));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(line -> MINI.deserialize(apply(line, placeholders)))
                    .toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static String apply(String text, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
