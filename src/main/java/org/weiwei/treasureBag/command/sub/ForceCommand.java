package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SenderType;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;

/**
 * 強制開啟自己的隨身背包，並將超出可用格數的物品掉落到地板上（管理員用）
 * 玩家一般開背包遇到超出格會開超出格取出介面，不需要這個指令；權限沿用 treasure-bag.admin，沒權限的看不到也用不了
 */
public class ForceCommand extends SubCommand {

    private static final String PERMISSION = "treasure-bag.admin";

    public ForceCommand() {
        super("force", Message.COMMAND__FORCE);
        setType(SenderType.PLAYER);
    }

    @Override
    protected boolean canUsePermission(CommandSender sender) {
        return !(sender instanceof Player player) || player.hasPermission(PERMISSION);
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
