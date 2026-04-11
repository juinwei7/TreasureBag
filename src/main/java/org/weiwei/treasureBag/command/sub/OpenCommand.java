package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.util.Message;

import java.util.List;

/**
 * 開啟隨身背包
 */

public class OpenCommand extends SubCommand {

    public OpenCommand() {
        super("open", Message.COMMAND__OPEN);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {

    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
