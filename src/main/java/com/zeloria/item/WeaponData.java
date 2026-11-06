package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * {@code config/WeaponData/*.yml} から読み込んだ武器アイテム定義。
 * <p>
 * 基本フォーマット（{@link ItemData}）に加えて、武器種別・基礎ダメージ・
 * 攻撃範囲・攻撃角度を保持する。
 * <pre>
 * id: "knight_test_sword"
 * name: "&lt;white&gt;ナイト用武器テンプレート（剣）"
 * material: IRON_SWORD
 * lore:
 *   - "&lt;gray&gt;ナイト用の武器テンプレート。"
 * sell_price: 0
 * weaponTypes: ["SWORD", "AXE"]
 * baseDamage: 10.0
 * attackRange: 3.0
 * attackAngle: 90.0
 * requiredLevel: 0
 * stats:
 *   attackPower: 4
 *   criticalRate: 3
 * </pre>
 */
public final class WeaponData extends ItemData {

    private final List<String> weaponTypes;
    private final double baseDamage;
    private final double attackRange;
    private final double attackAngle;
    private final ItemStatBonus statBonus;

    public WeaponData(String id, String name, Material material, List<String> lore,
                      long sellPrice, String sourceKey, List<String> weaponTypes,
                      double baseDamage, double attackRange, double attackAngle,
                      ItemStatBonus statBonus) {
        this(id, name, material, lore, sellPrice, sourceKey, weaponTypes,
                baseDamage, attackRange, attackAngle, statBonus, null);
    }

    public WeaponData(String id, String name, Material material, List<String> lore,
                      long sellPrice, String sourceKey, List<String> weaponTypes,
                      double baseDamage, double attackRange, double attackAngle,
                      ItemStatBonus statBonus, String itemModel) {
        super(id, name, material, lore, sellPrice, sourceKey, itemModel);
        this.weaponTypes = normalizeWeaponTypes(weaponTypes);
        this.baseDamage = baseDamage;
        this.attackRange = attackRange;
        this.attackAngle = attackAngle;
        this.statBonus = statBonus == null ? ItemStatBonus.NONE : statBonus;
    }

    /**
     * YAML 設定から武器定義を生成する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code WeaponData/BasicSword.yml}）
     * @param config    読み込み済み設定
     * @return 武器定義
     * @throws IllegalArgumentException {@code material} が未指定・不正な場合
     */
    public static WeaponData fromConfig(String sourceKey, FileConfiguration config) {
        Material material = readMaterial(config);
        String fallback = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallback);
        String name = text(config.getString("name"), id);

        List<String> weaponTypes = config.getStringList("weaponTypes");
        if (weaponTypes.isEmpty()) {
            // 旧キー（単一文字列）との後方互換。
            String legacy = config.getString("weaponType");
            if (legacy != null && !legacy.isBlank()) {
                weaponTypes = List.of(legacy);
            }
        }

        return new WeaponData(
                id,
                name,
                material,
                readLore(config),
                config.getLong("sell_price", 0L),
                sourceKey,
                weaponTypes,
                config.getDouble("baseDamage", 0.0),
                config.getDouble("attackRange", 0.0),
                config.getDouble("attackAngle", 0.0),
                ItemStatBonus.fromConfig(config),
                config.getString("item_model")
        );
    }

    /**
     * この武器が扱える武器タイプの一覧を取得する。
     * <p>
     * 空になることはなく、未指定の場合は {@code SWORD} を返す。
     */
    public List<String> weaponTypes() {
        return weaponTypes;
    }

    /**
     * 武器タイプの表記を正規化する（大文字・重複除去・空要素の除外）。
     */
    private static List<String> normalizeWeaponTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of("SWORD");
        }
        List<String> normalized = raw.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of("SWORD") : normalized;
    }

    public double baseDamage() {
        return baseDamage;
    }

    public double attackRange() {
        return attackRange;
    }

    public double attackAngle() {
        return attackAngle;
    }

    /**
     * ステータス補正を取得する。
     *
     * @return ステータス補正
     */
    public ItemStatBonus statBonus() {
        return statBonus;
    }

    /**
     * 装着に必要な最低レベルを取得する。
     *
     * @return 必要レベル（0 は制限なし）
     */
    public int requiredLevel() {
        return statBonus.requiredLevel();
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.WEAPON;
    }

    @Override
    public int maxStackSize() {
        return 1;
    }
}
