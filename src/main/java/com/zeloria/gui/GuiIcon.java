package com.zeloria.gui;

import com.zeloria.core.ConfigCache;
import com.zeloria.core.TextComponents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/**
 * GUI のボタンアイコン設定。
 * <p>
 * {@code config/GuiData/menu.yml} の {@code icons} セクションと 1 対 1 で対応し、
 * 解決済み Material（任意の {@code custom_model_data} 付き）・MiniMessage 形式の表示名・
 * 説明文（lore）を保持する。
 *
 * @param material    解決済みのマテリアル設定
 * @param displayName MiniMessage 形式の表示名
 * @param lore        MiniMessage 形式の説明文
 */
public record GuiIcon(GuiMaterial material, String displayName, List<String> lore) {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public GuiIcon {
        material = material == null ? GuiMaterial.STONE : material;
        displayName = displayName == null ? "" : displayName;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    /**
     * 設定セクションからアイコンを生成する（ログ・キャッシュなし）。
     *
     * @param section 設定セクション
     * @return アイコン設定。セクションが {@code null} の場合は {@code null}
     */
    public static GuiIcon from(ConfigurationSection section) {
        return from(section, null);
    }

    /**
     * 設定セクションからアイコンを生成する。
     * <p>
     * {@code cache} が指定された場合は解決済み Material を再利用し、不正値の警告も行う。
     *
     * @param section 設定セクション
     * @param cache   設定キャッシュ（{@code null} の場合は都度解決）
     * @return アイコン設定。セクションが {@code null} の場合は {@code null}
     */
    public static GuiIcon from(ConfigurationSection section, ConfigCache cache) {
        return from(section, cache, "");
    }

    /**
     * 設定セクションからアイコンを生成する。
     * <p>
     * キャッシュキーの衝突を避けるため、{@code sourceKey} には設定ファイルの
     * 相対パスを渡すこと。
     *
     * @param section   設定セクション
     * @param cache     設定キャッシュ（{@code null} の場合は都度解決）
     * @param sourceKey 設定ファイルの識別子
     * @return アイコン設定。セクションが {@code null} の場合は {@code null}
     */
    public static GuiIcon from(
            ConfigurationSection section, ConfigCache cache, String sourceKey) {
        if (section == null) {
            return null;
        }

        Material fallback = GuiMaterial.STONE.material();
        Material resolved = cache == null
                ? ConfigCache.resolveMaterial(section.getString("material"))
                : cache.material(sourceKey, section, "material", fallback);
        int customModelData = section.getInt("custom_model_data", 0);
        return new GuiIcon(
                new GuiMaterial(resolved == null ? fallback : resolved, customModelData),
                section.getString("name"),
                section.getStringList("lore")
        );
    }

    /**
     * 解決済み Material を返す。
     */
    public Material materialType() {
        return material.material();
    }

    /**
     * この設定から GUI 用の {@link ItemStack} を生成する。
     *
     * @param placeholders 表示名・説明文へ埋め込むプレースホルダ（{@code {key}}）
     * @return 表示名・説明文を適用したアイテム
     */
    public ItemStack createItem(Map<String, String> placeholders) {
        ItemStack stack = material.createItem();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        if (!displayName.isBlank()) {
            meta.displayName(TextComponents.mini(MINI, apply(displayName, placeholders)));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(line -> TextComponents.mini(MINI, apply(line, placeholders)))
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
