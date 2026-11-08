package com.zeloria.player;

import com.zeloria.core.Zeloria;
import com.zeloria.gathering.GatheringType;
import com.zeloria.skill.ClassManager;
import com.zeloria.world.LayerManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * レベルアップ時のチャット・パーティクル・効果音通知を担当するユーティリティ。
 * <p>
 * 通知先は各プレイヤーの {@link PlayerSettings} に従う。
 * 自分自身への通知は {@link PlayerSettings#chatLogSelf()}、
 * 他プレイヤーへの通知は {@link PlayerSettings#chatLogOthers()} で制御する。
 * 効果音はレジストリキー（{@code entity.player.levelup}）で再生する。
 * 通知範囲は同じレイヤーインスタンス内に限定し、別レイヤーのプレイヤーには送らない。
 */
public final class LevelUpNotifier {

    private static final Key LEVEL_UP_SOUND = Key.key("entity.player.levelup");

    private LevelUpNotifier() {
    }

    /**
     * クラスのレベルアップを通知する。
     *
     * @param plugin   プラグイン
     * @param data     対象プレイヤーデータ
     * @param newLevel 到達したレベル
     */
    public static void notifyClass(Zeloria plugin, PlayerData data, int newLevel) {
        if (plugin == null || data == null) {
            return;
        }
        broadcast(plugin, data, classLabel(plugin, data), newLevel);
    }

    /**
     * ギャザリングのレベルアップを通知する。
     *
     * @param plugin   プラグイン
     * @param data     対象プレイヤーデータ
     * @param type     ギャザリング種別
     * @param newLevel 到達したレベル
     */
    public static void notifyGathering(
            Zeloria plugin, PlayerData data, GatheringType type, int newLevel) {
        if (plugin == null || data == null || type == null) {
            return;
        }
        broadcast(plugin, data, Component.text(type.displayName(), NamedTextColor.AQUA), newLevel);
    }

    private static void broadcast(
            Zeloria plugin, PlayerData data, Component label, int newLevel) {
        Component message = Component.text(data.getName(), NamedTextColor.WHITE)
                .append(Component.text(" は ", NamedTextColor.GRAY))
                .append(label)
                .append(Component.text(" Lv." + newLevel + " に到達しました", NamedTextColor.YELLOW));

        Player source = data.getUuid() == null ? null : Bukkit.getPlayer(data.getUuid());
        if (source == null) {
            return;
        }
        boolean selfEnabled = data.getSettings().chatLogSelf();
        boolean notified = false;
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!isSameLayer(plugin, source, recipient)) {
                continue;
            }
            boolean self = recipient.getUniqueId().equals(source.getUniqueId());
            PlayerSettings settings = self ? data.getSettings() : settingsOf(recipient);
            boolean enabled = self ? settings.chatLogSelf() : settings.chatLogOthers();
            if (!enabled) {
                continue;
            }

            recipient.sendMessage(message);
            recipient.playSound(Sound.sound(
                    LEVEL_UP_SOUND, Sound.Source.PLAYER, 1.0f, 1.0f));
            notified = true;
        }

        // 自分の通知を OFF にしている場合は、自分向けのパーティクルも出さない。
        if (notified && selfEnabled && source != null && source.isOnline()) {
            source.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    source.getLocation().add(0.0, 1.0, 0.0),
                    30, 0.5, 0.8, 0.5, 0.0);
        }
    }

    private static PlayerSettings settingsOf(Player player) {
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        return data == null ? PlayerSettings.DEFAULT : data.getSettings();
    }

    /**
     * 通知元と通知先が同じレイヤーインスタンスにいるか判定する。
     * <p>
     * {@link LayerManager#getCurrentLayerId(Player)} でレイヤーインスタンス ID を比較する。
     * 両者ともレイヤー外（メインワールド等）の場合は同じワールドかどうかで判定する。
     * 片方だけがレイヤー内の場合は、別レイヤー扱いとして通知しない。
     */
    private static boolean isSameLayer(Zeloria plugin, Player source, Player recipient) {
        if (source == null || recipient == null) {
            return false;
        }
        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager != null) {
            String sourceLayer = layerManager.getCurrentLayerId(source);
            String recipientLayer = layerManager.getCurrentLayerId(recipient);
            if (sourceLayer != null || recipientLayer != null) {
                return sourceLayer != null && sourceLayer.equals(recipientLayer);
            }
        }
        return source.getWorld().equals(recipient.getWorld());
    }

    private static Component classLabel(Zeloria plugin, PlayerData data) {
        String classId = data.getClassData().currentClassIdOrNull();
        if (classId == null || classId.isBlank()) {
            return Component.text("クラス", NamedTextColor.GOLD);
        }
        ClassManager classManager = plugin.getClassManager();
        if (classManager == null) {
            return Component.text(classId, NamedTextColor.GOLD);
        }
        return classManager.find(classId)
                .map(definition -> definition.displayName())
                .orElseGet(() -> Component.text(classId, NamedTextColor.GOLD));
    }
}
