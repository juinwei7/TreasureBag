package org.weiwei.treasureBag.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 查看玩家的背包存取紀錄：/bag log <玩家> [頁碼]
 * 權限沿用查看別人背包的 treasure-bag.admin；主控台也能用
 */
public class LogCommand extends SubCommand {

    private static final String PERMISSION = "treasure-bag.admin";

    public LogCommand() {
        super("log", Message.COMMAND__LOG);
        setArg("<玩家> [頁碼]");
    }

    @Override
    protected boolean canUsePermission(CommandSender sender) {
        return !(sender instanceof Player player) || player.hasPermission(PERMISSION);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length > 2) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                Message.sendPrefix(sender, Message.MESSAGE__INVALID_NUMBER, args[2]);
                return;
            }
        }
        PlayerBagService.showLogs(sender, args[1], page);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
