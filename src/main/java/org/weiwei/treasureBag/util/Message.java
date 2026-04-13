package org.weiwei.treasureBag.util;

import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;

import java.io.File;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * 消息工具類，用於處理翻譯和玩家通信
 */
public enum Message {
    MESSAGE__PREFIX,
    MESSAGE__RELOAD_ALL,
    MESSAGE__RELOAD_SINGLE,
    MESSAGE__RELOAD_NULL,
    MESSAGE__NO_PERMISSION,
    MESSAGE__NO_PLAYER,
    MESSAGE__PLAYER_ONLY,
    MESSAGE__CONSOLE_ONLY,
    MESSAGE__LIMIT_COMMAND,
    MESSAGE__UNKNOWN_COMMAND,
    MESSAGE__UNKNOWN_SUBCOMMAND,
    MESSAGE__NOT_ENOUGH_MONEY,
    MESSAGE__NOT_ENOUGH_MONEY_TYPE,
    MESSAGE__MAIN_HAND_AIR,
    MESSAGE__FEATURE_NOT_ENABLED,
    MESSAGE__LIMIT_SERVER,
    MESSAGE__COOLDOWN_BAR,
    MESSAGE__INVALID_NUMBER,
    MESSAGE__INVALID_ARGS,
    MESSAGE__NOTIFICATION_MANGER,
    MESSAGE__TIME_FORMAT_ERROR,
    MESSAGE__PLUGIN_DISABLED,
    MESSAGE__ERROR,
    MESSAGE__SUCCESS,
    MESSAGE__BAG_SPACE_SHRINK,
    MESSAGE__BAG_FORCE_CLICK,
    MESSAGE__BAG_FORCE_HOVER,
    MESSAGE__BAG_FORCE_DROPPED,
    MESSAGE__BAG_CONTAINER_FORBIDDEN,
    MESSAGE__BAG_TARGET_IN_USE,

    FORMAT__TIME_S,
    FORMAT__TIME_M,
    FORMAT__TIME_H,
    FORMAT__TIME_D,
    FORMAT__TIME_Y,
    FORMAT__IS_NULL,

    COMMAND__OPEN,
    COMMAND__RELOAD,
    COMMAND__FORCE,

    ADMIN_MESSAGE__EDIT_DISABLED,

    MENU_TITLE__ADMIN_MENU,
    MENU_TITLE__VIEW_MENU,
    MENU_TITLE__BAG_MENU,

    HELP__FIRST_LINE,
    HELP__HOVER,


    ;


    private static final Tool tool = new Tool(); // 工具類，用於處理消息
    @Getter
    private static YamlConfiguration messages; // 消息配置文件

    /**
     * 加載消息配置文件
     */
    public static void loadMessage() {
        File file = new File(Main.getInst().getDataFolder(), "Message.yml");

        if (!file.exists()) {
            Main.getInst().getLogger().info("創建 Message.yml");
            Main.getInst().saveResource("Message.yml", true);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        tool.setConfig(messages);
    }

    /**
     * 獲取格式化的字串消息
     *
     * @param loc  消息的枚舉值
     * @param args 格式化參數
     * @return 格式化後的字串
     */
    public static String getMsg(Message loc, Object... args) {
        return tool.getString(loc.toString(), args);
    }

    /**
     * 獲取格式化的字串列表
     *
     * @param loc  消息的枚舉值
     * @param args 格式化參數
     * @return 格式化後的字串列表
     */
    public static List<String> getStringList(Message loc, Object... args) {
        return tool.getStringList(loc.toString(), args);
    }

    /**
     * 發送消息給實體（玩家或生物）
     *
     * @param sender 接收消息的實體
     * @param loc    消息的枚舉值
     * @param args   格式化參數
     */
    public static void send(CommandSender sender, Message loc, Object... args) {
        tool.send(sender, loc.toString(), args);
    }

    public static void sendPrefix(CommandSender sender, Message loc, Object... args) {
        tool.sendPrefix(sender, loc.toString(), args);
    }


    /**
     * 發送純文本消息給實體（玩家或生物）
     *
     * @param sender 接收消息的實體
     * @param loc    消息的枚舉值
     * @param args   格式化參數
     */
    public static void sendList(CommandSender sender, Message loc, Object... args) {
        List<String> list = tool.getStringList(loc.toString(), args);
        if (list == null || list.isEmpty()) return;
        list.forEach(sender::sendMessage);
    }


    @Override
    public String toString() {
        return name().replace("__", ".");
    }

    /**
     * 訊息處理工具類
     */
    @Setter
    public static class Tool {
        public static final Pattern HEX_PATTERN = Pattern.compile("&#(\\w{5}[0-9a-f])"); // HEX 顏色匹配模式
        private YamlConfiguration config; // 配置文件

        /**
         * 轉換文本中的十六進制顏色代碼
         *
         * @param textToTranslate 含有十六進制顏色代碼的文本
         * @return 轉換後的文本
         */
        public static String translateHexCodes(String textToTranslate) {
            if (textToTranslate == null) return "";

            Matcher matcher = HEX_PATTERN.matcher(textToTranslate);
            StringBuffer buffer = new StringBuffer();

            while (matcher.find()) {
                matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
            }

            return matcher.appendTail(buffer).toString();
        }

        /**
         * 從配置中獲取格式化的字串
         *
         * @param loc  配置鍵
         * @param args 格式化參數
         * @return 格式化後的字串
         */
        public String getString(String loc, Object... args) {
            String message = config.getString(loc, "空消息: " + loc);
            return translateHexCodes(MessageFormat.format(message, args)).replace("&", "§");
        }

        /**
         * 從配置中獲取格式化的字串列表
         *
         * @param loc  配置鍵
         * @param args 格式化參數
         * @return 格式化後的字串列表
         */
        public List<String> getStringList(String loc, Object... args) {
            List<String> list = config.getStringList(loc);
            if (list.isEmpty()) return Collections.singletonList("空消息: " + loc);

            IntStream.range(0, list.size()).forEach(i -> list.set(i, translateHexCodes(MessageFormat.format(list.get(i), args)).replace("&", "§")));
            return list;
        }

        /**
         * 發送消息給實體（玩家或生物）
         *
         * @param sender 接收消息的實體
         * @param loc    配置鍵
         * @param args   格式化參數
         */
        public void send(CommandSender sender, String loc, Object... args) {
            if (sender != null) {
                sender.sendMessage(getString(loc, args));
            }
        }

        public void sendPrefix(CommandSender sender, String loc, Object... args) {
            if (sender != null) {
                sender.sendMessage(Message.getMsg(Message.MESSAGE__PREFIX) + getString(loc, args));
            }
        }

        /**
         * 發送純文本消息給實體（玩家或生物）
         *
         * @param sender  接收消息的實體
         * @param message 要發送的消息文本
         */
        public void send(CommandSender sender, String message) {
            if (sender != null) {
                sender.sendMessage(message);
            }
        }
    }
}
