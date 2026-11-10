package org.weiwei.treasureBag.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.service.VaultKind;
import org.weiwei.treasureBag.service.VaultService;
import org.weiwei.treasureBag.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理玩家的倉庫（treasure-bag.admin，主控台可用）：/bag oreadmin、/bag materialadmin
 * <pre>
 * add &lt;玩家&gt; &lt;物品&gt; &lt;數量&gt;    補發（礦物用英文材質名稱，副本素材用 MythicMobs 代號）
 * take &lt;玩家&gt; &lt;物品&gt; &lt;數量&gt;   扣除（不夠就扣光）
 * space &lt;玩家&gt; &lt;件數|default&gt;  個別容量
 * import                         重跑這台的 ExtraStorage 匯入（只有 oreadmin，只限主控台）
 * </pre>
 */
public class VaultAdminCommand extends SubCommand {

    private static final String PERMISSION = "treasure-bag.admin";

    private final VaultKind kind;
    private final VaultService service;
    private final List<String> actions;

    public VaultAdminCommand(VaultKind kind, Message description) {
        super(kind.command + "admin", description);
        this.kind = kind;
        this.service = VaultService.of(kind);
        this.actions = kind == VaultKind.ORE ? List.of("add", "take", "space", "import") : List.of("add", "take", "space");
        setArg("<" + String.join("|", actions) + "> ...");
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
            default -> Message.sendList(sender, kind.adminUsage);
        }
    }

    private void runImport(CommandSender sender) {
        if (kind != VaultKind.ORE) {
            Message.sendList(sender, kind.adminUsage);
            return;
        }
        if (sender instanceof Player) {
            Message.send(sender, Message.MESSAGE__CONSOLE_ONLY);
            return;
        }
        VaultService.importOre(sender);
    }

    private void changeAmount(CommandSender sender, String action, String[] args) {
        if (args.length < 5) {
            Message.sendList(sender, kind.adminUsage);
            return;
        }
        String key = action.equals("take") ? kind.parseStoredKey(args[3]) : kind.parseKey(args[3]);
        if (key == null) {
            Message.sendPrefix(sender, kind.unknownItem, args[3]);
            return;
        }
        long amount = parsePositive(sender, args[4]);
        if (amount <= 0) return;
        if (action.equals("add")) service.adminAdd(sender, args[2], key, amount);
        else service.adminTake(sender, args[2], key, amount);
    }

    private void changeSpace(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Message.sendList(sender, kind.adminUsage);
            return;
        }
        if (args[3].equalsIgnoreCase("default")) {
            service.adminSpace(sender, args[2], null);
            return;
        }
        long space = parsePositive(sender, args[3]);
        if (space > 0) service.adminSpace(sender, args[2], space);
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
            return actions.stream().filter(action -> action.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("import")) {
            String input = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("take"))) {
            return kind.suggest(args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("space")) return List.of("default");
        return List.of();
    }
}
