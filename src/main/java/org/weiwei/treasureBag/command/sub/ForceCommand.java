package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;

/**
 * 強制開啟隨身背包，並將超出可用格數的物品掉落到地板上
 */
public class ForceCommand extends SubCommand {

    public ForceCommand() {
        super("force", Message.COMMAND__FORCE);
        setType(SenderType.PLAYER);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        PlayerBagService.forceOpen((Player) sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
