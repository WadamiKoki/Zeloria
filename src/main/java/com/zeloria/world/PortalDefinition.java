package com.zeloria.world;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Objects;

/**
 * レイヤー内に配置するポータルの定義。
 * <p>
 * {@code LayerData} の {@code portals} セクションにおける 1 エントリに対応する。
 * <pre>
 * portals:
 *   to_field:
 *     world: "hub_instance_1"   # 空なら対象レイヤーの全インスタンスで有効
 *     x: 10.5
 *     y: 65.0
 *     z: 10.5
 *     radius: 1.5
 *     target_template: "field_1"
 *     target_x: 0.5
 *     target_y: 65.0
 *     target_z: 0.5
 *     target_yaw: 0.0
 *     target_pitch: 0.0
 * </pre>
 * 転送先は上記のフラットな {@code target_*} 形式に加え、次のネスト形式も受け付ける:
 * <pre>
 *     target:
 *       template: "field_1"
 *       x: 0.5
 *       y: 65.0
 *       z: 0.5
 *       yaw: 0.0
 *       pitch: 0.0
 * </pre>
 *
 * @param id             ポータル ID
 * @param world          ポータルが設置されたワールド名（空ならレイヤーの全インスタンス）
 * @param x              設置座標 X
 * @param y              設置座標 Y
 * @param z              設置座標 Z
 * @param radius         発動半径
 * @param targetTemplate 転送先レイヤーのテンプレート ID
 * @param targetX        転送先 X（未指定なら {@code null}）
 * @param targetY        転送先 Y（未指定なら {@code null}）
 * @param targetZ        転送先 Z（未指定なら {@code null}）
 * @param targetYaw      転送先 Yaw（未指定なら {@code null}）
 * @param targetPitch    転送先 Pitch（未指定なら {@code null}）
 */
public record PortalDefinition(
        String id,
        String world,
        double x,
        double y,
        double z,
        double radius,
        String targetTemplate,
        Double targetX,
        Double targetY,
        Double targetZ,
        Float targetYaw,
        Float targetPitch
) {

    /**
     * ポータルの統一半径（メートル）。
     * <p>
     * 発動判定（足元座標からの距離）と外周パーティクルの描画は、どちらもこの値を基準にそろえる。
     */
    public static final double PORTAL_RADIUS = 1.0;

    /** 既定の発動半径（{@link #PORTAL_RADIUS} に統一）。 */
    public static final double DEFAULT_RADIUS = PORTAL_RADIUS;

    public PortalDefinition {
        id = id == null ? "" : id;
        world = world == null ? "" : world;
        // 判定（足元からの距離）と外周パーティクルの描画を一致させるため、
        // 設定値に関わらず半径は常に PORTAL_RADIUS（1.0m）へ統一する。
        radius = PORTAL_RADIUS;
        targetTemplate = targetTemplate == null ? "" : targetTemplate;
    }

    /**
     * ポータル定義を YAML セクションから読み込む。
     *
     * @param id      ポータル ID
     * @param section ポータルの設定セクション
     * @return ポータル定義
     */
    public static PortalDefinition fromConfig(String id, ConfigurationSection section) {
        Objects.requireNonNull(section, "section");

        ConfigurationSection target = section.getConfigurationSection("target");

        String template = section.getString("target_template");
        if (template == null || template.isBlank()) {
            template = target == null ? "" : target.getString("template",
                    target.getString("target_template", ""));
        }

        return new PortalDefinition(
                id,
                section.getString("world", ""),
                section.getDouble("x", 0.0),
                section.getDouble("y", 0.0),
                section.getDouble("z", 0.0),
                // radius は互換のため読み取り自体は行わず、常に仕様値を使用する。
                PORTAL_RADIUS,
                template,
                number(section, target, "target_x", "x"),
                number(section, target, "target_y", "y"),
                number(section, target, "target_z", "z"),
                floatNumber(section, target, "target_yaw", "yaw"),
                floatNumber(section, target, "target_pitch", "pitch")
        );
    }

    /**
     * 転送先テンプレートが指定されているか判定する。
     */
    public boolean hasTargetTemplate() {
        return !targetTemplate.isBlank();
    }

    /**
     * 転送先座標が明示されているか判定する。
     */
    public boolean hasTargetLocation() {
        return targetX != null && targetY != null && targetZ != null;
    }

    private static Double number(ConfigurationSection section, ConfigurationSection nested,
                                 String flatKey, String nestedKey) {
        if (section.isSet(flatKey)) {
            return section.getDouble(flatKey);
        }
        if (nested != null && nested.isSet(nestedKey)) {
            return nested.getDouble(nestedKey);
        }
        return null;
    }

    private static Float floatNumber(ConfigurationSection section, ConfigurationSection nested,
                                     String flatKey, String nestedKey) {
        Double value = number(section, nested, flatKey, nestedKey);
        return value == null ? null : value.floatValue();
    }
}
