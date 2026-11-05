package com.zeloria.core;

import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code config/MessageData/} 配下のメッセージ設定を解決するサービス。
 * <p>
 * メッセージ本文は MiniMessage 形式で記述でき、{@code {time}} や {@code {layer_name}}
 * のような波括弧プレースホルダーは送信時に置換される。
 * 配置場所の例:
 * <ul>
 *     <li>{@code config/MessageData/gate.yml}（ポータル関連）</li>
 *     <li>{@code config/MessageData/skill.yml}（スキル関連）</li>
 *     <li>{@code config/MessageData/system.yml}（共通メッセージ）</li>
 * </ul>
 * 指定ファイルにキーが無い場合は {@code system.yml} をフォールバックとして参照する。
 * <p>
 * 読み込み済み設定は {@link YamlLoader} がスレッドセーフにキャッシュしているため、
 * 本クラスは非同期スレッドから呼び出しても安全。
 */
public class MessageService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** {@code {placeholder}} 形式のプレースホルダー。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    /** フォールバック先のメッセージファイル。 */
    private static final String FALLBACK_FILE = "system";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    public MessageService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
    }

    /**
     * 指定ファイル・キーの生メッセージを取得する。
     *
     * @param file メッセージファイル名（例: {@code gate}）
     * @param key  メッセージキー（例: {@code portal.cooldown}）
     * @return 生メッセージ。見つからない場合は空
     */
    public Optional<String> findRaw(String file, String key) {
        String value = lookup(file, key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * 指定ファイル・キーの生メッセージを取得する。
     *
     * @param file         メッセージファイル名
     * @param key          メッセージキー
     * @param defaultValue 見つからない場合の既定値
     * @return 生メッセージ
     */
    public String raw(String file, String key, String defaultValue) {
        String value = lookup(file, key);
        return value == null ? defaultValue : value;
    }

    /**
     * プレースホルダーを置換した MiniMessage 文字列を返す。
     *
     * @param template     テンプレート（MiniMessage）
     * @param placeholders プレースホルダー（キーは波括弧を除いた名前）
     * @return 置換後の文字列
     */
    public String format(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return template;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.get(matcher.group(1));
            if (value == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                // 値に含まれる MiniMessage タグを無効化し、意図しない装飾を防ぐ。
                matcher.appendReplacement(result, Matcher.quoteReplacement(MINI.escapeTags(value)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * テンプレートを MiniMessage として解決する。
     *
     * @param template     MiniMessage テンプレート
     * @param placeholders プレースホルダー
     * @return 解決済みコンポーネント
     */
    public Component deserialize(String template, Map<String, String> placeholders) {
        return MINI.deserialize(format(template, placeholders));
    }

    /**
     * 指定ファイル・キーのメッセージを解決する。
     *
     * @param file         メッセージファイル名
     * @param key          メッセージキー
     * @param placeholders プレースホルダー
     * @return 解決済みコンポーネント。見つからない場合は空
     */
    public Optional<Component> component(String file, String key, Map<String, String> placeholders) {
        return findRaw(file, key).map(template -> deserialize(template, placeholders));
    }

    /**
     * 指定ファイル・キーのメッセージを送信する。見つからない場合は何もしない。
     *
     * @param recipient    送信先
     * @param file         メッセージファイル名
     * @param key          メッセージキー
     * @param placeholders プレースホルダー
     */
    public void send(CommandSender recipient, String file, String key, Map<String, String> placeholders) {
        if (recipient == null) {
            return;
        }
        component(file, key, placeholders).ifPresent(recipient::sendMessage);
    }

    /**
     * 指定ファイル・キーのメッセージをアクションバーへ表示する。
     * 見つからない場合は何もしない。
     *
     * @param player       対象プレイヤー
     * @param file         メッセージファイル名
     * @param key          メッセージキー
     * @param placeholders プレースホルダー
     */
    public void actionBar(Player player, String file, String key, Map<String, String> placeholders) {
        if (player == null) {
            return;
        }
        component(file, key, placeholders).ifPresent(component -> player.sendActionBar(component));
    }

    /**
     * {@code system.yml} の共通プレフィックスを取得する。
     */
    public Component prefix() {
        return deserialize(raw(FALLBACK_FILE, "prefix", ""), Map.of());
    }

    private String lookup(String file, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        FileConfiguration config = yamlLoader.getMessageConfig(file);
        if (config != null) {
            String value = config.getString(key);
            if (value != null) {
                return value;
            }
        }

        if (file != null && !FALLBACK_FILE.equalsIgnoreCase(file)) {
            FileConfiguration fallback = yamlLoader.getMessageConfig(FALLBACK_FILE);
            if (fallback != null) {
                return fallback.getString(key);
            }
        }
        return null;
    }
}
