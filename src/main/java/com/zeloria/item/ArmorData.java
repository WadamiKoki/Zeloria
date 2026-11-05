package com.zeloria.item;

import com.zeloria.player.EquipSlot;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * {@code config/ArmorData/*.yml} から読み込んだ防具アイテム定義。
 * <p>
 * 基本フォーマット（{@link ItemData}）に加えて、装着部位 {@code equipment_slot}
 * （HELMET / CHESTPLATE / LEGGINGS / BOOTS）と各種ステータスを保持する。
 * <pre>
 * id: "helmet"
 * name: "&lt;white&gt;ヘルメット"
 * material: IRON_HELMET
 * lore:
 *   - "&lt;gray&gt;防具テンプレート（ヘルメット）。"
 * sell_price: 0
 * equipment_slot: HELMET
 * requiredLevel: 0
 * stats:
 *   maxHealth: 10
 *   defense2: 2
 *   healthRegenFlat: 0.5
 * </pre>
 */
public final class ArmorData extends ItemData {

    private final EquipSlot equipmentSlot;
    private final ItemStatBonus statBonus;

    public ArmorData(String id, String name, Material material, List<String> lore,
                     long sellPrice, String sourceKey, EquipSlot equipmentSlot,
                     ItemStatBonus statBonus) {
        this(id, name, material, lore, sellPrice, sourceKey, equipmentSlot, statBonus, null);
    }

    public ArmorData(String id, String name, Material material, List<String> lore,
                     long sellPrice, String sourceKey, EquipSlot equipmentSlot,
                     ItemStatBonus statBonus, String itemModel) {
        super(id, name, material, lore, sellPrice, sourceKey, itemModel);
        this.equipmentSlot = equipmentSlot == null ? EquipSlot.HELMET : equipmentSlot;
        this.statBonus = statBonus == null ? ItemStatBonus.NONE : statBonus;
    }

    /**
     * YAML 設定から防具定義を生成する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code ArmorData/helmet.yml}）
     * @param config    読み込み済み設定
     * @return 防具定義
     * @throws IllegalArgumentException {@code material} が未指定・不正な場合
     */
    public static ArmorData fromConfig(String sourceKey, FileConfiguration config) {
        Material material = readMaterial(config);
        String fallback = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallback);
        String name = text(config.getString("name"), id);

        return new ArmorData(
                id,
                name,
                material,
                readLore(config),
                config.getLong("sell_price", 0L),
                sourceKey,
                resolveEquipmentSlot(config.getString("equipment_slot"), material),
                ItemStatBonus.fromConfig(config),
                config.getString("item_model")
        );
    }

    /**
     * {@code equipment_slot} を {@link EquipSlot} へ解決する。
     * <p>
     * {@code HELMET} / {@code helmet} のどちらの表記も受け付ける。未指定・不正な場合は
     * Material から装着部位を推定し、それでも解決できなければ兜として扱う。
     *
     * @param raw      {@code equipment_slot} の値
     * @param material 防具の Material
     * @return 装着部位（{@code null} にはならない）
     */
    public static EquipSlot resolveEquipmentSlot(String raw, Material material) {
        if (raw != null && !raw.isBlank()) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            for (EquipSlot slot : EquipSlot.values()) {
                if (isArmorSlot(slot) && slot.name().equals(normalized)) {
                    return slot;
                }
            }
            EquipSlot byKey = EquipSlot.fromKey(raw);
            if (isArmorSlot(byKey)) {
                return byKey;
            }
        }

        if (material != null) {
            EquipSlot matched = EquipSlot.match(new ItemStack(material));
            if (isArmorSlot(matched)) {
                return matched;
            }
        }
        return EquipSlot.HELMET;
    }

    private static boolean isArmorSlot(EquipSlot slot) {
        return slot == EquipSlot.HELMET
                || slot == EquipSlot.CHESTPLATE
                || slot == EquipSlot.LEGGINGS
                || slot == EquipSlot.BOOTS;
    }

    /**
     * 装着部位を取得する。
     */
    public EquipSlot equipmentSlot() {
        return equipmentSlot;
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

    public double atk() {
        return statBonus.attackPower();
    }

    public double def() {
        return statBonus.defense();
    }

    public double hpRegen() {
        return statBonus.healthRegenFlat();
    }

    public double mpRegen() {
        return statBonus.manaRegenFlat();
    }

    public double critRate() {
        return statBonus.criticalRate();
    }

    public double critDmg() {
        return statBonus.criticalDamage();
    }

    public double cdr() {
        return statBonus.cooldownReduction();
    }

    public double moveSpeed() {
        return statBonus.moveSpeed();
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.ARMOR;
    }

    @Override
    public int maxStackSize() {
        return 1;
    }
}
