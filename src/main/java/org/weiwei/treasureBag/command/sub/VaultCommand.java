package org.weiwei.treasureBag.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.VaultKind;
import org.weiwei.treasureBag.service.VaultService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 打開倉庫：/bag ore、/bag material；管理員查看別人的：/bag ore &lt;玩家&gt;（唯讀）
 */
public class VaultCommand extends SubCommand {

    private static final String OPEN_OTHER_PERMISSION = "treasure-bag.admin";

    private final VaultService service;

    public VaultCommand(VaultKind kind, Message description) {
        super(kind.command, description);
        this.service = VaultService.of(kind);
        setType(SenderType.PLAYER);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 2 || args[1].equalsIgnoreCase(player.getName())) {
            service.open(player);
            return;
        }
        if (!player.hasPermission(OPEN_OTHER_PERMISSION)) {
            Message.sendPrefix(player, Message.MESSAGE__NO_PERMISSION);
            return;
        }
        service.openOther(player, args[1]);
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
