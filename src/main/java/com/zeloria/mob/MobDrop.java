package com.zeloria.mob;

/**
 * モブ撃破時のドロップ定義。
 * <p>
 * {@code MobData} の YAML に次の形式で記述する。
 * <pre>
 * drops:
 *   - itemId: "goblin_ear"
 *     amount: 1
 *     chance: 0.5
 * </pre>
 *
 * @param itemId アイテム ID
 * @param amount 付与数（1〜2,147,483,647）
 * @param chance 抽選確率（0.0〜1.0）
 */
public record MobDrop(String itemId, long amount, double chance) {

    public MobDrop {
        itemId = itemId == null ? "" : itemId.trim();
        amount = Math.max(1L, Math.min((long) Integer.MAX_VALUE, amount));
        chance = Math.max(0.0, Math.min(1.0, chance));
    }
}
