package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * {@code config/WeaponData/*.yml} から読み込んだ武器アイテム定義。
 * <p>
 * 基本フォーマット（{@link ItemData}）に加えて、武器種別と武器種共通の通常攻撃
 * パラメータ（攻撃速度・攻撃範囲・攻撃角度・パーティクル・サウンド）を保持する。
 * <pre>
 * id: "sword_test_sword"
 * name: "&lt;white&gt;剣用武器テンプレート"
 * material: IRON_SWORD
 * lore:
 *   - "&lt;gray&gt;剣の武器テンプレート。"
 * sell_price: 0
 * weaponTypes: ["SWORD"]
 * attackSpeedTicks: 32
 * attackRange: 3.5
 * attackAngle: 90.0
 * attackParticle: SWEEP_ATTACK
 * attackSound: ENTITY_PLAYER_ATTACK_SWEEP
 * requiredLevel: 0
 * stats:
 *   attackPower: 4
 *   criticalRate: 3
 * </pre>
 */
public final class WeaponData extends ItemData {

    private final List<String> weaponTypes;
    private final int attackSpeedTicks;
    private final double attackRange;
    private final double attackAngle;
    private final String attackParticle;
    private final String attackSound;
    private final ItemStatBonus statBonus;

    public WeaponData(String id, String name, Material material, List<String> lore,
                      long sellPrice, String sourceKey, List<String> weaponTypes,
                      int attackSpeedTicks, double attackRange, double attackAngle,
                      String attackParticle, String attackSound,
                      ItemStatBonus statBonus) {
        this(id, name, material, lore, sellPrice, sourceKey, weaponTypes,
                attackSpeedTicks, attackRange, attackAngle, attackParticle, attackSound,
                statBonus, null);
    }

    public WeaponData(String id, String name, Material material, List<String> lore,
                      long sellPrice, String sourceKey, List<String> weaponTypes,
                      int attackSpeedTicks, double attackRange, double attackAngle,
                      String attackParticle, String attackSound,
                      ItemStatBonus statBonus, String itemModel) {
        super(id, name, material, lore, sellPrice, sourceKey, itemModel);
        this.weaponTypes = normalizeWeaponTypes(weaponTypes);
        this.attackSpeedTicks = Math.max(1, attackSpeedTicks);
        this.attackRange = Math.max(0.0, attackRange);
        this.attackAngle = Math.max(0.0, Math.min(360.0, attackAngle));
        this.attackParticle = normalizeText(attackParticle);
        this.attackSound = normalizeText(attackSound);
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

        // 武器種共通の既定値。個別の武器 YAML で未指定の場合はここで補完する。
        WeaponType primaryType = WeaponType.primary(weaponTypes);

        return new WeaponData(
                id,
                name,
                material,
                readLore(config),
                config.getLong("sell_price", 0L),
                sourceKey,
                weaponTypes,
                config.getInt("attackSpeedTicks", primaryType.attackSpeedTicks()),
                config.getDouble("attackRange", primaryType.attackRange()),
                config.getDouble("attackAngle", primaryType.attackAngle()),
                config.getString("attackParticle", primaryType.attackParticle()),
                config.getString("attackSound", primaryType.attackSound()),
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

    public int attackSpeedTicks() {
        return attackSpeedTicks;
    }

    public double attackRange() {
        return attackRange;
    }

    public double attackAngle() {
        return attackAngle;
    }

    public String attackParticle() {
        return attackParticle;
    }

    public String attackSound() {
        return attackSound;
    }

    /**
     * 文字列の前後空白を除去する（{@code null} は空文字）。
     */
    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
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
