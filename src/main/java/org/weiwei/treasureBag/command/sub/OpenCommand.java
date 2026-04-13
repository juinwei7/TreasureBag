package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;

/**
 * 開啟隨身背包
 */

public class OpenCommand extends SubCommand {

    public OpenCommand() {
        super("open", Message.COMMAND__OPEN);
        setType(SenderType.PLAYER);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        PlayerBagService.open((Player) sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
