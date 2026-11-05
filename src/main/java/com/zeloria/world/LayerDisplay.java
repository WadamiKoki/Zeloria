package com.zeloria.world;

import org.bukkit.configuration.ConfigurationSection;

/**
 * レイヤー入場時の演出設定（Title / Subtitle / Sound）。
 * <p>
 * {@code LayerData} の {@code display} セクションに対応する。
 * <pre>
 * display:
 *   title: "&lt;gradient:#00c6ff:#0072ff&gt;中央都市&lt;/gradient&gt;"
 *   subtitle: "&lt;gray&gt;ようこそ、{player} さん&lt;/gray&gt;"
 *   fade_in: 10
 *   stay: 50
 *   fade_out: 10
 *   sound: "block.note_block.pling"
 *   volume: 1.0
 *   pitch: 1.2
 * </pre>
 * {@code title} / {@code subtitle} は MiniMessage 形式で記述する。
 * {@code fade_in} / {@code stay} / {@code fade_out} はティック単位。
 *
 * @param title   メインタイトル（MiniMessage）
 * @param subtitle サブタイトル（MiniMessage）
 * @param fadeIn  フェードイン（ティック）
 * @param stay    表示維持（ティック）
 * @param fadeOut フェードアウト（ティック）
 * @param sound   再生するサウンド名（空文字なら再生しない）
 * @param volume  音量
 * @param pitch   ピッチ
 */
public record LayerDisplay(
        String title,
        String subtitle,
        int fadeIn,
        int stay,
        int fadeOut,
        String sound,
        float volume,
        float pitch
) {

    /** 演出未設定時の既定値。 */
    public static final LayerDisplay EMPTY = new LayerDisplay("", "", 10, 40, 10, "", 1.0f, 1.0f);

    public LayerDisplay {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        sound = sound == null ? "" : sound;
        fadeIn = Math.max(0, fadeIn);
        stay = Math.max(0, stay);
        fadeOut = Math.max(0, fadeOut);
        volume = Math.max(0.0f, volume);
        pitch = Math.max(0.0f, pitch);
    }

    /**
     * {@code display} セクションから演出設定を読み込む。
     *
     * @param section {@code display} セクション（{@code null} 可）
     * @return 演出設定。セクションが無い場合は {@link #EMPTY}
     */
    public static LayerDisplay fromConfig(ConfigurationSection section) {
        if (section == null) {
            return EMPTY;
        }
        return new LayerDisplay(
                section.getString("title", EMPTY.title()),
                section.getString("subtitle", EMPTY.subtitle()),
                section.getInt("fade_in", EMPTY.fadeIn()),
                section.getInt("stay", EMPTY.stay()),
                section.getInt("fade_out", EMPTY.fadeOut()),
                section.getString("sound", EMPTY.sound()),
                (float) section.getDouble("volume", EMPTY.volume()),
                (float) section.getDouble("pitch", EMPTY.pitch())
        );
    }

    public boolean hasTitle() {
        return !title.isBlank();
    }

    public boolean hasSubtitle() {
        return !subtitle.isBlank();
    }

    public boolean hasSound() {
        return !sound.isBlank();
    }
}
