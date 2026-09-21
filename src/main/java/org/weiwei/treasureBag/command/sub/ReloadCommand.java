package org.weiwei.treasureBag.command.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.dataBase.DataBase;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.io.File;
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

        // reload 不會重建資料庫連線池；若 DataBase.yml 的 type 已改動，
        // 明確告知需要重啟，避免管理員誤以為已切換後端
        String diskType = YamlConfiguration.loadConfiguration(
                new File(Main.getInst().getDataFolder(), "DataBase.yml")).getString("type", "");
        if (diskType == null || diskType.isBlank()) diskType = "mysql";
        String liveType = DataBase.getType().name().toLowerCase();
        if (!diskType.trim().equalsIgnoreCase(liveType)) {
            Message.sendPrefix(sender, Message.MESSAGE__DB_TYPE_CHANGED, diskType.trim(), liveType);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
