package com.zeloria.item;

import java.util.List;
import java.util.Locale;

/**
 * 武器種ごとの通常攻撃パラメータ。
 * <p>
 * {@code WeaponData/<type>/test.yml} で個別に指定された値を優先し、未指定の場合は
 * 本 enum の既定値を使用する。これにより、武器ごとの YAML に攻撃範囲・攻撃速度などを
 * 重複して記述しなくても、武器種共通の挙動で動作する。
 * <p>
 * {@code attackAngle} は攻撃方向を中心とした扇の全角度（度）を表す。
 * {@code 0.0} は直線攻撃として扱う。
 */
public enum WeaponType {

    /** 剣。広めの扇形攻撃。 */
    SWORD(32, 3.5, 90.0, "SWEEP_ATTACK", "ENTITY_PLAYER_ATTACK_SWEEP"),

    /** 大斧。レイキャストによる直線攻撃（幅 3 ブロック）。 */
    AXE(64, 5.0, 0.0, "CRIT", "ENTITY_PLAYER_ATTACK_CRIT"),

    /** 短剣。狭めの扇形攻撃で、最も近い 1 体のみに命中する。 */
    DAGGER(16, 2.5, 60.0, "SWEEP_ATTACK", "ENTITY_PLAYER_ATTACK_SWEEP"),

    /** 杖。火球を発射する遠距離武器。 */
    STAFF(32, 8.0, 0.0, "FLAME", "ENTITY_BLAZE_SHOOT"),

    /** 弓。矢を発射する遠距離武器。 */
    BOW(32, 12.0, 0.0, "CRIT", "ENTITY_ARROW_SHOOT"),

    /** 未知の武器種。SWORD 相当の近接攻撃として扱う。 */
    UNKNOWN(32, 3.0, 90.0, "SWEEP_ATTACK", "ENTITY_PLAYER_ATTACK_SWEEP");

    private final int attackSpeedTicks;
    private final double attackRange;
    private final double attackAngle;
    private final String attackParticle;
    private final String attackSound;

    WeaponType(int attackSpeedTicks,
               double attackRange,
               double attackAngle,
               String attackParticle,
               String attackSound) {
        this.attackSpeedTicks = attackSpeedTicks;
        this.attackRange = attackRange;
        this.attackAngle = attackAngle;
        this.attackParticle = attackParticle;
        this.attackSound = attackSound;
    }

    /**
     * 武器種名を解決する（大文字小文字は区別しない）。
     *
     * @param raw 武器種名
     * @return 解決した武器種。未知の場合は {@link #UNKNOWN}
     */
    public static WeaponType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    /**
     * 武器種リストから通常攻撃に使用する代表種を取得する。
     * <p>
     * 複数指定されている場合は、既知の武器種のうち最初のものを使用する。
     *
     * @param weaponTypes 武器種リスト
     * @return 代表の武器種。既知の種が無い場合は {@link #UNKNOWN}
     */
    public static WeaponType primary(List<String> weaponTypes) {
        if (weaponTypes != null) {
            for (String weaponType : weaponTypes) {
                WeaponType parsed = from(weaponType);
                if (parsed != UNKNOWN) {
                    return parsed;
                }
            }
        }
        return UNKNOWN;
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
     * 近接武器か判定する。
     */
    public boolean isMelee() {
        return this == SWORD || this == AXE || this == DAGGER;
    }

    /**
     * 遠距離武器か判定する。
     */
    public boolean isRanged() {
        return this == STAFF || this == BOW;
    }
}
