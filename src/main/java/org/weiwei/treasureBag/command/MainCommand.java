package org.weiwei.treasureBag.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.command.sub.ForceCommand;
import org.weiwei.treasureBag.command.sub.OpenCommand;
import org.weiwei.treasureBag.command.sub.ReloadCommand;
import org.weiwei.treasureBag.command.sub.TradeCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;
import org.weiwei.treasureBag.util.PlaceholderUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ####################################################
 * #                                                  #
 * #                Main 指令管理                      #
 * #                                                  #
 * ####################################################
 */

public class MainCommand implements CommandExecutor, TabCompleter {


    // 註冊主指令
    public MainCommand() {

        new OpenCommand().registerCommand();
        new ForceCommand().registerCommand();
        new ReloadCommand().registerCommand();

        new TradeCommand().registerCommand();
    }

    // 註冊主指令
    public void setup(Set<String> commandPrefix) {
        commandPrefix.forEach(
                cmdpx -> {
                    Main.getInst().getCommand(cmdpx).setExecutor(this);
                    Main.getInst().getCommand(cmdpx).setTabCompleter(this);
                    Main.getInst().getLogger().info("Register main command [ " + cmdpx + " ]");
                }
        );
        List<SubCommand> commands = SubCommand.getSubCommandMap().values().stream().toList();
        commands.stream().filter(subCommand -> subCommand instanceof Listener).forEach(subCommand -> Bukkit.getPluginManager().registerEvents((Listener) subCommand, Main.getInst()));
        Main.getInst().getLogger().info("load " + commands.size() + " subcommand");
    }


    private SenderType getType(CommandSender sender) {
        return sender instanceof Player ? SenderType.PLAYER : SenderType.CONSOLE;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command arg1, String label, String[] args) {
        SenderType type = getType(sender);
        if (args.length == 0) {
            if (sender instanceof Player player) {
                PlayerBagService.open(player);
                return true;
            }
            Message.send(sender, Message.MESSAGE__PLAYER_ONLY);
            return true;
        }
        String cmd = args[0];
        SubCommand sub = SubCommand.getSubCommandMap().get(cmd);
        if (sub == null) {
            sendHelp(sender);
            return true;
        } else {

            // 權限檢查
            if (!sub.canUsePermission(sender)) {
                Message.sendPrefix(sender, Message.MESSAGE__NO_PERMISSION);
                return true;
            }

            // 使用者檢查
            if (!sub.isUse(type)) {
                Message msg = (type == SenderType.PLAYER) ? Message.MESSAGE__PLAYER_ONLY : Message.MESSAGE__CONSOLE_ONLY;
                Message.send(sender, msg);
                return true;
            }

            // 參數檢查
            if (args.length == 1 && sub.arg != null && !sub.arg.isEmpty()) {
                Message.send(sender, Message.MESSAGE__UNKNOWN_COMMAND, "&f/" + sub.cmd + " " + sub.arg);
                return true;
            }

            sub.onCommand(sender, args);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String s, String[] args) {
        SenderType type = getType(sender);
        List<SubCommand> subCommands = SubCommand.getSubCommandMap().values().stream().toList();
        if (args.length == 1) {
            return subCommands.stream()
                    .filter(sub -> sub.isUse(type) && sub.cmd.startsWith(args[0].toLowerCase()))
                    .filter(sub -> sub.canUsePermission(sender)) // 檢查玩家權限
                    .map(sub -> sub.cmd)
                    .collect(Collectors.toList());
        }
        return subCommands.stream()
                .filter(sub -> sub.cmd.equalsIgnoreCase(args[0]))
                .findFirst()
                .filter(sub -> sub.isUse(type))
                .filter(sub -> sub.canUsePermission(sender)) // 檢查玩家權限
                .map(sub -> sub.onTabComplete(sender, Arrays.copyOfRange(args, 1, args.length))) // 傳遞去除首個參數的 args
                .orElse(Collections.emptyList());
    }


    // 顯示幫助信息
    private void sendHelp(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Message.send(sender, Message.MESSAGE__PLAYER_ONLY);
            return;
        }
        Message.send(player, Message.HELP__FIRST_LINE); // 顯示第一行提示
        SubCommand.getSubCommandMap().values().stream()
                .filter(sub -> sub.isUse(getType(sender)))
                .forEach(sub -> {
                    TextComponent line = Component.text("§b/" + sub.cmd + " " + sub.arg)
                            .hoverEvent(HoverEvent.showText(Component.text(Message.getMsg(Message.HELP__HOVER))))
                            .clickEvent(ClickEvent.suggestCommand("/" + sub.cmd + " " + sub.arg))
                            .append(Component.text(" §7- " + sub.description));

                    player.sendMessage(line);
                });

        player.sendMessage(Component.text("§7§m                                           "));
    }
}
