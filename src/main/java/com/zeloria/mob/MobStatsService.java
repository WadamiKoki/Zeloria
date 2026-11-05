package com.zeloria.mob;

import com.zeloria.core.Zeloria;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * カスタムモブの {@link MobStats} をエンティティ UUID ごとに管理するサービス。
 * <p>
 * 対象は {@code /admin mob spawn} が {@code zeloria:mob_id} を付与した
 * {@link MobData} 由来のモブのみ。バニラモブや他プラグインのモブは
 * {@link #get(Entity)} が {@code null} を返し、カスタムダメージ計算の対象外となる。
 * <p>
 * スポーン時に {@link #attach(LivingEntity, MobData)} で登録し、未登録の場合は
 * PDC のモブ ID から {@link MobConfigService} を引いて遅延生成する。
 * エンティティ死亡・チャンクアンロード時に破棄する必要はなく、UUID キーのため
 * 新しいエンティティと衝突しない。プラグイン停止時に {@link #clearAll()} で破棄する。
 */
public final class MobStatsService {

    /** モブを識別する PDC キー（{@code AdminCommand} / {@code LevelListener} と同一）。 */
    public static final String MOB_ID_KEY = "mob_id";

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;

    /** エンティティ UUID → 実行時ステータス。 */
    private final Map<UUID, MobStats> stats = new ConcurrentHashMap<>();

    public MobStatsService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
    }

    /**
     * スポーン時にモブ定義を登録する。
     *
     * @param entity 対象エンティティ
     * @param data   モブ定義
     */
    public void attach(LivingEntity entity, MobData data) {
        if (entity == null || data == null) {
            return;
        }
        stats.put(entity.getUniqueId(), MobStats.from(data));
    }

    /**
     * エンティティの実行時ステータスを取得する。
     * <p>
     * カスタムモブでない場合は {@code null} を返す。未登録のカスタムモブは
     * PDC のモブ ID から定義を引き、次回以降のためにキャッシュする。
     *
     * @param entity 対象エンティティ
     * @return 実行時ステータス。カスタムモブでない場合は {@code null}
     */
    public MobStats get(Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return null;
        }

        MobStats cached = stats.get(living.getUniqueId());
        if (cached != null) {
            return cached;
        }

        String mobId = living.getPersistentDataContainer()
                .get(mobIdKey, PersistentDataType.STRING);
        if (mobId == null || mobId.isBlank()) {
            return null;
        }

        MobConfigService configService = plugin.getMobConfigService();
        Optional<MobData> found = configService == null
                ? Optional.empty()
                : configService.find(mobId);
        if (found.isEmpty()) {
            return null;
        }

        MobStats created = MobStats.from(found.get());
        stats.put(living.getUniqueId(), created);
        return created;
    }

    /**
     * エンティティがカスタムモブか判定する。
     */
    public boolean isCustomMob(Entity entity) {
        return get(entity) != null;
    }

    /**
     * 指定エンティティの登録を破棄する（死亡時など）。
     */
    public void remove(UUID entityId) {
        if (entityId != null) {
            stats.remove(entityId);
        }
    }

    /**
     * すべての登録を破棄する（プラグイン停止時用）。
     */
    public void clearAll() {
        stats.clear();
    }
}
