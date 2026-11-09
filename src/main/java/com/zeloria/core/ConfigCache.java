package com.zeloria.core;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * YAML 上の音・パーティクル・マテリアル名を起動時に Enum へ解決してキャッシュする。
 * <p>
 * 文字列のまま毎回パースすると、GUI 描画や戦闘処理のたびに変換コストが発生する。
 * 本クラスは「設定パス → 解決済み Enum」の Map を保持し、同じ設定を繰り返し
 * 参照しても変換を 1 回で済ませる。
 * <p>
 * キー未設定・不正な名前の場合は警告を出し、呼び出し側が指定した安全な既定値へ
 * フォールバックする。キャッシュは {@link #clear()} で破棄でき、{@code /admin reload}
 * 時に各サービスが再読み込みする。
 */
public final class ConfigCache {

    private final JavaPlugin plugin;
    private final Map<String, Material> materials = new HashMap<>();
    private final Map<String, Sound> sounds = new HashMap<>();
    private final Map<String, Particle> particles = new HashMap<>();

    public ConfigCache(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 再読み込み前に解決済み Enum を破棄する。
     */
    public void clear() {
        materials.clear();
        sounds.clear();
        particles.clear();
    }

    /**
     * マテリアル名を解決してキャッシュする。
     * <p>
     * 既存の呼び出しとの互換用。{@code sourceKey} なしで呼んだ場合は
     * キャッシュキーのファイル部分が空文字になる。
     */
    public Material material(ConfigurationSection section, String path, Material fallback) {
        return material("", section, path, fallback);
    }

    /**
     * マテリアル名を解決してキャッシュする。
     *
     * @param sourceKey 設定ファイルの識別子（例: {@code GuiData/menu.yml}）
     * @param section   設定セクション
     * @param path      セクションからの相対キー
     * @param fallback  解決できなかった場合の既定値
     */
    public Material material(String sourceKey, ConfigurationSection section,
                             String path, Material fallback) {
        String cacheKey = cacheKey(sourceKey, section, path);
        Material cached = materials.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String raw = read(section, path);
        Material resolved = resolveMaterial(raw);
        if (resolved == null) {
            warn("material", cacheKey, raw, fallback);
            resolved = fallback;
        }
        if (resolved != null) {
            materials.put(cacheKey, resolved);
        }
        return resolved;
    }

    /**
     * サウンド ID を解決してキャッシュする。
     * <p>
     * 既存の呼び出しとの互換用。{@code sourceKey} なしで呼んだ場合は
     * キャッシュキーのファイル部分が空文字になる。
     */
    public Sound sound(ConfigurationSection section, String path, Sound fallback) {
        return sound("", section, path, fallback);
    }

    /**
     * サウンド ID を解決してキャッシュする。
     * <p>
     * キャッシュキーは {@code sourceKey|セクションパス|path}。同名セクションを持つ
     * 別ファイルの設定が衝突しないよう、必ずファイル識別子を渡すこと。
     *
     * @param sourceKey 設定ファイルの識別子（例: {@code SystemData/combat_settings.yml}）
     * @param section   設定セクション
     * @param path      セクションからの相対キー
     * @param fallback  解決できなかった場合の既定値
     */
    public Sound sound(String sourceKey, ConfigurationSection section,
                       String path, Sound fallback) {
        String cacheKey = cacheKey(sourceKey, section, path);
        Sound cached = sounds.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String raw = read(section, path);
        Sound resolved = resolveSound(raw);
        if (resolved == null) {
            warn("sound", cacheKey, raw, fallback);
            resolved = fallback;
        }
        if (resolved != null) {
            sounds.put(cacheKey, resolved);
        }
        return resolved;
    }

    /**
     * パーティクル名を解決してキャッシュする。
     * <p>
     * 既存の呼び出しとの互換用。{@code sourceKey} なしで呼んだ場合は
     * キャッシュキーのファイル部分が空文字になる。
     */
    public Particle particle(ConfigurationSection section, String path, Particle fallback) {
        return particle("", section, path, fallback);
    }

    /**
     * パーティクル名を解決してキャッシュする。
     *
     * @param sourceKey 設定ファイルの識別子（例: {@code SystemData/portal_settings.yml}）
     * @param section   設定セクション
     * @param path      セクションからの相対キー
     * @param fallback  解決できなかった場合の既定値
     */
    public Particle particle(String sourceKey, ConfigurationSection section,
                             String path, Particle fallback) {
        String cacheKey = cacheKey(sourceKey, section, path);
        Particle cached = particles.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String raw = read(section, path);
        Particle resolved = resolveParticle(raw);
        if (resolved == null) {
            warn("particle", cacheKey, raw, fallback);
            resolved = fallback;
        }
        if (resolved != null) {
            particles.put(cacheKey, resolved);
        }
        return resolved;
    }

    /**
     * Material 名を解決する（ログ・キャッシュなし）。
     *
     * @return 解決した Material。空・不正な場合は {@code null}
     */
    public static Material resolveMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim());
        return material == null || material.isAir() || !material.isItem() ? null : material;
    }

    /**
     * サウンド ID（例: {@code ui.button.click}）を解決する（ログ・キャッシュなし）。
     * <p>
     * レジストリキー形式と Enum 定数形式（例: {@code UI_BUTTON_CLICK}）の両方を受け付ける。
     *
     * @return 解決した Sound。空・不正な場合は {@code null}
     */
    public static Sound resolveSound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();
        Sound sound = lookupSound(value);
        if (sound != null) {
            return sound;
        }

        // Enum 定数形式で書かれている場合も Minecraft のレジストリキーへ変換して解決する。
        String enumStyle = value.toLowerCase(Locale.ROOT).replace('_', '.');
        return lookupSound(enumStyle);
    }

    private static Sound lookupSound(String value) {
        NamespacedKey key = NamespacedKey.fromString(value.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.SOUNDS.get(key);
    }

    /**
     * パーティクル名を解決する（ログ・キャッシュなし）。
     *
     * @return 解決した Particle。空・不正な場合は {@code null}
     */
    public static Particle resolveParticle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String cacheKey(String sourceKey, ConfigurationSection section, String path) {
        String source = sourceKey == null ? "" : sourceKey;
        String base = section == null ? "" : section.getCurrentPath();
        return source + "|" + base + "|" + path;
    }

    private static String read(ConfigurationSection section, String path) {
        if (section == null || path == null) {
            return null;
        }
        Object value = section.get(path);
        return value == null ? null : String.valueOf(value);
    }

    private void warn(String type, String cacheKey, String raw, Object fallback) {
        if (plugin == null) {
            return;
        }
        String reason = raw == null || raw.isBlank()
                ? "キーが未設定"
                : "不正な値 '" + raw + "'";
        plugin.getLogger().warning("設定 " + type + " の " + reason
                + " を検出しました: " + cacheKey
                + " - 既定値 " + fallback + " を使用します。");
    }
}
