package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.util.List;

/**
 * 重新加載設定檔
 */

public class ReloadCommand extends SubCommand {

    public ReloadCommand() {
        super("reload", Message.COMMAND__RELOAD);
        setPermissionRequired();
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        new ConfigManager(Main.getInst()).reload();
        Message.loadMessage();
        Message.sendPrefix(sender, Message.MESSAGE__RELOAD_ALL);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
