package com.zeloria.skill;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code config/SkillData/*.yml} から読み込んだスキル定義。
 * <p>
 * 属性・ダメージ計算は本基盤の対象外だが、将来の計算で利用する
 * コスト・倍率・射程は設定値のまま保持する。クールダウンは秒単位で定義し、
 * 内部ではミリ秒へ変換して扱う。
 * <p>
 * スキルはクラス定義の {@code skills} とスキルスロット（数字キー等）によって
 * 選択される想定。{@code icon} はスキルスロット表示用の見た目だけに使用し、
 * ダメージ計算には使用しない。
 *
 * @param id                   スキル ID（小文字・空白なし）
 * @param displayName          表示名（MiniMessage 解決済み）
 * @param description          説明文（MiniMessage 解決済み、複数行）
 * @param icon                 スキルスロットに表示するアイコン
 * @param cooldownSeconds      クールダウン（秒）
 * @param manaCost             固定マナコスト
 * @param manaPercentCost      最大マナに対する割合コスト（0.1 = 10%）
 * @param healthCost           固定ヘルスコスト
 * @param healthPercentCost    最大ヘルスに対する割合コスト（0.05 = 5%）
 * @param baseDamageMultiplier 基礎ダメージ倍率
 * @param range                効果範囲・射程距離（ブロック単位）
 * @param armorPenetration     防御貫通率（0.0〜1.0。第 2 段階防御力にのみ適用）
 * @param lifesteal            与ダメージに対する吸血率（0.0〜1.0）
 * @param particle             発動時に表示するパーティクル名
 * @param particleCount        パーティクル数
 * @param particleSpread       パーティクルの広がり
 * @param sound                発動時に再生するサウンド ID
 * @param soundVolume          サウンド音量
 * @param soundPitch           サウンドピッチ
 * @param upgrades             今後のツリー分岐用の空枠（現状は空）
 */
public record Skill(
        String id,
        Component displayName,
        List<Component> description,
        Material icon,
        double cooldownSeconds,
        double manaCost,
        double manaPercentCost,
        double healthCost,
        double healthPercentCost,
        double baseDamageMultiplier,
        double range,
        double armorPenetration,
        double lifesteal,
        String particle,
        int particleCount,
        double particleSpread,
        String sound,
        double soundVolume,
        double soundPitch,
        List<String> upgrades
) {

    public Skill {
        id = normalizeId(id);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("スキル ID が空です。");
        }
        displayName = Objects.requireNonNull(displayName, "displayName");
        description = description == null
                ? List.of()
                : description.stream().filter(Objects::nonNull).toList();
        if (icon == null || icon.isAir()) {
            icon = Material.PAPER;
        }
        cooldownSeconds = Math.max(0.0, cooldownSeconds);
        manaCost = Math.max(0.0, manaCost);
        manaPercentCost = Math.max(0.0, manaPercentCost);
        healthCost = Math.max(0.0, healthCost);
        healthPercentCost = Math.max(0.0, healthPercentCost);
        baseDamageMultiplier = Math.max(0.0, baseDamageMultiplier);
        range = Math.max(0.0, range);
        armorPenetration = Math.max(0.0, Math.min(1.0, armorPenetration));
        lifesteal = Math.max(0.0, Math.min(1.0, lifesteal));
        particle = particle == null ? "" : particle.trim();
        particleCount = Math.max(1, particleCount);
        particleSpread = Math.max(0.0, particleSpread);
        sound = sound == null ? "" : sound.trim();
        soundVolume = Math.max(0.0, soundVolume);
        soundPitch = Math.max(0.0, soundPitch);
        upgrades = upgrades == null
                ? List.of()
                : upgrades.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(upgradeId -> !upgradeId.isEmpty())
                .toList();
    }

    /**
     * クールダウンをミリ秒へ変換する。
     *
     * @return クールダウン（ミリ秒）
     */
    public long cooldownMillis() {
        return Math.round(cooldownSeconds * 1000.0);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

