package com.zeloria.player;

import com.zeloria.core.EffectSettings;
import com.zeloria.core.Zeloria;
import com.zeloria.gathering.GatheringType;
import com.zeloria.skill.ClassManager;
import com.zeloria.skill.LevelService;
import com.zeloria.skill.LevelSettings;
import com.zeloria.world.LayerManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;

/**
 * レベルアップ時のチャット・パーティクル・効果音通知を担当するユーティリティ。
 * <p>
 * 通知先は各プレイヤーの {@link PlayerSettings} に従う。
 * 自分自身への通知は {@link PlayerSettings#chatLogSelf()}、
 * 他プレイヤーへの通知は {@link PlayerSettings#chatLogOthers()} で制御する。
 * これらのトグルは<b>チャットメッセージのみ</b>を制御し、パーティクルと効果音は
 * トグルの状態に関わらず常に発生させる。
 * 効果音とパーティクルは {@code SystemData/level_settings.yml} の
 * {@code level_up} セクションから読み込む。
 * 通知範囲は同じレイヤーインスタンス内に限定し、別レイヤーのプレイヤーには送らない。
 */
public final class LevelUpNotifier {

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

        LevelSettings.LevelUpSettings levelUp = levelUpSettings(plugin);
        EffectSettings.SoundEffect sound = levelUp.sound();
        Key soundKey = resolveSoundKey(sound);

        // チャットのみトグルで制御する。パーティクルと効果音は常に発生させる。
        boolean selfEnabled = data.getSettings().chatLogSelf();
        if (selfEnabled && source.isOnline()) {
            source.sendMessage(message);
        }
        if (source.isOnline()) {
            source.playSound(Sound.sound(
                    soundKey, Sound.Source.PLAYER, sound.volume(), sound.pitch()));
        }

        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.getUniqueId().equals(source.getUniqueId())) {
                continue;
            }
            if (!isSameLayer(plugin, source, recipient)) {
                continue;
            }

            PlayerSettings settings = settingsOf(recipient);
            if (settings.chatLogOthers()) {
                recipient.sendMessage(message);
            }
            // 他人用トグルはチャットのみを制御し、効果音は常に再生する。
            recipient.playSound(Sound.sound(
                    soundKey, Sound.Source.PLAYER, sound.volume(), sound.pitch()));
        }

        // 自分用トグルの状態に関わらず、レベルアップのパーティクルは常に表示する。
        if (source.isOnline()) {
            EffectSettings.ParticleEffect particle = levelUp.particle();
            source.getWorld().spawnParticle(particle.particle(),
                    source.getLocation().add(0.0, levelUp.particleYOffset(), 0.0),
                    particle.count(),
                    particle.offsetX(), particle.offsetY(), particle.offsetZ(),
                    particle.extra());
        }
    }

    /**
     * 設定済みサウンドの Adventure {@link Key} を解決する。
     * <p>
     * {@link org.bukkit.Sound#getKey()} は非推奨のため {@link Registry#SOUNDS} から
     * キーを取得する。レジストリに登録されていない場合は、YAML に書かれた
     * キー文字列へフォールバックする。
     */
    private static Key resolveSoundKey(EffectSettings.SoundEffect sound) {
        if (sound.sound() != null) {
            NamespacedKey key = Registry.SOUNDS.getKey(sound.sound());
            if (key != null) {
                return Key.key(key.getNamespace(), key.getKey());
            }
        }
        return Key.key(sound.key());
    }

    private static LevelSettings.LevelUpSettings levelUpSettings(Zeloria plugin) {
        LevelService levelService = plugin.getLevelService();
        if (levelService == null) {
            return LevelSettings.LevelUpSettings.DEFAULT;
        }
        return levelService.getSettings().levelUp();
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
