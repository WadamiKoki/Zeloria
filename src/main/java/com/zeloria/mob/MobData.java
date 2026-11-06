package com.zeloria.mob;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.Objects;

/**
 * {@code config/MobData/*.yml} から読み込んだモブ定義。
 * <p>
 * 1 ファイルが 1 種類のモブに対応する。
 * <pre>
 * id: "goblin"          # 省略時はファイル名（拡張子なし）
 * name: "ゴブリン"       # 表示名（MiniMessage 可）
 * entityType: "zombie"  # Bukkit のエンティティ種別（省略時は ZOMBIE）
 * maxHp: 50.0
 * defense: 2.0          # 第 2 段階（防具相当）の防御力
 * attackPower: 4.0      # 攻撃力
 * criticalRate: 5.0     # クリティカル率（%）
 * criticalDamage: 50.0  # クリティカルダメージ（% 加算）
 * accuracy: 90.0        # 命中率（%）
 * evasion: 0.0          # 回避率（%）
 * expReward: 15
 * moneyReward: 5
 * </pre>
 * {@code entityType} は {@code zombie} / {@code minecraft:zombie} / {@code ZOMBIE} の
 * いずれの表記も受け付ける。
 *
 * @param id          モブ ID（コマンド引数・識別子）
 * @param name        表示名（MiniMessage 形式）
 * @param sourceKey   YAML のキャッシュキー（例: {@code MobData/Goblin.yml}）
 * @param entityType  スポーンさせるエンティティ種別（解決できなかった場合は {@code null}）
 * @param maxHp          最大 HP（0 以下ならエンティティ既定値を使用）
 * @param defense        第 2 段階の防御力（防具相当）
 * @param attackPower    攻撃力
 * @param criticalRate   クリティカル率（%）
 * @param criticalDamage クリティカルダメージ（% 加算。50.0 = +50%）
 * @param accuracy       命中率（%）
 * @param evasion        回避率（%）
 * @param expReward      撃破時経験値
 * @param moneyReward    撃破時報酬
 */
public record MobData(
        String id,
        String name,
        String sourceKey,
        EntityType entityType,
        double maxHp,
        double defense,
        double attackPower,
        double criticalRate,
        double criticalDamage,
        double accuracy,
        double evasion,
        int expReward,
        int moneyReward
) {

    /** {@code entityType} が未指定のときに使用する既定のエンティティ種別。 */
    public static final EntityType DEFAULT_ENTITY_TYPE = EntityType.ZOMBIE;

    private static final String YAML_EXTENSION = ".yml";
    private static final String SEPARATOR = ":";

    public MobData {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        sourceKey = sourceKey == null ? "" : sourceKey;
        maxHp = Math.max(maxHp, 0.0);
        defense = Math.max(defense, 0.0);
        attackPower = Math.max(attackPower, 0.0);
        criticalRate = Math.max(0.0, Math.min(100.0, criticalRate));
        criticalDamage = Math.max(criticalDamage, 0.0);
        accuracy = Math.max(0.0, Math.min(100.0, accuracy));
        evasion = Math.max(0.0, Math.min(100.0, evasion));
        expReward = Math.max(expReward, 0);
        moneyReward = Math.max(moneyReward, 0);
    }

    /**
     * YAML 設定からモブ定義を生成する。
     *
     * @param sourceKey YAML のキャッシュキー（例: {@code MobData/Goblin.yml}）
     * @param config    読み込み済み設定
     * @return モブ定義
     */
    public static MobData fromConfig(String sourceKey, FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        String fallbackId = fallbackId(sourceKey);
        String id = text(config.getString("id"), fallbackId);
        String name = text(config.getString("name"), id);

        return new MobData(
                id,
                name,
                sourceKey == null ? "" : sourceKey,
                resolveEntityType(config.getString("entityType")),
                config.getDouble("maxHp", 0.0),
                config.getDouble("defense", 0.0),
                config.getDouble("attackPower", MobStats.DEFAULT_ATTACK_POWER),
                config.getDouble("criticalRate", MobStats.DEFAULT_CRITICAL_RATE),
                config.getDouble("criticalDamage", MobStats.DEFAULT_CRITICAL_DAMAGE),
                config.getDouble("accuracy", MobStats.DEFAULT_ACCURACY),
                config.getDouble("evasion", MobStats.DEFAULT_EVASION),
                config.getInt("expReward", 0),
                config.getInt("moneyReward", 0)
        );
    }

    /**
     * エンティティ種別の文字列を {@link EntityType} へ解決する。
     * <p>
     * {@code zombie} / {@code minecraft:zombie} / {@code ZOMBIE} のいずれも受け付け、
     * 未指定・解決不能の場合は {@code null} を返す（呼び出し側で既定値へフォールバックする）。
     *
     * @param raw エンティティ種別の文字列
     * @return 解決されたエンティティ種別。解決できない場合は {@code null}
     */
    public static EntityType resolveEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = normalized.contains(SEPARATOR)
                ? NamespacedKey.fromString(normalized)
                : NamespacedKey.minecraft(normalized);
        if (key == null) {
            return null;
        }
        return Registry.ENTITY_TYPE.get(key);
    }

    /**
     * スポーンに使用するエンティティ種別を返す。
     * <p>
     * YAML で解決できなかった場合は {@link #DEFAULT_ENTITY_TYPE} を返す。
     */
    public EntityType spawnableEntityType() {
        return entityType == null ? DEFAULT_ENTITY_TYPE : entityType;
    }

    /**
     * {@code MobData/<ファイル名>.yml} から拡張子なしファイル名を取り出す。
     */
    private static String fallbackId(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return "";
        }
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        return fileName.toLowerCase(Locale.ROOT).endsWith(YAML_EXTENSION)
                ? fileName.substring(0, fileName.length() - YAML_EXTENSION.length())
                : fileName;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
