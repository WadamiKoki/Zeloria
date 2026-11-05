package com.zeloria.npc;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * {@code config/NpcData/*.yml} の {@code equipment} セクションを表すモデル。
 * <p>
 * NPC の装備は見た目専用のため、ドロップ率は 0 に固定する。
 * 未指定・解決できない Material 名は {@link Material#AIR} として扱う。
 *
 * @param mainHand   メインハンド
 * @param helmet     兜
 * @param chestplate 胸当て
 * @param leggings   脚甲
 * @param boots      靴
 */
public record NpcEquipment(
        Material mainHand,
        Material helmet,
        Material chestplate,
        Material leggings,
        Material boots
) {

    /** 装備なしを表す既定値。 */
    public static final NpcEquipment EMPTY = new NpcEquipment(
            Material.AIR, Material.AIR, Material.AIR, Material.AIR, Material.AIR);

    public NpcEquipment {
        mainHand = normalize(mainHand);
        helmet = normalize(helmet);
        chestplate = normalize(chestplate);
        leggings = normalize(leggings);
        boots = normalize(boots);
    }

    /**
     * {@code equipment} セクションから装備設定を生成する。
     *
     * @param section 装備セクション（{@code null} の場合は {@link #EMPTY}）
     * @return 装備設定
     */
    public static NpcEquipment fromConfig(ConfigurationSection section) {
        if (section == null) {
            return EMPTY;
        }
        return new NpcEquipment(
                material(section.getString("mainHand")),
                material(section.getString("helmet")),
                material(section.getString("chestplate")),
                material(section.getString("leggings")),
                material(section.getString("boots"))
        );
    }

    /**
     * すべての部位が空か判定する。
     */
    public boolean isEmpty() {
        return mainHand.isAir() && helmet.isAir() && chestplate.isAir()
                && leggings.isAir() && boots.isAir();
    }

    /**
     * 装備をエンティティへ適用する。
     * <p>
     * NPC の装備がドロップしないよう、ドロップ率は 0 に設定する。
     *
     * @param equipment 対象の装備スロット（{@code null} の場合は何もしない）
     */
    public void apply(EntityEquipment equipment) {
        if (equipment == null) {
            return;
        }

        equipment.setItemInMainHand(item(mainHand));
        equipment.setHelmet(item(helmet));
        equipment.setChestplate(item(chestplate));
        equipment.setLeggings(item(leggings));
        equipment.setBoots(item(boots));

        equipment.setItemInMainHandDropChance(0.0f);
        equipment.setHelmetDropChance(0.0f);
        equipment.setChestplateDropChance(0.0f);
        equipment.setLeggingsDropChance(0.0f);
        equipment.setBootsDropChance(0.0f);
    }

    private static ItemStack item(Material material) {
        return new ItemStack(material);
    }

    private static Material normalize(Material material) {
        return material == null ? Material.AIR : material;
    }

    /**
     * Material 名を解決する（大文字小文字は区別しない）。解決できない場合は AIR。
     */
    private static Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.AIR;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return material == null ? Material.AIR : material;
    }
}
