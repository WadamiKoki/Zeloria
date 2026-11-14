package com.zeloria.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;

/**
 * 表示用コンポーネントの共通整形ユーティリティ。
 * <p>
 * Minecraft はカスタムアイテム名・Lore を自動的に斜体で描画するため、
 * 本プラグインでは表示名・説明文・GUI タイトルを常に非イタリックに統一する。
 */
public final class TextComponents {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private TextComponents() {
    }

    /**
     * MiniMessage 文字列を解決し、斜体を無効化したコンポーネントを返す。
     *
     * @param miniMessage MiniMessage 文字列
     * @return 斜体を無効化したコンポーネント
     */
    public static Component mini(String miniMessage) {
        return mini(MINI, miniMessage);
    }

    /**
     * 指定パーサーで MiniMessage 文字列を解決し、斜体を無効化したコンポーネントを返す。
     * <p>
     * Geyser（Bedrock）ではアイテム名・Lore の {@code italic=false} が
     * 伝播しない場合があるため、MiniMessage の {@code <!italic>} タグも
     * 併用して明示的に斜体を無効化する。
     *
     * @param parser      MiniMessage パーサー
     * @param miniMessage MiniMessage 文字列
     * @return 斜体を無効化したコンポーネント
     */
    public static Component mini(MiniMessage parser, String miniMessage) {
        MiniMessage resolved = parser == null ? MINI : parser;
        String message = miniMessage == null ? "" : miniMessage;
        if (!message.startsWith("<!italic>")) {
            message = "<!italic>" + message;
        }
        return noItalic(resolved.deserialize(message));
    }

    /**
     * コンポーネントの斜体を無効化する。
     *
     * @param component 対象コンポーネント
     * @return 斜体を無効化したコンポーネント
     */
    public static Component noItalic(Component component) {
        return component == null
                ? Component.empty()
                : component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 複数コンポーネントの斜体をまとめて無効化する。
     *
     * @param components 対象コンポーネント
     * @return 斜体を無効化したコンポーネント一覧
     */
    public static List<Component> noItalic(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return List.of();
        }
        return components.stream().map(TextComponents::noItalic).toList();
    }
}
