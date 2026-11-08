package com.zeloria.party;

import com.zeloria.core.MessageService;
import com.zeloria.core.Zeloria;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * パーティの作成・招待・参加・脱退・一覧表示をメモリ上で管理する。
 * <p>
 * 永続化は行わない。サーバー稼働中のみ状態を保持し、再起動で全消滅する。
 * ログアウト時もパーティ情報は保持し、名前だけを {@link #nameCache} へ控える。
 * {@code /party list} はこのキャッシュから名前を引くため、
 * {@code Bukkit#getOfflinePlayer(UUID)} による都度解決は行わない。
 * <p>
 * 操作結果の通知は {@link MessageService} の {@code MessageData/party.yml} を
 * 参照して送信する。YAML が未配置でも動作するよう、コード側にフォールバックを持つ。
 */
public final class PartyManager {

    /** パーティの最大人数（オフラインメンバーも枠に含む）。 */
    public static final int MAX_MEMBERS = 5;

    /** 招待の有効期限（ミリ秒）。 */
    private static final long INVITE_TIMEOUT_MILLIS = 60_000L;

    /** パーティ関連メッセージのファイル名（{@code MessageData/party.yml}）。 */
    private static final String MESSAGE_FILE = "party";

    private final Zeloria plugin;

    /** パーティ名（小文字）→ パーティ。重複判定を大文字小文字非依存にする。 */
    private final Map<String, Party> partiesByName = new HashMap<>();

    /** メンバーの UUID → 所属パーティ。 */
    private final Map<UUID, Party> partiesByMember = new HashMap<>();

    /** 招待されたプレイヤーの UUID → 最新の招待。上書きにより常に最新を採用する。 */
    private final Map<UUID, Invite> invites = new HashMap<>();

    /** ログアウト時のプレイヤー名キャッシュ（UUID → 名前）。 */
    private final Map<UUID, String> nameCache = new HashMap<>();

    public PartyManager(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * ログイン時にプレイヤー名をキャッシュし、所属パーティがあれば復帰を通知する。
     * <p>
     * パーティ情報はログアウト時も保持しているため、ここでは再登録のみを行う。
     *
     * @param player 参加したプレイヤー
     */
    public void handleJoin(Player player) {
        Objects.requireNonNull(player, "player");
        nameCache.put(player.getUniqueId(), player.getName());

        Party party = partiesByMember.get(player.getUniqueId());
        if (party != null) {
            send(player, "restore.joined", Map.of("party", party.name()));
        }
    }

    /**
     * ログアウト時にプレイヤー名をキャッシュする。パーティからは脱退させない。
     *
     * @param player 退出したプレイヤー
     */
    public void handleQuit(Player player) {
        Objects.requireNonNull(player, "player");
        nameCache.put(player.getUniqueId(), player.getName());
    }

    /**
     * 全データを破棄する。プラグイン無効化時に呼び出す。
     */
    public void clearAll() {
        partiesByName.clear();
        partiesByMember.clear();
        invites.clear();
        nameCache.clear();
    }

    /**
     * {@code /party} の使用方法を送信する。
     *
     * @param player 送信先プレイヤー
     */
    public void sendUsage(Player player) {
        send(player, "command.usage", Map.of());
    }

    /**
     * パーティを作成する。
     *
     * @param player 作成者（初代リーダー）
     * @param name   パーティ名
     */
    public void create(Player player, String name) {
        if (partiesByMember.containsKey(player.getUniqueId())) {
            send(player, "error.already_in_party", Map.of());
            return;
        }

        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            send(player, "error.name_required", Map.of());
            return;
        }

        String key = normalizeName(trimmed);
        if (partiesByName.containsKey(key)) {
            send(player, "error.name_taken", Map.of("party", trimmed));
            return;
        }

        Party party = new Party(trimmed, player.getUniqueId());
        partiesByName.put(key, party);
        partiesByMember.put(player.getUniqueId(), party);
        // 作成したプレイヤー宛ての招待は不要になるため破棄する。
        invites.remove(player.getUniqueId());
        nameCache.put(player.getUniqueId(), player.getName());

        send(player, "create.success",
                Map.of("party", party.name(), "max", String.valueOf(MAX_MEMBERS)));
    }

    /**
     * オンラインプレイヤーをパーティへ招待する。
     * <p>
     * 招待はパーティメンバーなら誰でも行えるが、リーダーがオフラインの間は実行できない。
     *
     * @param inviter    招待するプレイヤー
     * @param targetName 招待するオンラインプレイヤー名
     */
    public void invite(Player inviter, String targetName) {
        Party party = partiesByMember.get(inviter.getUniqueId());
        if (party == null) {
            send(inviter, "error.not_in_party", Map.of());
            return;
        }

        if (!isLeaderOnline(party)) {
            send(inviter, "error.leader_offline", Map.of());
            return;
        }

        Player target = findOnlinePlayer(targetName);
        if (target == null) {
            send(inviter, "error.target_offline",
                    Map.of("player", targetName == null ? "" : targetName));
            return;
        }

        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            send(inviter, "error.target_self", Map.of());
            return;
        }

        if (partiesByMember.containsKey(target.getUniqueId())) {
            send(inviter, "error.target_in_party", Map.of("player", target.getName()));
            return;
        }

        if (party.size() >= MAX_MEMBERS) {
            send(inviter, "error.party_full",
                    Map.of("party", party.name(), "max", String.valueOf(MAX_MEMBERS)));
            return;
        }

        purgeExpiredInvites();
        // 複数パーティから招待された場合は、最後に届いた招待だけを保持する。
        invites.put(target.getUniqueId(), new Invite(party, System.currentTimeMillis()));
        nameCache.put(target.getUniqueId(), target.getName());

        send(inviter, "invite.sent", Map.of("player", target.getName(), "party", party.name()));
        send(target, "invite.received", Map.of(
                "player", inviter.getName(),
                "party", party.name(),
                "seconds", String.valueOf(INVITE_TIMEOUT_MILLIS / 1000L)));
    }

    /**
     * 最新の招待を承認してパーティへ参加する。
     * <p>
     * 招待の発行から 60 秒経過している場合は無効として扱う。
     *
     * @param player 承認するプレイヤー
     */
    public void accept(Player player) {
        if (partiesByMember.containsKey(player.getUniqueId())) {
            send(player, "error.already_in_party", Map.of());
            return;
        }

        Invite invite = invites.get(player.getUniqueId());
        if (invite == null) {
            send(player, "error.no_invite", Map.of());
            return;
        }

        if (invite.isExpired(System.currentTimeMillis())) {
            invites.remove(player.getUniqueId());
            send(player, "error.invite_expired", Map.of());
            return;
        }

        Party party = invite.party();
        if (partiesByName.get(normalizeName(party.name())) != party) {
            // パーティが既に解散している場合は招待も無効。
            invites.remove(player.getUniqueId());
            send(player, "error.no_invite", Map.of());
            return;
        }

        if (!isLeaderOnline(party)) {
            send(player, "error.leader_offline", Map.of());
            return;
        }

        if (party.size() >= MAX_MEMBERS) {
            invites.remove(player.getUniqueId());
            send(player, "error.party_full",
                    Map.of("party", party.name(), "max", String.valueOf(MAX_MEMBERS)));
            return;
        }

        party.addMember(player.getUniqueId());
        partiesByMember.put(player.getUniqueId(), party);
        invites.remove(player.getUniqueId());
        nameCache.put(player.getUniqueId(), player.getName());

        send(player, "accept.success", Map.of("party", party.name()));
        broadcast(party, "accept.broadcast", Map.of("player", player.getName()));
    }

    /**
     * 現在のパーティから脱退する。
     * <p>
     * リーダーが脱退した場合は最古参メンバーへ移譲し、そのパーティの招待をすべて無効化する。
     * 最後のメンバーが脱退した場合はパーティを解散する。
     *
     * @param player 脱退するプレイヤー
     */
    public void leave(Player player) {
        UUID playerId = player.getUniqueId();
        Party party = partiesByMember.get(playerId);
        if (party == null) {
            send(player, "error.not_in_party", Map.of());
            return;
        }

        boolean wasLeader = party.isLeader(playerId);
        party.removeMember(playerId);
        partiesByMember.remove(playerId);

        if (party.size() == 0) {
            partiesByName.remove(normalizeName(party.name()));
            invalidateInvites(party);
            send(player, "leave.disbanded", Map.of("party", party.name()));
            return;
        }

        if (wasLeader) {
            UUID newLeaderId = party.promoteOldestMember().orElse(null);
            // リーダー脱退時は、そのパーティが発行した招待をすべて無効化する。
            invalidateInvites(party);
            if (newLeaderId != null) {
                broadcast(party, "leave.leader_moved",
                        Map.of("player", resolveName(newLeaderId)));
            }
        }

        send(player, "leave.success", Map.of("party", party.name()));
        broadcast(party, "leave.broadcast", Map.of("player", player.getName()));
    }

    /**
     * 所属パーティのメンバー一覧を送信する。
     * <p>
     * オフラインメンバーの名前はログアウト時にキャッシュしたものを使用する。
     *
     * @param player 一覧を表示するプレイヤー
     */
    public void list(Player player) {
        Party party = partiesByMember.get(player.getUniqueId());
        if (party == null) {
            send(player, "error.not_in_party", Map.of());
            return;
        }

        send(player, "list.header", Map.of(
                "party", party.name(),
                "size", String.valueOf(party.size()),
                "max", String.valueOf(MAX_MEMBERS)));

        UUID leaderId = party.leaderId();
        for (UUID memberId : party.members()) {
            send(player, "list.entry", Map.of(
                    "player", resolveName(memberId),
                    "role", memberId.equals(leaderId) ? "リーダー" : "メンバー",
                    "status", isOnline(memberId) ? "オンライン" : "オフライン"));
        }
    }

    /**
     * 指定プレイヤーが所属するパーティを取得する。
     * <p>
     * モブ報酬の分配対象（LA のパーティメンバー）を解決するために使用する。
     *
     * @param playerId プレイヤーの UUID
     * @return 所属パーティ。未所属・{@code null} の場合は空
     */
    public Optional<Party> partyOf(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(partiesByMember.get(playerId));
    }

    /**
     * リーダーがオンラインかどうかを判定する。
     *
     * @param party 対象パーティ
     * @return リーダーがオンラインであれば {@code true}
     */
    private boolean isLeaderOnline(Party party) {
        return isOnline(party.leaderId());
    }

    private boolean isOnline(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        return online != null && online.isOnline();
    }

    /**
     * 表示用のプレイヤー名を解決する。
     * オンラインなら現在の名前、オフラインならログアウト時のキャッシュを使用する。
     */
    private String resolveName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            return online.getName();
        }
        return nameCache.getOrDefault(playerId, playerId.toString());
    }

    /**
     * オンラインプレイヤーを名前（大文字小文字非依存）で取得する。
     *
     * @param name プレイヤー名
     * @return 見つかったプレイヤー。オフライン・該当なしの場合は {@code null}
     */
    private static Player findOnlinePlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    /**
     * 期限切れの招待を破棄する。
     */
    private void purgeExpiredInvites() {
        long now = System.currentTimeMillis();
        invites.values().removeIf(invite -> invite.isExpired(now));
    }

    /**
     * 指定パーティが発行した招待をすべて破棄する。
     */
    private void invalidateInvites(Party party) {
        invites.values().removeIf(invite -> invite.party() == party);
    }

    /**
     * メンバー全員（オンラインのみ）へメッセージを送信する。
     */
    private void broadcast(Party party, String key, Map<String, String> placeholders) {
        for (UUID memberId : party.members()) {
            Player online = Bukkit.getPlayer(memberId);
            if (online != null && online.isOnline()) {
                send(online, key, placeholders);
            }
        }
    }

    /**
     * パーティ関連メッセージを送信する。
     * <p>
     * {@code MessageData/party.yml} にキーが無い場合はコード側のフォールバックを使用する。
     */
    private void send(Player player, String key, Map<String, String> placeholders) {
        MessageService messages = plugin.getMessageService();
        if (messages == null) {
            return;
        }
        String template = messages.findRaw(MESSAGE_FILE, key).orElseGet(() -> fallback(key));
        if (template.isBlank()) {
            return;
        }
        player.sendMessage(messages.deserialize(template, placeholders));
    }

    /**
     * {@code party.yml} が未配置の場合に使用する既定メッセージ。
     */
    private static String fallback(String key) {
        return switch (key) {
            case "command.usage" ->
                    "<gray>使い方:</gray> <white>/party \\<create|invite|accept|leave|list></white>";
            case "create.success" ->
                    "<green>パーティ <white>{party}</white> を作成しました。</green> <dark_gray>(最大 {max} 人)</dark_gray>";
            case "restore.joined" -> "<gray>パーティ <white>{party}</white> に復帰しました。</gray>";
            case "invite.sent" ->
                    "<gray><white>{player}</white> をパーティ <white>{party}</white> に招待しました。</gray>";
            case "invite.received" ->
                    "<yellow><white>{player}</white> からパーティ <white>{party}</white> に招待されました。</yellow>"
                            + " <gray><white>/party accept</white> で参加できます。</gray>"
                            + " <dark_gray>({seconds}秒以内)</dark_gray>";
            case "accept.success" -> "<green>パーティ <white>{party}</white> に参加しました。</green>";
            case "accept.broadcast" -> "<gray><white>{player}</white> がパーティに参加しました。</gray>";
            case "leave.success" -> "<gray>パーティ <white>{party}</white> から脱退しました。</gray>";
            case "leave.broadcast" -> "<gray><white>{player}</white> がパーティから脱退しました。</gray>";
            case "leave.leader_moved" ->
                    "<gray><white>{player}</white> がパーティの新しいリーダーになりました。</gray>";
            case "leave.disbanded" -> "<gray>パーティ <white>{party}</white> を解散しました。</gray>";
            case "list.header" ->
                    "<gray>パーティ <white>{party}</white> <dark_gray>({size}/{max})</dark_gray></gray>";
            case "list.entry" ->
                    "<gray>- <white>{player}</white> <dark_gray>({role} / {status})</dark_gray></gray>";
            case "error.already_in_party" -> "<red>すでにパーティに所属しています。</red>";
            case "error.name_required" ->
                    "<red>パーティ名を指定してください。</red> <gray>/party create \\<name></gray>";
            case "error.name_taken" ->
                    "<red>パーティ名 <white>{party}</white> は既に使われています。</red>";
            case "error.not_in_party" -> "<red>パーティに所属していません。</red>";
            case "error.party_full" ->
                    "<red>パーティ <white>{party}</white> は満員です。</red> <dark_gray>(最大 {max} 人)</dark_gray>";
            case "error.target_offline" ->
                    "<red>プレイヤー <white>{player}</white> はオンラインではありません。</red>";
            case "error.target_self" -> "<red>自分自身は招待できません。</red>";
            case "error.target_in_party" ->
                    "<red>プレイヤー <white>{player}</white> はすでにパーティに所属しています。</red>";
            case "error.no_invite" -> "<red>有効な招待がありません。</red>";
            case "error.invite_expired" -> "<red>招待の有効期限が切れています。</red>";
            case "error.leader_offline" -> "<red>リーダーがオフラインのため、この操作はできません。</red>";
            default -> "";
        };
    }

    /**
     * パーティ名の重複判定用キーへ正規化する。
     */
    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 招待 1 件分。発行時刻を保持し、承認時に有効期限を判定する。
     */
    private record Invite(Party party, long issuedAt) {

        /**
         * 招待が有効期限（60 秒）を過ぎているか判定する。
         *
         * @param now 現在時刻（ミリ秒）
         * @return 期限切れであれば {@code true}
         */
        boolean isExpired(long now) {
            return now - issuedAt >= INVITE_TIMEOUT_MILLIS;
        }
    }
}
