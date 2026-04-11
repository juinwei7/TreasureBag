package org.weiwei.treasureBag.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.List;

/**
 * 設定檔案管理（Config.yml）
 */
public class ConfigManager {

    public static final String MAIN_SERVER = "MainServer";
    public static final String DEBUG = "debug";
    private static YamlConfiguration config;
    private final Plugin plugin;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public static YamlConfiguration getConfig() {
        return config;
    }

    public static String getStr(String key) {
        return config.getString(key);
    }

    public static int getInt(String key) {
        return config.getInt(key);
    }

    public static boolean getBool(String key) {
        return config.getBoolean(key);
    }

    public static double getDouble(String key) {
        return config.getDouble(key);
    }

    public static List<String> getList(String key) {
        return config.getStringList(key);
    }

    public Plugin getPlugin() {
        return plugin;
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
