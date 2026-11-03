package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 幫自己的背包頁面取名字：/bag name <頁碼> [名稱]，不給名稱就是清除
 */
public class NameCommand extends SubCommand {

    public NameCommand() {
        super("name", Message.COMMAND__NAME);
        setType(SenderType.PLAYER);
        setArg("<頁碼> [名稱]");
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        int page;
        try {
            page = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            Message.sendPrefix(player, Message.MESSAGE__INVALID_NUMBER, args[1]);
            return;
        }

        String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
        PlayerBagService.renamePage(player, page, name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            return IntStream.rangeClosed(1, PlayerBagService.getMaxPage(player))
                    .mapToObj(String::valueOf)
                    .filter(page -> page.startsWith(args[0]))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
