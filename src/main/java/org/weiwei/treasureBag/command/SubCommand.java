package org.weiwei.treasureBag.command;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.util.Message;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子指令抽象類
 *
 * @author weiwei
 */
public abstract class SubCommand implements Comparable<SubCommand> {

    @Getter
    public static final Map<String, SubCommand> subCommandMap = new HashMap<>();

    public String cmd, arg = "", description = "";

    @Setter
    public double priority = 100;

    @Getter
    private SenderType[] types = new SenderType[]{SenderType.ALL};

    // 是否需要權限
    public boolean permissionRequired = false;


    /**
     * @param cmd String
     */
    public SubCommand(String cmd, Message message) {
        this.cmd = cmd;
        this.description = Message.getMsg(message);
    }

    /**
     * 註冊指令方法
     */
    public final void registerCommand() {
        if (!subCommandMap.containsKey(cmd)) {
            subCommandMap.put(cmd, this);
        }
    }


    /**
     * 执行指令抽象方法
     *
     * @param sender CommandSender
     * @param args   String[]
     */
    public abstract void onCommand(CommandSender sender, String[] args);

    /**
     * TAB執行方法
     *
     * @param sender CommandSender
     * @param args   String[]
     * @return List
     */
    public abstract List<String> onTabComplete(CommandSender sender, String[] args);

    /**
     * 判斷是否可用
     *
     * @param type   SenderType
     * @return boolean
     */
    public boolean isUse(SenderType type) {
        return Arrays.stream(this.types).anyMatch(senderType -> senderType.equals(SenderType.ALL) || senderType.equals(type));
    }

    // 設置指令參數
    protected void setArg(String arg) {
        this.arg = arg;
    }

    // 設置權限需要
    protected void setPermissionRequired() {
        this.permissionRequired = true;
    }

    // 判斷是否可以使用權限
    protected boolean canUsePermission(CommandSender sender) {
        if (!permissionRequired) return true; // 如果沒有設定權限，則默認可以使用
        if (sender instanceof Player player) {
            String perm = "huAltar." + cmd;
            return player.hasPermission(perm);
        }
        return true;
    }

    protected void setType(SenderType... types) {
        this.types = types;
    }

    @Override
    public int compareTo(SubCommand cmd) {
        return Double.compare(priority, cmd.priority);
    }

}
