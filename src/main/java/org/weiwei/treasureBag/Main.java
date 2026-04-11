package org.weiwei.treasureBag;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

public final class Main extends JavaPlugin {


    @Getter
    public static Main inst;

    @Override
    public void onEnable() {
        inst = this;

        ConfigManager configManager = new ConfigManager(this);
        configManager.loadConfig();

        Message.loadMessage();

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
