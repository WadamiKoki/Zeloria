package com.zeloria.weapon;

import java.util.List;
import java.util.Objects;

import org.bukkit.Material;

/**
 * 武器1種の不変定義。
 * YAMLから読み込んだ ID / ダメージ / サウンドID などを保持する。
 */
public final class WeaponDefinition {

    /** PDC に埋め込む武器IDキー（value は "weapon_id"）。 */
    public static final String PDC_WEAPON_ID_KEY = "weapon_id";

    private final String id;
    private final String displayName;
    private final Material material;
    private final double damage;
    private final double headshotMultiplier;
    private final int magazineSize;
    private final List<String> lore;
    private final Integer customModelData;
    private final Sounds sounds;

    public WeaponDefinition(String id, String displayName, Material material, double damage,
                            double headshotMultiplier, int magazineSize, List<String> lore,
                            Integer customModelData, Sounds sounds) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = displayName != null ? displayName : id;
        this.material = Objects.requireNonNull(material, "material");
        this.damage = damage;
        this.headshotMultiplier = headshotMultiplier;
        this.magazineSize = magazineSize;
        this.lore = lore != null ? List.copyOf(lore) : List.of();
        this.customModelData = customModelData;
        this.sounds = sounds != null ? sounds : new Sounds(null, null);
    }

    public String getId() { return this.id; }
    public String getDisplayName() { return this.displayName; }
    public Material getMaterial() { return this.material; }
    public double getDamage() { return this.damage; }
    public double getHeadshotMultiplier() { return this.headshotMultiplier; }
    public int getMagazineSize() { return this.magazineSize; }
    public List<String> getLore() { return this.lore; }
    public Integer getCustomModelData() { return this.customModelData; }
    public Sounds getSounds() { return this.sounds; }

    /**
     * 武器の発射/リロード音のIDのみを保持する。
     * 実際の再生処理は Phase 2 で実装する。
     */
    public static final class Sounds {
        private final String fire;
        private final String reload;

        public Sounds(String fire, String reload) {
            this.fire = fire;
            this.reload = reload;
        }

        public String getFire() { return this.fire; }
        public String getReload() { return this.reload; }
    }
}
