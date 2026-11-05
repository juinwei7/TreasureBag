package org.weiwei.treasureBag.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.OreService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理玩家的礦物倉庫（treasure-bag.admin，主控台可用）：
 * <pre>
 * /bag oreadmin add &lt;玩家&gt; &lt;物品&gt; &lt;數量&gt;    補發
 * /bag oreadmin take &lt;玩家&gt; &lt;物品&gt; &lt;數量&gt;   扣除（不夠就扣光）
 * /bag oreadmin space &lt;玩家&gt; &lt;件數|default&gt;  個別容量
 * /bag oreadmin import                         重跑這台的 ExtraStorage 匯入（只限主控台）
 * </pre>
 */
public class OreAdminCommand extends SubCommand {

    private static final String PERMISSION = "treasure-bag.admin";
    private static final List<String> ACTIONS = List.of("add", "take", "space", "import");

    public OreAdminCommand() {
        super("oreadmin", Message.COMMAND__ORE_ADMIN);
        setArg("<add|take|space|import> ...");
    }

    @Override
    protected boolean canUsePermission(CommandSender sender) {
        return !(sender instanceof Player player) || player.hasPermission(PERMISSION);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        String action = args[1].toLowerCase();
        switch (action) {
            case "import" -> runImport(sender);
            case "add", "take" -> changeAmount(sender, action, args);
            case "space" -> changeSpace(sender, args);
            default -> Message.sendList(sender, Message.MESSAGE__ORE_ADMIN_USAGE);
        }
    }

    private void runImport(CommandSender sender) {
        if (sender instanceof Player) {
            Message.send(sender, Message.MESSAGE__CONSOLE_ONLY);
            return;
        }
        OreService.importNow(sender);
    }

    private void changeAmount(CommandSender sender, String action, String[] args) {
        if (args.length < 5) {
            Message.sendList(sender, Message.MESSAGE__ORE_ADMIN_USAGE);
            return;
        }
        Material material = Material.matchMaterial(args[3]);
        if (material == null || !OreService.canStore(material)) {
            Message.sendPrefix(sender, Message.MESSAGE__ORE_UNKNOWN_ITEM, args[3]);
            return;
        }
        long amount = parsePositive(sender, args[4]);
        if (amount <= 0) return;
        if (action.equals("add")) OreService.adminAdd(sender, args[2], material, amount);
        else OreService.adminTake(sender, args[2], material, amount);
    }

    private void changeSpace(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Message.sendList(sender, Message.MESSAGE__ORE_ADMIN_USAGE);
            return;
        }
        if (args[3].equalsIgnoreCase("default")) {
            OreService.adminSpace(sender, args[2], null);
            return;
        }
        long space = parsePositive(sender, args[3]);
        if (space > 0) OreService.adminSpace(sender, args[2], space);
    }

    private long parsePositive(CommandSender sender, String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        Message.sendPrefix(sender, Message.MESSAGE__INVALID_NUMBER, raw);
        return -1;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return ACTIONS.stream().filter(action -> action.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("import")) {
            String input = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("take"))) {
            String input = args[2].toUpperCase();
            return java.util.Arrays.stream(Material.values())
                    .filter(material -> material.isItem() && material.name().startsWith(input))
                    .map(Material::name)
                    .limit(30)
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("space")) return List.of("default");
        return List.of();
    }
}
