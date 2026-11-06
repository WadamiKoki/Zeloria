package com.zeloria.player;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code config/ClassData/*.yml} から読み込んだクラス定義。
 * <p>
 * YAML の {@code displayName} / {@code description} は {@link com.zeloria.skill.ClassManager}
 * が MiniMessage で {@link Component} へ変換した状態で保持する。Java 側にクラス名や
 * スキル構成をハードコードせず、すべて設定ファイルを唯一の情報源とする。
 *
 * @param id                クラス ID（小文字・空白なし）
 * @param displayName       表示名（MiniMessage 解決済み）
 * @param description       説明文（MiniMessage 解決済み、複数行）
 * @param icon              GUI アイコンに使用する Material
 * @param unlockedByDefault 初期状態で解放されるか
 * @param skills            このクラスで使用できるスキル ID の一覧
 * @param weaponTypes       このクラスで使用できる武器タイプの一覧（空なら全武器許可）
 * @param baseStats         レベル 1 時点の基礎ステータス
 * @param levelUpPercent    レベルアップごとの成長率（基礎値に対する %）
 */
public record ZeloriaClass(
        String id,
        Component displayName,
        List<Component> description,
        Material icon,
        boolean unlockedByDefault,
        List<String> skills,
        List<String> weaponTypes,
        ClassStats baseStats,
        ClassStats levelUpPercent
) {

    public ZeloriaClass {
        id = normalizeId(id);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("クラス ID が空です。");
        }
        displayName = Objects.requireNonNull(displayName, "displayName");
        description = description == null
                ? List.of()
                : description.stream().filter(Objects::nonNull).toList();
        icon = icon == null ? Material.PAPER : icon;
        skills = skills == null
                ? List.of()
                : skills.stream()
                .filter(Objects::nonNull)
                .map(ZeloriaClass::normalizeId)
                .filter(skillId -> !skillId.isEmpty())
                .distinct()
                .toList();
        weaponTypes = weaponTypes == null
                ? List.of()
                : weaponTypes.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        baseStats = baseStats == null ? ClassStats.DEFAULT : baseStats;
        levelUpPercent = levelUpPercent == null ? ClassStats.NONE : levelUpPercent;
    }

    /**
     * 指定レベルでの実効基礎ステータスを算出する（単利成長）。
     * <p>
     * {@code baseStats × (1 + levelUpPercent / 100 × (level - 1))}。
     * レベル 0 と 1 は {@link #baseStats()} をそのまま返す。
     *
     * @param level レベル
     * @return 実効基礎ステータス
     */
    public ClassStats statsAtLevel(int level) {
        return ClassStats.atLevel(baseStats, levelUpPercent, level);
    }

    /**
     * 指定したスキル ID がこのクラスに許可されているか判定する。
     *
     * @param skillId スキル ID
     * @return 許可されている場合は {@code true}
     */
    public boolean allows(String skillId) {
        String normalized = normalizeId(skillId);
        return !normalized.isEmpty() && skills.contains(normalized);
    }

    /**
     * 指定した武器タイプがこのクラスに許可されているか判定する。
     * <p>
     * {@code weaponTypes} が空の場合は全武器を許可する。
     *
     * @param weaponType 武器タイプ（例: {@code SWORD}）
     * @return 許可されている場合は {@code true}
     */
    public boolean allowsWeaponType(String weaponType) {
        if (weaponTypes.isEmpty()) {
            return true;
        }
        if (weaponType == null || weaponType.isBlank()) {
            return false;
        }
        String normalized = weaponType.trim().toUpperCase(Locale.ROOT);
        return weaponTypes.contains(normalized);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
