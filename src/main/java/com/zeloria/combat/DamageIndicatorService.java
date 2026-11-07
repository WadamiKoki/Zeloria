package com.zeloria.combat;

import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ダメージ確定時に被弾エンティティの頭上へ数値を表示するフローティングダメージテキスト。
 * <p>
 * {@link TextDisplay} を 10 tick（0.5 秒）だけ表示し、自動で削除する。
 * 負荷対策として 1 tick あたりの生成数を {@value #MAX_PER_TICK} 個に制限し、
 * 生成した表示は {@link UUID} で追跡して確実に削除する。
 * <p>
 * 表示はメインスレッド専用。メインスレッド以外から呼ばれた場合は
 * 次の tick にメインスレッドで実行するよう予約する。
 */
public final class DamageIndicatorService {

    /** 1 tick あたりに生成するダメージインジケーターの上限。 */
    private static final int MAX_PER_TICK = 10;

    /** 表示を維持する tick 数（0.5 秒）。 */
    private static final long LIFETIME_TICKS = 10L;

    /** テキストの拡大率。 */
    private static final float SCALE = 0.8f;

    /** 水平方向のばらつき幅（-0.8 〜 +0.8）。 */
    private static final double HORIZONTAL_SPREAD = 1.6;

    /** エンティティ上端からの表示オフセット。 */
    private static final double Y_OFFSET = 0.3;

    /** PDC のキー（zeloria:damage_indicator）。 */
    private static final String PDC_KEY = "damage_indicator";

    private final Zeloria plugin;
    private final NamespacedKey indicatorKey;

    /** 追跡中のダメージインジケーター（エンティティ UUID）。 */
    private final Set<UUID> activeDisplays = new HashSet<>();

    /** 現在 tick で生成した個数。 */
    private int spawnedThisTick;

    /** 次 tick でのカウンタリセットが予約済みかどうか。 */
    private boolean tickResetScheduled;

    public DamageIndicatorService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.indicatorKey = new NamespacedKey(plugin, PDC_KEY);
    }

    /**
     * 対象エンティティの頭上へダメージ数値を表示する。
     *
     * @param target   被弾したエンティティ
     * @param damage   確定したダメージ（0 以下は表示しない）
     * @param critical クリティカルかどうか（色・太字が変わる）
     */
    public void showDamage(Entity target, double damage, boolean critical) {
        if (target == null || damage <= 0.0) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> showDamage(target, damage, critical));
            return;
        }
        if (!reserveSlot()) {
            return;
        }

        World world = target.getWorld();
        BoundingBox box = target.getBoundingBox();
        double topY = box.getMaxY();
        double offsetX = (Math.random() - 0.5) * HORIZONTAL_SPREAD;
        double offsetZ = (Math.random() - 0.5) * HORIZONTAL_SPREAD;
        Location spawnLoc = new Location(world,
                target.getLocation().getX() + offsetX,
                topY + Y_OFFSET,
                target.getLocation().getZ() + offsetZ);

        TextDisplay display;
        try {
            display = world.spawn(spawnLoc, TextDisplay.class);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(
                    "ダメージインジケーターの生成に失敗しました: " + e.getMessage());
            return;
        }

        display.text(format(damage, critical));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf(),
                new Vector3f(SCALE, SCALE, SCALE),
                new Quaternionf()));
        display.getPersistentDataContainer().set(indicatorKey, PersistentDataType.BYTE, (byte) 1);

        UUID id = display.getUniqueId();
        activeDisplays.add(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(id), LIFETIME_TICKS);
    }

    /**
     * PDC にダメージインジケーターのキーを持つ残存表示を一括削除する（サーバー停止時用）。
     */
    public void shutdown() {
        for (UUID id : new ArrayList<>(activeDisplays)) {
            remove(id);
        }
        activeDisplays.clear();

        // UUID から解決できなかった残存表示も、読み込み済みワールドから走査して削除する。
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay display && isDamageIndicator(display)) {
                    display.remove();
                }
            }
        }
    }

    /**
     * 現在追跡中のダメージインジケーター数を返す。
     *
     * @return 追跡中の表示数
     */
    public int getActiveCount() {
        return activeDisplays.size();
    }

    private boolean reserveSlot() {
        if (spawnedThisTick >= MAX_PER_TICK) {
            return false;
        }
        spawnedThisTick++;
        if (!tickResetScheduled) {
            tickResetScheduled = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                spawnedThisTick = 0;
                tickResetScheduled = false;
            });
        }
        return true;
    }

    private void remove(UUID id) {
        activeDisplays.remove(id);
        Entity entity = Bukkit.getEntity(id);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private boolean isDamageIndicator(TextDisplay display) {
        return display.getPersistentDataContainer().has(indicatorKey, PersistentDataType.BYTE);
    }

    private static Component format(double damage, boolean critical) {
        String value = Long.toString(Math.max(1L, Math.round(damage)));
        if (critical) {
            return Component.text(value, NamedTextColor.RED).decorate(TextDecoration.BOLD);
        }
        return Component.text(value, NamedTextColor.WHITE);
    }
}
