package com.zeloria.world;

import com.zeloria.core.ConfigCache;
import com.zeloria.core.EffectSettings;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * {@code config/SystemData/portal_settings.yml} から読み込んだポータル設定。
 * <p>
 * 転送クールダウン・転送音・外周パーティクルの描画値を保持する。
 * 発動半径と垂直許容範囲は仕様固定のため、本設定には含めない。
 */
public final class PortalSettings {

    /** 設定ファイルの識別子。 */
    public static final String SETTINGS_KEY = "SystemData/portal_settings.yml";

    private final Portal portal;

    public PortalSettings(Portal portal) {
        this.portal = Objects.requireNonNull(portal, "portal");
    }

    /**
     * YAML 設定から読み込む。
     *
     * @param config 設定（必須）
     * @param cache  Sound / Particle の解決キャッシュ
     * @return 読み込んだ設定
     */
    public static PortalSettings fromConfig(FileConfiguration config, ConfigCache cache) {
        Objects.requireNonNull(config, "config");

        ConfigurationSection portal = config.getConfigurationSection("portal");
        ConfigurationSection warpSound = portal == null
                ? null
                : portal.getConfigurationSection("warp_sound");
        ConfigurationSection particle = portal == null
                ? null
                : portal.getConfigurationSection("particle");

        EffectSettings.SoundEffect sound = EffectSettings.loadSound(
                warpSound, SETTINGS_KEY, cache, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        PortalParticle portalParticle = new PortalParticle(
                cache == null
                        ? ConfigCache.resolveParticle(particle == null ? null : particle.getString("type"))
                        : cache.particle(SETTINGS_KEY, particle, "type", Particle.END_ROD),
                particle == null ? 32.0 : particle.getDouble("view_distance"),
                particle == null ? 8 : particle.getInt("ring_points"),
                particle == null ? 0.1 : particle.getDouble("height_offset"),
                particle == null ? 1 : particle.getInt("count_per_point"));

        return new PortalSettings(new Portal(
                portal == null ? 0L : portal.getLong("cooldown_millis"),
                sound,
                portalParticle));
    }

    public Portal portal() {
        return portal;
    }

    /**
     * ポータル共通設定。
     *
     * @param cooldownMillis 再発動までのクールダウン（ミリ秒）
     * @param warpSound      転送成功時の効果音
     * @param particle       外周パーティクルの描画設定
     */
    public record Portal(
            long cooldownMillis,
            EffectSettings.SoundEffect warpSound,
            PortalParticle particle
    ) {

        public Portal {
            cooldownMillis = Math.max(0L, cooldownMillis);
            warpSound = Objects.requireNonNull(warpSound, "warpSound");
            particle = Objects.requireNonNull(particle, "particle");
        }
    }

    /**
     * ポータル外周パーティクルの設定。
     *
     * @param type            パーティクル種別
     * @param viewDistance    送信する最大距離（メートル）
     * @param ringPoints      円周上に配置する点数
     * @param heightOffset    ポータル座標から浮かせる高さ
     * @param countPerPoint   1 点あたりの生成数
     */
    public record PortalParticle(
            Particle type,
            double viewDistance,
            int ringPoints,
            double heightOffset,
            int countPerPoint
    ) {

        public PortalParticle {
            // セクションが無い場合の解決失敗に備え、描画不能にならない既定種別へ寄せる。
            type = type == null ? Particle.END_ROD : type;
            viewDistance = Math.max(1.0, viewDistance);
            ringPoints = Math.max(1, ringPoints);
            countPerPoint = Math.max(1, countPerPoint);
        }
    }
}
