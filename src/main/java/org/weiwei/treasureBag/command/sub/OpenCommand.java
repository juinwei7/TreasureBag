package org.weiwei.treasureBag.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 開啟隨身背包
 */

public class OpenCommand extends SubCommand {

    private static final String OPEN_OTHER_PERMISSION = "treasure-bag.admin";

    public OpenCommand() {
        super("open", Message.COMMAND__OPEN);
        setType(SenderType.PLAYER);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 2) {
            PlayerBagService.open(player);
            return;
        }

        String targetName = args[1];
        if (!targetName.equalsIgnoreCase(player.getName()) && !player.hasPermission(OPEN_OTHER_PERMISSION)) {
            Message.sendPrefix(player, Message.MESSAGE__NO_PERMISSION);
            return;
        }

        PlayerBagService.openOther(player, targetName);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender.hasPermission(OPEN_OTHER_PERMISSION)) {
            String input = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
