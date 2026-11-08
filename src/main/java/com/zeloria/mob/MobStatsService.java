package com.zeloria.mob;

import com.zeloria.core.Zeloria;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
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

    /** モブを識別する PDC キー（{@code AdminCommand} / {@code MobRewardListener} と同一）。 */
    public static final String MOB_ID_KEY = "mob_id";

    /**
     * スポーン時に決定したモブレベルを保持する PDC キー。
     * <p>
     * チャンク再読み込みでメモリ上のキャッシュが消えても、この値から
     * 同じレベル補正を再現できる。
     */
    public static final String MOB_LEVEL_KEY = "mob_level";

    private final Zeloria plugin;
    private final NamespacedKey mobIdKey;
    private final NamespacedKey mobLevelKey;

    /** エンティティ UUID → 実行時ステータス。 */
    private final Map<UUID, MobStats> stats = new ConcurrentHashMap<>();

    public MobStatsService(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobIdKey = new NamespacedKey(plugin, MOB_ID_KEY);
        this.mobLevelKey = new NamespacedKey(plugin, MOB_LEVEL_KEY);
    }

    /**
     * スポーン時にモブ定義を登録する（レベルは {@link MobData#baseLevel()}）。
     *
     * @param entity 対象エンティティ
     * @param data   モブ定義
     */
    public void attach(LivingEntity entity, MobData data) {
        attach(entity, data, data == null ? 1 : data.baseLevel());
    }

    /**
     * スポーン時にモブ定義とレベルを登録する。
     * <p>
     * スポナーが個別のレベルを指定した場合は、このメソッドで明示したレベルが
     * 使用される。レベルは {@value #MOB_LEVEL_KEY} として PDC にも保存し、
     * キャッシュ消失時の再生成に備える。
     *
     * @param entity 対象エンティティ
     * @param data   モブ定義
     * @param level  スポーンレベル（1 未満は 1 として扱う）
     */
    public void attach(LivingEntity entity, MobData data, int level) {
        if (entity == null || data == null) {
            return;
        }
        int normalizedLevel = Math.max(1, level);
        // プラグインがスポーンするモブは、子供・装備持ち・アイテム拾得などの
        // 偶発的な例外状態を持たせない。
        sanitize(entity);
        entity.getPersistentDataContainer().set(
                mobLevelKey, PersistentDataType.INTEGER, normalizedLevel);
        stats.put(entity.getUniqueId(), MobStats.from(data, normalizedLevel));
    }

    /**
     * スポーン直後のモブから例外状態を取り除く。
     * <p>
     * 子供（Baby）状態を解除し、アイテム拾得を無効化したうえで、装備
     * （メインハンド／オフハンド／防具 4 部位）をすべて空にする。対象は
     * {@link Ageable} / {@link Mob} に該当するモブのみで、それ以外の
     * エンティティには干渉しない。
     *
     * @param entity 対象エンティティ
     */
    public void sanitize(LivingEntity entity) {
        if (entity == null) {
            return;
        }

        // 子供状態を解除する（大人として扱う）。
        if (entity instanceof Ageable ageable) {
            ageable.setAdult();
        }
        // アイテム拾得を無効化する（LivingEntity#setCanPickupItems）。
        if (entity instanceof Mob mob) {
            mob.setCanPickupItems(false);
        }

        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.clear();
        }
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

        MobStats created = MobStats.from(found.get(), levelOf(living, found.get()));
        stats.put(living.getUniqueId(), created);
        return created;
    }

    /**
     * エンティティの PDC からスポーンレベルを取得する。
     * <p>
     * レベルが保存されていない（旧バージョンでスポーンした等）場合は
     * {@link MobData#baseLevel()} を返す。
     *
     * @param entity 対象エンティティ
     * @param data   モブ定義
     */
    private int levelOf(LivingEntity entity, MobData data) {
        Integer level = entity.getPersistentDataContainer()
                .get(mobLevelKey, PersistentDataType.INTEGER);
        return level == null ? data.baseLevel() : Math.max(1, level);
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
