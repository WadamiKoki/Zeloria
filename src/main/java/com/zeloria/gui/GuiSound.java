package com.zeloria.gui;

import com.zeloria.core.ConfigCache;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * GUI 操作時に再生する効果音の設定。
 * <p>
 * {@code config/GuiData/menu.yml} / {@code inventory.yml} / {@code class_tree.yml} の
 * {@code sounds} セクションと 1 対 1 で対応する。{@code key} は Bukkit のサウンドキー
 * （例: {@code ui.button.click}）を保持し、読み込み時に {@link Sound} へ解決してキャッシュする。
 *
 * @param key    サウンドキー（ログ・診断用）
 * @param sound  解決済みの Sound。再生不可の場合は {@code null}
 * @param volume 音量（0.0 以上）
 * @param pitch  ピッチ（0.5〜2.0 を推奨）
 */
public record GuiSound(String key, Sound sound, float volume, float pitch) {

    /** 再生不可を表す空の効果音。 */
    public static final GuiSound NONE = new GuiSound("", null, 0.0f, 1.0f);

    public GuiSound {
        key = key == null ? "" : key.trim();
        volume = Math.max(0.0f, volume);
        pitch = pitch <= 0.0f ? 1.0f : pitch;
    }

    /**
     * サウンドキーから効果音を生成する。
     */
    public GuiSound(String key, float volume, float pitch) {
        this(key, ConfigCache.resolveSound(key), volume, pitch);
    }

    /**
     * 設定セクションから効果音を生成する（ログ・キャッシュなし）。
     * <p>
     * セクションが {@code null}、または {@code key} が空・不正な場合は {@code fallback} を返す。
     *
     * @param section  設定セクション
     * @param fallback 読み込めなかった場合の代替値
     * @return 効果音設定
     */
    public static GuiSound from(ConfigurationSection section, GuiSound fallback) {
        return from(section, fallback, null);
    }

    /**
     * 設定セクションから効果音を生成する。
     * <p>
     * {@code cache} が指定された場合は解決済み Sound を再利用し、不正値の警告も行う。
     * セクションが {@code null}、または {@code key} が空・不正な場合は {@code fallback} を返す。
     *
     * @param section  設定セクション
     * @param fallback 読み込めなかった場合の代替値
     * @param cache    設定キャッシュ（{@code null} の場合は都度解決）
     * @return 効果音設定
     */
    public static GuiSound from(ConfigurationSection section, GuiSound fallback, ConfigCache cache) {
        return from(section, fallback, cache, "");
    }

    /**
     * 設定セクションから効果音を生成する。
     * <p>
     * {@code cache} が指定された場合は解決済み Sound を再利用し、不正値の警告も行う。
     * キャッシュキーの衝突を避けるため、{@code sourceKey} には設定ファイルの
     * 相対パスを渡すこと。
     *
     * @param section   設定セクション
     * @param fallback  読み込めなかった場合の代替値
     * @param cache     設定キャッシュ（{@code null} の場合は都度解決）
     * @param sourceKey 設定ファイルの識別子
     * @return 効果音設定
     */
    public static GuiSound from(ConfigurationSection section, GuiSound fallback,
                                ConfigCache cache, String sourceKey) {
        if (section == null) {
            return fallback;
        }

        String key = section.getString("key");
        if (key == null || key.isBlank()) {
            return fallback;
        }

        Sound fallbackSound = fallback == null ? null : fallback.sound();
        Sound sound = cache == null
                ? ConfigCache.resolveSound(key)
                : cache.sound(sourceKey, section, "key", fallbackSound);
        if (sound == null) {
            return fallback;
        }

        float volume = (float) section.getDouble("volume", fallback == null ? 1.0 : fallback.volume());
        float pitch = (float) section.getDouble("pitch", fallback == null ? 1.0 : fallback.pitch());
        return new GuiSound(key, sound, volume, pitch);
    }

    /**
     * 再生可能なサウンドが設定されているか判定する。
     */
    public boolean isPresent() {
        return sound != null;
    }

    /**
     * プレイヤーの現在位置で効果音を再生する。
     *
     * @param player 対象プレイヤー（{@code null} の場合は何もしない）
     */
    public void play(Player player) {
        if (player != null && isPresent()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }
}
