package org.weiwei.treasureBag.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.weiwei.treasureBag.service.OreService;
import org.weiwei.treasureBag.util.Message;

/**
 * 舊指令 /exstorage、/storage（ExtraStorage 時代的習慣，選單也還在用）：直接打開礦物倉庫
 */
public class OreAliasCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (sender instanceof Player player) {
            OreService.open(player);
        } else {
            Message.send(sender, Message.MESSAGE__PLAYER_ONLY);
        }
        return true;
    }
}
