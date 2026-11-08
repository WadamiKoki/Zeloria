package com.zeloria.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * {@code config/ItemData/weapon/*.yml} から読み込んだ武器アイテム定義。
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
 * attackSound: "entity.player.attack.sweep"
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
        super(id, name, material, lore, sellPrice, sourceKey);
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
     * @param sourceKey YAML のキャッシュキー（例: {@code ItemData/weapon/sword/test.yml}）
     * @param config    読み込み済み設定
     * @return 武器定義
     * @throws IllegalArgumentException {@code material} が未指定・不正な場合
     */
    public static WeaponData fromConfig(String sourceKey, FileConfiguration config) {
        Material material = readMaterial(config);
        String fallback = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallback);
        String name = text(config.getString("name"), id);

        List<String> weaponTypes = readWeaponTypes(sourceKey, config);

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
                ItemStatBonus.fromConfig(config)
        );
    }

    /**
     * YAML から武器種を読み取る。
     * <p>
     * 標準の {@code weaponTypes} に加えて、別名の {@code weapon_types} /
     * {@code weaponType} / {@code weapon_type} / {@code type} も受理する。
     * いずれも未指定の場合は読み込み元のパス
     * （{@code ItemData/weapon/<武器種>/...}）から武器種を推定する。
     * {@code type} に {@code weapon} などのカテゴリ名が書かれている場合は
     * 武器種として扱わない。
     *
     * @param sourceKey YAML のキャッシュキー
     * @param config    読み込み済み設定
     * @return 武器種リスト（未指定の場合は空）
     */
    private static List<String> readWeaponTypes(String sourceKey, FileConfiguration config) {
        List<String> configured = validWeaponTypes(config.getStringList("weaponTypes"));
        if (configured.isEmpty()) {
            configured = validWeaponTypes(config.getStringList("weapon_types"));
        }
        if (configured.isEmpty()) {
            String single = null;
            for (String candidate : new String[]{
                    config.getString("weaponTypes"),
                    config.getString("weapon_types"),
                    config.getString("weaponType"),
                    config.getString("weapon_type"),
                    config.getString("type")}) {
                if (candidate != null && !candidate.isBlank()
                        && WeaponType.from(candidate) != WeaponType.UNKNOWN) {
                    single = candidate.trim();
                    break;
                }
            }
            if (single != null) {
                configured = List.of(single);
            }
        }
        if (configured.isEmpty()) {
            String inferred = inferWeaponType(sourceKey);
            if (inferred != null) {
                configured = List.of(inferred);
            }
        }
        return configured;
    }

    /**
     * 既知の武器種だけを残す。
     *
     * @param raw YAML に記載された値
     * @return 正規化済みの武器種リスト（未知の値は除外）
     */
    private static List<String> validWeaponTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty() && WeaponType.from(value) != WeaponType.UNKNOWN)
                .toList();
    }

    /**
     * 読み込み元パスの {@code weapon/<武器種>/} から武器種を推定する。
     *
     * @param sourceKey YAML のキャッシュキー
     * @return 推定した武器種。推定できない場合は {@code null}
     */
    private static String inferWeaponType(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return null;
        }
        String normalized = sourceKey.replace('\\', '/');
        int start;
        if (normalized.startsWith("weapon/")) {
            start = "weapon/".length();
        } else {
            int marker = normalized.indexOf("/weapon/");
            if (marker < 0) {
                return null;
            }
            start = marker + "/weapon/".length();
        }
        int end = normalized.indexOf('/', start);
        if (end <= start) {
            return null;
        }
        String segment = normalized.substring(start, end);
        if (WeaponType.from(segment) == WeaponType.UNKNOWN) {
            return null;
        }
        return segment.trim().toUpperCase(java.util.Locale.ROOT);
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
}
