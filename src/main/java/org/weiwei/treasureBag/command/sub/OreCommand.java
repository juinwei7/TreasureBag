package org.weiwei.treasureBag.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.OreService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 打開礦物倉庫：/bag ore；管理員查看別人的：/bag ore <玩家>（唯讀）
 */
public class OreCommand extends SubCommand {

    private static final String OPEN_OTHER_PERMISSION = "treasure-bag.admin";

    public OreCommand() {
        super("ore", Message.COMMAND__ORE);
        setType(SenderType.PLAYER);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 2 || args[1].equalsIgnoreCase(player.getName())) {
            OreService.open(player);
            return;
        }
        if (!player.hasPermission(OPEN_OTHER_PERMISSION)) {
            Message.sendPrefix(player, Message.MESSAGE__NO_PERMISSION);
            return;
        }
        OreService.openOther(player, args[1]);
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
