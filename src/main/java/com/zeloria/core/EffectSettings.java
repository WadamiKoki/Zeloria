package com.zeloria.core;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 効果音・パーティクルの共通設定モデル。
 * <p>
 * YAML の {@code sound} / {@code particle} セクションを 1 回だけ解析し、
 * 解決済みの {@link Sound} / {@link Particle} と再生パラメータを保持する。
 * 毎 tick の処理で YAML を再解析しないよう、各設定クラスが起動時に生成した
 * インスタンスを再利用する。
 */
public final class EffectSettings {

    private EffectSettings() {
    }

    /**
     * YAML の {@code sound} セクションを読み込む。
     *
     * @param section   設定セクション（{@code key} / {@code volume} / {@code pitch}）
     * @param sourceKey 設定ファイルの識別子
     * @param cache     解決結果キャッシュ（{@code null} の場合は都度解決）
     * @param fallback  解決できなかった場合のサウンド
     * @return 効果音設定
     */
    public static SoundEffect loadSound(ConfigurationSection section, String sourceKey,
                                        ConfigCache cache, Sound fallback) {
        if (section == null) {
            return new SoundEffect("", fallback, 1.0f, 1.0f);
        }

        String rawKey = section.getString("key");
        Sound resolved = cache == null
                ? ConfigCache.resolveSound(rawKey)
                : cache.sound(sourceKey, section, "key", fallback);
        if (resolved == null) {
            resolved = fallback;
        }
        String key = rawKey == null || rawKey.isBlank()
                ? (resolved == null ? "" : resolved.getKey().getKey())
                : rawKey.trim();
        float volume = (float) section.getDouble("volume", 1.0);
        float pitch = (float) section.getDouble("pitch", 1.0);
        return new SoundEffect(key, resolved, volume, pitch);
    }

    /**
     * YAML の {@code particle} セクションを読み込む。
     *
     * @param section   設定セクション
     *                  （{@code type} / {@code count} / {@code offset_x} /
     *                  {@code offset_y} / {@code offset_z} / {@code extra}）
     * @param sourceKey 設定ファイルの識別子
     * @param cache     解決結果キャッシュ（{@code null} の場合は都度解決）
     * @param fallback  解決できなかった場合のパーティクル
     * @return パーティクル設定
     */
    public static ParticleEffect loadParticle(ConfigurationSection section, String sourceKey,
                                              ConfigCache cache, Particle fallback) {
        if (section == null) {
            return new ParticleEffect(fallback, 1, 0.0, 0.0, 0.0, 0.0);
        }

        Particle resolved = cache == null
                ? ConfigCache.resolveParticle(section.getString("type"))
                : cache.particle(sourceKey, section, "type", fallback);
        if (resolved == null) {
            resolved = fallback;
        }
        return new ParticleEffect(
                resolved,
                section.getInt("count", 1),
                section.getDouble("offset_x", 0.0),
                section.getDouble("offset_y", 0.0),
                section.getDouble("offset_z", 0.0),
                section.getDouble("extra", 0.0));
    }

    /**
     * 効果音の設定。
     *
     * @param key    サウンド ID（ログ・Adventure 再生用）
     * @param sound  解決済みの Bukkit Sound。解決できなかった場合は {@code null}
     * @param volume 音量（0.0 以上）
     * @param pitch  ピッチ（0.0 以下は 1.0 に補正）
     */
    public record SoundEffect(String key, Sound sound, float volume, float pitch) {

        public SoundEffect {
            key = key == null ? "" : key.trim();
            volume = Math.max(0.0f, volume);
            pitch = pitch <= 0.0f ? 1.0f : pitch;
        }
    }

    /**
     * パーティクルの設定。
     *
     * @param particle 解決済みのパーティクル
     * @param count    生成数（1 以上）
     * @param offsetX  X 方向のばらつき
     * @param offsetY  Y 方向のばらつき
     * @param offsetZ  Z 方向のばらつき
     * @param extra    追加速度
     */
    public record ParticleEffect(
            Particle particle,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double extra
    ) {

        public ParticleEffect {
            count = Math.max(1, count);
        }
    }
}
