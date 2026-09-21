package org.weiwei.treasureBag;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.weiwei.treasureBag.command.MainCommand;
import org.weiwei.treasureBag.dataBase.DataBase;
import org.weiwei.treasureBag.listener.PlayerBagListener;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.util.Set;

public final class Main extends JavaPlugin {

    private static final Set<String> COMMAND = Set.of("treasurebag", "bag", "bp");

    @Getter
    public static Main inst;

    @Override
    public void onEnable() {
        inst = this;

        ConfigManager configManager = new ConfigManager(this);
        configManager.loadConfig();

        Message.loadMessage();

        // 資料庫失敗就停用插件：物品倉儲插件在沒有資料表的狀態下運作，
        // 只會讓每次開背包報錯，甚至造成資料異常
        if (!DataBase.initialize()) {
            getLogger().severe("資料庫初始化失敗，停用插件（請檢查 DataBase.yml 與上方錯誤訊息）");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        MainCommand mainCommand = new MainCommand();
        mainCommand.setup(COMMAND);

        Bukkit.getPluginManager().registerEvents(new PlayerBagListener(), this);

    }

    @Override
    public void onDisable() {
        DataBase.close();
    }
}
