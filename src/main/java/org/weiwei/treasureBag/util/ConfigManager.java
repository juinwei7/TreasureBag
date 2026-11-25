package org.weiwei.treasureBag.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * 設定檔案管理（Config.yml）
 */
public class ConfigManager {

    private static YamlConfiguration config;
    private final Plugin plugin;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public static YamlConfiguration getConfig() {
        return config;
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "Config.yml");
        if (!file.exists()) plugin.saveResource("Config.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void reload() {
        loadConfig();
    }
}
