package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * すべてのアイテム設定 YAML が継承する共通フォーマット。
 * <p>
 * 各アイテム設定は次の基本構造を持ち、{@code name} と {@code lore} は
 * MiniMessage（{@code <yellow>} などのカラーコード）として解釈される。
 * <pre>
 * id: "test"
 * name: "&lt;yellow&gt;test"
 * material: WOODEN_HOE
 * lore:
 *   - "&lt;gray&gt;何もない。"
 *   - "&lt;gray&gt;テスト用。"
 * sell_price: 75
 * </pre>
 * 種別ごとの追加項目は {@link MaterialData} / {@link ArmorData} / {@link WeaponData} が保持する。
 * <p>
 * {@code item_model} は Paper の item_model コンポーネントに使用する任意項目で、
 * 未指定の場合はコンポーネントを付与せず {@code material} 本来のバニラ表示を使用する
 * （{@link ItemService} を参照）。CustomModelData は使用しない。
 */
public abstract class ItemData {

    private static final String YAML_EXTENSION = ".yml";
    private static final String YAML_ALT_EXTENSION = ".yaml";

    private final String id;
    private final String name;
    private final Material material;
    private final List<String> lore;
    private final long sellPrice;
    private final String itemModel;
    private final String sourceKey;

    protected ItemData(String id, String name, Material material,
                       List<String> lore, long sellPrice, String sourceKey) {
        this(id, name, material, lore, sellPrice, sourceKey, null);
    }

    protected ItemData(String id, String name, Material material,
                       List<String> lore, long sellPrice, String sourceKey, String itemModel) {
        this.sourceKey = sourceKey == null ? "" : sourceKey;
        String fallback = fallbackId(this.sourceKey);
        this.id = text(id, fallback);
        this.name = text(name, this.id);
        this.material = Objects.requireNonNull(material, "material");
        this.lore = lore == null ? List.of() : List.copyOf(lore);
        this.sellPrice = Math.max(0L, sellPrice);
        this.itemModel = itemModel == null || itemModel.isBlank() ? null : itemModel.trim();
    }

    /**
     * アイテム ID（コマンド引数・識別子）。
     */
    public String id() {
        return id;
    }

    /**
     * 表示名（MiniMessage 形式）。
     */
    public String name() {
        return name;
    }

    /**
     * Bukkit の Material。
     */
    public Material material() {
        return material;
    }

    /**
     * 説明文（MiniMessage 形式）。
     */
    public List<String> lore() {
        return lore;
    }

    /**
     * 売却価格。
     */
    public long sellPrice() {
        return sellPrice;
    }

    /**
     * YAML で明示指定された item_model（例: {@code minecraft:diamond_sword}）。
     * <p>
     * 未指定の場合は空を返す。呼び出し側（{@link ItemService}）はこの値が
     * 無い場合に item_model コンポーネントを付与せず、{@code material} の
     * バニラテクスチャをそのまま使用する。
     */
    public Optional<String> itemModel() {
        return Optional.ofNullable(itemModel);
    }

    /**
     * 読み込み元 YAML のキャッシュキー（例: {@code MaterialData/iron_ore.yml}）。
     */
    public String sourceKey() {
        return sourceKey;
    }

    /**
     * このアイテムの種別を取得する。
     */
    public abstract ItemCategory category();

    /**
     * 1 スロットに格納できる最大数。
     * <p>
     * 素材は YAML の {@code max_stack_size}、防具・武器は 1 を返す。
     */
    public abstract int maxStackSize();

    /* ------------------------------------------------------------------
     * 共通パーサ
     * ------------------------------------------------------------------ */

    /**
     * {@code material} を {@link Material} として解決する。
     *
     * @param config アイテム設定
     * @return 解決された Material
     * @throws IllegalArgumentException 未指定・不正な Material の場合
     */
    protected static Material readMaterial(FileConfiguration config) {
        String raw = config.getString("material");
        Material material = raw == null || raw.isBlank()
                ? null
                : Material.matchMaterial(raw.trim());
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("material が不正です: " + raw);
        }
        return material;
    }

    /**
     * {@code lore} を文字列リストとして読み込む。
     */
    protected static List<String> readLore(FileConfiguration config) {
        return config.getStringList("lore");
    }

    /**
     * {@code config/<folder>/<ファイル名>.yml} から拡張子なしファイル名を取り出す。
     */
    protected static String fallbackId(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return "";
        }
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(YAML_ALT_EXTENSION)) {
            return fileName.substring(0, fileName.length() - YAML_ALT_EXTENSION.length());
        }
        if (lower.endsWith(YAML_EXTENSION)) {
            return fileName.substring(0, fileName.length() - YAML_EXTENSION.length());
        }
        return fileName;
    }

    /**
     * 空文字・{@code null} の場合にフォールバックを返す。
     */
    protected static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
