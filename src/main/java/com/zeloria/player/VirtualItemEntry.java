package com.zeloria.player;

/**
 * 仮想インベントリの 1 枠分のアイテムデータ。
 * <p>
 * 表示用の {@code ItemStack} は常に数量 1 とし、実際の所持数は {@link #amount()}
 * で管理する。DB では {@code BIGINT} 相当（Java では {@code long}）として保存するが、
 * 論理的な上限は {@link Integer#MAX_VALUE} とする。
 *
 * @param itemId         アイテム ID（{@code ItemData#id()}）
 * @param amount         論理的な所持数（1〜2,147,483,647）
 * @param serializedData 将来の拡張用シリアライズデータ（未使用時は空文字）
 */
public record VirtualItemEntry(String itemId, long amount, String serializedData) {

    /** 論理数量の上限（int 上限）。 */
    public static final long MAX_AMOUNT = Integer.MAX_VALUE;

    public VirtualItemEntry {
        itemId = itemId == null ? "" : itemId.trim();
        amount = Math.max(0L, Math.min(MAX_AMOUNT, amount));
        serializedData = serializedData == null ? "" : serializedData;
    }

    /**
     * 数量を指定してエントリを生成する。
     *
     * @param itemId アイテム ID
     * @param amount 所持数
     * @return エントリ
     */
    public static VirtualItemEntry of(String itemId, long amount) {
        return new VirtualItemEntry(itemId, amount, "");
    }

    /**
     * 数量を加算した新しいエントリを返す（上限でクランプ）。
     *
     * @param delta 加算量
     * @return 加算後のエントリ
     */
    public VirtualItemEntry plus(long delta) {
        return new VirtualItemEntry(itemId, amount + Math.max(0L, delta), serializedData);
    }

    /**
     * 数量を減算した新しいエントリを返す（0 未満にはならない）。
     *
     * @param delta 減算量
     * @return 減算後のエントリ
     */
    public VirtualItemEntry minus(long delta) {
        return new VirtualItemEntry(itemId, amount - Math.max(0L, delta), serializedData);
    }
}
