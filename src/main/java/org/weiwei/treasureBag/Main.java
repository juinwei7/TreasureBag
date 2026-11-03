package org.weiwei.treasureBag;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.weiwei.treasureBag.command.MainCommand;
import org.weiwei.treasureBag.dataBase.DataBase;
import org.weiwei.treasureBag.listener.PlayerBagListener;
import org.weiwei.treasureBag.service.BagStorage;
import org.weiwei.treasureBag.service.BagTitle;
import org.weiwei.treasureBag.service.PlayerBagService;
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

        // IO 執行緒啟動時會先補寫上次停用沒存進去的背包（plugins/TreasureBag/unsaved）
        BagStorage.start();

        MainCommand mainCommand = new MainCommand();
        mainCommand.setup(COMMAND);

        Bukkit.getPluginManager().registerEvents(new PlayerBagListener(), this);
        BagTitle.setup(this);

    }

    @Override
    public void onDisable() {
        // 先存還開著的背包與排隊中的存檔，再關連線池
        if (DataBase.isReady()) PlayerBagService.shutdown();
        DataBase.close();
    }
}
