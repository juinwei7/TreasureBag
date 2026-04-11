package org.weiwei.treasureBag;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {


    @Getter
    public static Main inst;

    @Override
    public void onEnable() {
        inst = this;

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
