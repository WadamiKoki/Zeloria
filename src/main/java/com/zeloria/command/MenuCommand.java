package com.zeloria.command;

import com.zeloria.core.Zeloria;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * {@code /menu} コマンドの実装。
 * <p>
 * 実行者に対して、装備の着脱と今後追加される各種メニュー機能の呼び出しに
 * 責務を絞った独自メニュー GUI を開く。
 * 実際の生成・描画は {@link com.zeloria.gui.MenuService} が担う。
 */
public class MenuCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** {@code /menu} 実行に必要な権限。 */
    private static final String PERMISSION = "zeloria.menu";

    private final Zeloria plugin;

    public MenuCommand(Zeloria plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize("<red>このコマンドはプレイヤーのみ実行できます。</red>"));
            return true;
        }

        if (!player.hasPermission(PERMISSION)) {
            player.sendActionBar(MINI.deserialize(
                    "<red>このコマンドを実行する権限がありません。</red>"));
            return true;
        }

        if (plugin.getMenuService() == null) {
            player.sendActionBar(MINI.deserialize("<red>統合メニューが初期化されていません。</red>"));
            return true;
        }

        plugin.getMenuService().open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
