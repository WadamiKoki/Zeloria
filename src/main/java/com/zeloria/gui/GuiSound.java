package com.zeloria.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * GUI 操作時に再生する効果音の設定。
 * <p>
 * {@code config/gui/menu.yml} / {@code inventory.yml} の {@code sounds} セクションと
 * 1 対 1 で対応する。{@code key} は Bukkit のサウンドキー
 * （例: {@code ui.button.click}）を保持する。
 *
 * @param key    サウンドキー
 * @param volume 音量（0.0 以上）
 * @param pitch  ピッチ（0.5〜2.0 を推奨）
 */
public record GuiSound(String key, float volume, float pitch) {

    /** 再生不可を表す空の効果音。 */
    public static final GuiSound NONE = new GuiSound("", 0.0f, 1.0f);

    public GuiSound {
        key = key == null ? "" : key.trim();
        volume = Math.max(0.0f, volume);
        pitch = pitch <= 0.0f ? 1.0f : pitch;
    }

    /**
     * 設定セクションから効果音を生成する。
     * <p>
     * セクションが {@code null} または {@code key} が空の場合は {@code fallback} を返す。
     *
     * @param section  設定セクション
     * @param fallback 読み込めなかった場合の代替値
     * @return 効果音設定
     */
    public static GuiSound from(ConfigurationSection section, GuiSound fallback) {
        if (section == null) {
            return fallback;
        }

        String key = section.getString("key");
        if (key == null || key.isBlank()) {
            return fallback;
        }

        float volume = (float) section.getDouble("volume", fallback == null ? 1.0 : fallback.volume());
        float pitch = (float) section.getDouble("pitch", fallback == null ? 1.0 : fallback.pitch());
        return new GuiSound(key, volume, pitch);
    }

    /**
     * キーが設定されているか判定する。
     */
    public boolean isPresent() {
        return !key.isBlank();
    }

    /**
     * プレイヤーの現在位置で効果音を再生する。
     *
     * @param player 対象プレイヤー（{@code null} の場合は何もしない）
     */
    public void play(Player player) {
        if (player != null && isPresent()) {
            player.playSound(player.getLocation(), key, volume, pitch);
        }
    }
}
