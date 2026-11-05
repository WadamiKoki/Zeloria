package com.zeloria.player;

import com.zeloria.combat.CombatStateService;
import com.zeloria.core.Zeloria;
import com.zeloria.item.EquipmentService;
import com.zeloria.world.LayerManager;
import com.zeloria.skill.CooldownService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤーのログインライフサイクルを処理するリスナー。
 * <ul>
 *     <li>ログイン前: DB からプレイヤーデータを読み込み、オンメモリキャッシュへ保持する</li>
 *     <li>参加時: キャッシュを確認（未取得なら非同期読み込み）し、RPG ステータス
 *         （移動速度）を反映したうえで共有 Hub のスポーンへ移動する</li>
 *     <li>死亡・リスポーン時: HP / MP の回復状態を更新する</li>
 *     <li>退出時: メインスレッドでスナップショットを生成し、MySQL へ非同期保存する。
 *         保存に成功した場合のみキャッシュを破棄し、失敗時は未保存データを保持して再試行に備える</li>
 * </ul>
 * 実際の読み書きは {@link PlayerService} に委譲し、本クラスはイベントの橋渡しに徹する。
 */
public class PlayerListener implements Listener {

    private final Zeloria plugin;

    public PlayerListener(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * ログイン前にプレイヤーデータを非同期で読み込み、キャッシュへ保持する。
     * <p>
     * このイベント自体が非同期スレッドで発火するため、{@code join()} で完了を待っても
     * メインスレッドをブロックしない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            plugin.getLogger().warning(
                    "PlayerService が初期化されていないため、データ読み込みをスキップしました: "
                            + event.getName());
            return;
        }

        playerService.loadBlocking(event.getUniqueId(), event.getName());
    }

    /**
     * 参加時にデータのキャッシュを保証し、共有 Hub レイヤーのスポーンへテレポートする。
     * <p>
     * 通常は {@link AsyncPlayerPreLoginEvent} で読み込み済みだが、DB 遅延などで
     * キャッシュが無い場合はこのタイミングで非同期読み込みを開始する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.joinMessage(null);

        Player player = event.getPlayer();
        recordLastLogin(player);
        PlayerData cached = PlayerData.getCached(player.getUniqueId());
        if (cached != null) {
            applyName(cached, player);
            applyStats(player);
            teleportToHub(player);
            return;
        }

        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            plugin.getLogger().warning(
                    "プレイヤーデータが取得できませんでした（DB 未接続の可能性）: " + player.getName());
            teleportToHub(player);
            return;
        }

        playerService.loadIfAbsent(player)
                .thenAccept(data -> runOnMainThread(player, () -> {
                    if (player.isOnline()) {
                        applyName(data, player);
                        applyStats(player);
                        teleportToHub(player);
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "プレイヤーデータの読み込みに失敗しました: " + player.getName()
                                    + " (" + rootMessage(throwable) + ")");
                    runOnMainThread(player, () -> {
                        if (player.isOnline()) {
                            teleportToHub(player);
                        }
                    });
                    return null;
                });
    }

    /**
     * 退出メッセージを非表示化し、データを MySQL へ非同期保存したうえでキャッシュを破棄する。
     * <p>
     * 保存クエリは {@code INSERT ... ON DUPLICATE KEY UPDATE} を使用する。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.quitMessage(null);

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // クールダウンは PlayerService が保存直前にシリアライズするため、
        // スナップショット作成後に破棄する（ここでは何もしない）。
        CombatStateService combatStateService = plugin.getCombatStateService();
        if (combatStateService != null) {
            combatStateService.clear(uuid);
        }

        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            if (plugin.getCooldownService() != null) {
                plugin.getCooldownService().clear(uuid);
            }
            PlayerData.removeCached(uuid);
            return;
        }

        playerService.saveAndEvict(uuid, player.getName())
                .exceptionally(throwable -> {
                    // PlayerService 側でログ出力済み。ここでは最終防衛の警告のみ行う。
                    plugin.getLogger().warning(
                            "プレイヤーデータの保存処理でエラーが発生しました: " + player.getName()
                                    + " (" + rootMessage(throwable) + ")");
                    return null;
                });

        // saveAndEvict はスナップショットを同期的に作成してから非同期保存するため、
        // この位置で破棄しても保存内容には影響しない。
        if (plugin.getCooldownService() != null) {
            plugin.getCooldownService().clear(uuid);
        }
    }

    /**
     * 死亡時に RPG ステータスの HP を 0 にし、戦闘中として記録する。
     * <p>
     * 死亡中は自然回復タスクの対象外となり、リスポーン時に全回復する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.handleDeath(event.getEntity());
        }
    }

    /**
     * リスポーン時に HP / MP を全回復し、戦闘記録を破棄する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.handleRespawn(event.getPlayer());
        }
    }

    /**
     * キャッシュ済みデータのステータス（移動速度など）をプレイヤーへ反映する。
     */
    private void applyStats(Player player) {
        EquipmentService equipmentService = plugin.getEquipmentService();
        if (equipmentService != null) {
            // 装備補正を適用したうえで WalkSpeed などを反映する。
            equipmentService.recompute(player);
            restoreCooldowns(player);
            return;
        }

        PlayerStatsService statsService = plugin.getPlayerStatsService();
        if (statsService != null) {
            statsService.apply(player);
        }
        restoreCooldowns(player);
    }

    /**
     * 退出時に保存したクールダウンを復元する。
     * <p>
     * 復元後に保存文字列を空にし、同一セッション内での二重復元を防ぐ
     * （次回退出時に最新の状態が改めて保存される）。
     *
     * @param player 参加したプレイヤー
     */
    private void restoreCooldowns(Player player) {
        CooldownService cooldownService = plugin.getCooldownService();
        PlayerData data = PlayerData.getCached(player.getUniqueId());
        if (cooldownService == null || data == null) {
            return;
        }

        cooldownService.restore(player.getUniqueId(), data.getCooldownEndAt());
        data.setCooldownEndAt("");
    }

    /**
     * 最終ログイン日時と接続インスタンス（ワールド名）を DB へ記録する。
     * <p>
     * {@code /admin info} が参照する接続履歴であり、データ本体の保存とは独立している。
     * 失敗してもログイン処理を妨げないよう、警告のみ出す。
     *
     * @param player 参加したプレイヤー
     */
    private void recordLastLogin(Player player) {
        PlayerService playerService = plugin.getPlayerService();
        if (playerService == null) {
            return;
        }
        String instance = player.getWorld() == null ? "" : player.getWorld().getName();
        playerService.getRepository().updateLastLogin(player.getUniqueId(), instance)
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "最終ログイン情報の更新に失敗しました: " + player.getName()
                                    + " (" + rootMessage(throwable) + ")");
                    return null;
                });
    }

    private void runOnMainThread(Player player, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static void applyName(PlayerData data, Player player) {
        if (data != null && !Objects.equals(data.getName(), player.getName())) {
            // 名前変更に追従しておく。
            data.setName(player.getName());
        }
    }

    /**
     * Hub レイヤーを取得（未ロード時は生成）し、スポーン地点へ安全にテレポートする。
     */
    private void teleportToHub(Player player) {
        LayerManager layerManager = plugin.getLayerManager();
        if (layerManager == null) {
            plugin.getLogger().severe("LayerManager が初期化されていません。Hub へテレポートできません。");
            return;
        }

        World hub = layerManager.getWorld(Zeloria.HUB_INSTANCE_ID);
        if (hub == null) {
            try {
                hub = layerManager.createLayer(Zeloria.HUB_TEMPLATE_ID, Zeloria.HUB_INSTANCE_ID);
            } catch (RuntimeException e) {
                plugin.getLogger().severe(
                        "Hub レイヤーの生成に失敗したためテレポートを中止しました: " + e.getMessage());
                return;
            }
        }

        // ブロック中央・水平向きで着地させ、足元のブロックへの埋まりを避ける。
        Location spawn = hub.getSpawnLocation().toBlockLocation();
        spawn.add(0.5, 0.0, 0.5);
        spawn.setYaw(0.0f);
        spawn.setPitch(0.0f);

        String playerName = player.getName();
        player.teleportAsync(spawn)
                .thenAccept(success -> {
                    if (!success) {
                        plugin.getLogger().warning("Hub へのテレポートに失敗しました: " + playerName);
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().warning(
                            "Hub へのテレポート中にエラーが発生しました: " + playerName
                                    + " (" + rootMessage(throwable) + ")");
                    return null;
                });
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
