package com.zeloria.party;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * パーティ 1 件分の状態をメモリ上で保持する。
 * <p>
 * メンバーは参加順を保つ {@link LinkedHashSet} で管理し、
 * リーダー脱退時に「最古参メンバー」へ移譲できるようにしている。
 * 永続化は行わず、{@link PartyManager} がサーバー稼働中のみ保持する。
 */
public final class Party {

    /** パーティ名（表示用。大文字小文字は作成時のまま保持する）。 */
    private final String name;

    /** メンバーの UUID（リーダーを含む）。イテレーション順は参加順。 */
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

    /** 現在のリーダーの UUID。 */
    private UUID leaderId;

    /**
     * リーダー 1 人だけが所属する状態でパーティを生成する。
     *
     * @param name     パーティ名
     * @param leaderId 作成者（初代リーダー）の UUID
     */
    public Party(String name, UUID leaderId) {
        this.name = Objects.requireNonNull(name, "name");
        this.leaderId = Objects.requireNonNull(leaderId, "leaderId");
        this.members.add(leaderId);
    }

    /**
     * パーティ名を取得する。
     *
     * @return パーティ名
     */
    public String name() {
        return name;
    }

    /**
     * 現在のリーダーの UUID を取得する。
     *
     * @return リーダーの UUID
     */
    public UUID leaderId() {
        return leaderId;
    }

    /**
     * 指定プレイヤーがリーダーかどうかを判定する。
     *
     * @param playerId 判定するプレイヤーの UUID
     * @return リーダーであれば {@code true}
     */
    public boolean isLeader(UUID playerId) {
        return leaderId.equals(playerId);
    }

    /**
     * 指定プレイヤーがメンバーかどうかを判定する。
     *
     * @param playerId 判定するプレイヤーの UUID
     * @return メンバーであれば {@code true}
     */
    public boolean contains(UUID playerId) {
        return members.contains(playerId);
    }

    /**
     * メンバー数を返す（オフラインメンバーも含む）。
     *
     * @return メンバー数
     */
    public int size() {
        return members.size();
    }

    /**
     * メンバー一覧を参加順のスナップショットとして返す。
     *
     * @return メンバーの UUID 一覧（変更不可）
     */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    /**
     * メンバーを追加する。
     *
     * @param playerId 追加するプレイヤーの UUID
     * @return 新規追加された場合は {@code true}
     */
    public boolean addMember(UUID playerId) {
        return members.add(playerId);
    }

    /**
     * メンバーを削除する。リーダーの移譲は行わない。
     *
     * @param playerId 削除するプレイヤーの UUID
     * @return 削除された場合は {@code true}
     */
    public boolean removeMember(UUID playerId) {
        return members.remove(playerId);
    }

    /**
     * リーダーを参加順で最古参のメンバーへ移譲する。
     * <p>
     * リーダー以外のメンバーが残っていない場合は移譲せず空を返す。
     *
     * @return 新しいリーダーの UUID。移譲先がいない場合は空
     */
    public Optional<UUID> promoteOldestMember() {
        for (UUID memberId : members) {
            if (!leaderId.equals(memberId)) {
                leaderId = memberId;
                return Optional.of(memberId);
            }
        }
        return Optional.empty();
    }
}
