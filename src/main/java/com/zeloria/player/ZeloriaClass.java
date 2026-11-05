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
 * @param baseStats         レベル 1 時点の基礎ステータス
 * @param levelUpStats      レベルアップごとの成長値
 */
public record ZeloriaClass(
        String id,
        Component displayName,
        List<Component> description,
        Material icon,
        boolean unlockedByDefault,
        List<String> skills,
        ClassStats baseStats,
        ClassStats levelUpStats
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
        baseStats = baseStats == null ? ClassStats.DEFAULT : baseStats;
        levelUpStats = levelUpStats == null ? ClassStats.NONE : levelUpStats;
    }

    /**
     * 指定レベルでの実効基礎ステータスを算出する。
     * <p>
     * {@code baseStats + levelUpStats × (level - 1)}。レベル 0 と 1 は
     * {@link #baseStats()} をそのまま返す。
     *
     * @param level レベル
     * @return 実効基礎ステータス
     */
    public ClassStats statsAtLevel(int level) {
        return ClassStats.atLevel(baseStats, levelUpStats, level);
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

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
