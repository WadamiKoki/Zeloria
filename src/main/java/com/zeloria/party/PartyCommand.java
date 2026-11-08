package com.zeloria.party;

import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /party} コマンドの実装。
 * <p>
 * サブコマンドの解析とタブ補完のみを担当し、実際の状態管理と通知は
 * {@link PartyManager} へ委譲する。権限は不要で、全プレイヤーが実行できる。
 * <ul>
 *     <li>{@code /party create <name>} … パーティ作成</li>
 *     <li>{@code /party invite <player>} … オンラインプレイヤーを招待</li>
 *     <li>{@code /party accept} … 最新の招待を承認</li>
 *     <li>{@code /party leave} … 脱退</li>
 *     <li>{@code /party list} … メンバー一覧表示</li>
 * </ul>
 */
public class PartyCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String CREATE = "create";
    private static final String INVITE = "invite";
    private static final String ACCEPT = "accept";
    private static final String LEAVE = "leave";
    private static final String LIST = "list";

    /** 第 1 引数のサブコマンド候補。 */
    private static final List<String> SUBCOMMANDS = List.of(CREATE, INVITE, ACCEPT, LEAVE, LIST);

    private final Zeloria plugin;

    public PartyCommand(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize("<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return true;
        }

        PartyManager partyManager = plugin.getPartyManager();
        if (partyManager == null) {
            player.sendMessage(MINI.deserialize("<red>パーティ機能が初期化されていません。</red>"));
            return true;
        }

        if (args.length == 0) {
            partyManager.sendUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case CREATE -> {
                if (args.length < 2) {
                    partyManager.sendUsage(player);
                    return true;
                }
                partyManager.create(player, args[1]);
            }
            case INVITE -> {
                if (args.length < 2) {
                    partyManager.sendUsage(player);
                    return true;
                }
                partyManager.invite(player, args[1]);
            }
            case ACCEPT -> partyManager.accept(player);
            case LEAVE -> partyManager.leave(player);
            case LIST -> partyManager.list(player);
            default -> partyManager.sendUsage(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], SUBCOMMANDS);
        }

        // invite の第 2 引数はオンラインプレイヤー名を補完する。
        if (args.length == 2 && INVITE.equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player player
                        && online.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                names.add(online.getName());
            }
            return partial(args[1], names);
        }

        return List.of();
    }

    /**
     * 入力値で前方一致する候補を、重複を除去して昇順に返す。
     *
     * @param input      入力中の文字列
     * @param candidates 候補一覧
     * @return 絞り込み後の候補
     */
    private static List<String> partial(String input, List<String> candidates) {
        String lower = input.toLowerCase(Locale.ROOT);
        Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                sorted.add(candidate);
            }
        }
        return new ArrayList<>(sorted);
    }
}
