package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
 * 見た目は {@code material} のバニラテクスチャをそのまま使用する。
 * リソースパック用のカスタムモデルは使用しない。
 */
public abstract class ItemData {

    private static final String YAML_EXTENSION = ".yml";
    private static final String YAML_ALT_EXTENSION = ".yaml";

    private final String id;
    private final String name;
    private final Material material;
    private final List<String> lore;
    private final long sellPrice;
    private final String sourceKey;

    protected ItemData(String id, String name, Material material,
                       List<String> lore, long sellPrice, String sourceKey) {
        this.sourceKey = sourceKey == null ? "" : sourceKey;
        String fallback = fallbackId(this.sourceKey);
        this.id = text(id, fallback);
        this.name = text(name, this.id);
        this.material = Objects.requireNonNull(material, "material");
        this.lore = lore == null ? List.of() : List.copyOf(lore);
        this.sellPrice = Math.max(0L, sellPrice);
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
     * 読み込み元 YAML のキャッシュキー（例: {@code MaterialData/iron_ore.yml}）。
     */
    public String sourceKey() {
        return sourceKey;
    }

    /**
     * このアイテムの種別を取得する。
     */
    public abstract ItemCategory category();

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
