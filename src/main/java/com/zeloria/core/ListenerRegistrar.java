package com.zeloria.core;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bukkit イベントリスナーの登録・解除を一元管理する。
 * <p>
 * スキル基盤では「スキルごとにリスナーを作らない」方針を採るため、
 * 登録処理が各所へ散らばらないよう本クラスへ集約する。
 * <p>
 * Paper の Lifecycle API は {@code LifecycleEvents.COMMANDS} などの
 * プラグイン初期化イベントを提供するが、Bukkit の {@link Listener} を登録する
 * ライフサイクルイベントは提供していない。そのためリスナー登録自体は
 * {@link org.bukkit.plugin.PluginManager} を使用しつつ、登録箇所をここ 1 か所に限定し、
 * 無効化時には {@link HandlerList#unregisterAll(Listener)} で確実に解除する。
 * これによりリロード・再初期化時にハンドラが二重登録されるのを防ぐ。
 */
public final class ListenerRegistrar {

    private final JavaPlugin plugin;
    private final List<Listener> listeners = new ArrayList<>();

    public ListenerRegistrar(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * リスナーを登録する。
     *
     * @param listener 登録するリスナー
     */
    public synchronized void register(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /**
     * 登録済みのリスナーをすべて解除する。多重呼び出しは安全。
     */
    public synchronized void unregisterAll() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
    }
}
