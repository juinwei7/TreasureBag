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
        DataBase.initialize();

        MainCommand mainCommand = new MainCommand();
        mainCommand.setup(COMMAND);

        Bukkit.getPluginManager().registerEvents(new PlayerBagListener(), this);

    }

    @Override
    public void onDisable() {
        DataBase.close();
    }
}
