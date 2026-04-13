package org.weiwei.treasureBag.dataBase;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * DataBase
 */

public class DataBase {

    private static final Logger logger = Main.getInst().getLogger();

    private static HikariDataSource dataSource = null;


    public static YamlConfiguration dataBaseConfig;


    private static void loadDataBase() {
        File file = new File(Main.getInst().getDataFolder(), "DataBase.yml");
        if (!file.exists()) {
            Main.getInst().getLogger().info("Create DataBase.yml");
            Main.getInst().saveResource("DataBase.yml", true);
        }
        dataBaseConfig = YamlConfiguration.loadConfiguration(file);
    }

    // 獲取 MySQL 連線
    public static Connection getConnection() {
        try {
            if (dataSource == null) {
                logger.warning("MySQL connection pool is not initialized.");
                Main.getInst().getLogger().warning("請先初始化 MySQL 連線池");
            }
            return dataSource.getConnection();
        } catch (Exception e) {
            logger.severe("Failed to get MySQL connection: " + e.getMessage());
            Main.getInst().getLogger().severe("請檢查 MySQL 連線池是否已初始化");
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 初始化 MySQL 連線池
     */
    public static boolean initialize() {
        DataBase.loadDataBase();

        if (dataBaseConfig == null) {
            logger.warning("資料庫連線失敗");
            return false;
        }

        String path = "mysql.";
        String host = dataBaseConfig.getString(path + "host");
        int port = dataBaseConfig.getInt(path + "port", 3306);
        String database = dataBaseConfig.getString(path + "database");
        String user = dataBaseConfig.getString(path + "user");
        String password = dataBaseConfig.getString(path + "password");

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&serverTimezone=Asia/Taipei");
            config.setUsername(user);
            config.setPassword(password);
            config.setPoolName(database);

            // HikariCP 最佳化參數
            config.setMaximumPoolSize(10); // 最大連線數
            config.setMinimumIdle(2); // 最小閒置連線
            config.setIdleTimeout(30000); // 閒置連線存活時間 (30 秒)
            config.setMaxLifetime(1800000); // 連線最大存活時間 (30 分鐘)
            config.setConnectionTimeout(10000); // 連線超時時間 (10 秒)
            config.setConnectionTestQuery("SELECT 1");

            dataSource = new HikariDataSource(config);
            logger.info("MySQL database connected successfully!");

            createTable();
            return true;
        } catch (Exception e) {
            logger.severe("Failed to initialize MySQL connection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }


    /*--------------------------------------------------
                     新增DataBase
     --------------------------------------------------*/
    private static void createTable() {
        try (InputStream inputStream = Main.getInst().getResource("createDataBase.sql");
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String sql = reader.lines().collect(Collectors.joining("\n"));
            try (Connection conn = DataBase.dataSource.getConnection()) {
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        try (PreparedStatement pstmt = conn.prepareStatement(trimmed)) {
                            pstmt.execute();
                        }
                    }
                }
            }
            Main.getInst().getLogger().info("✅ 成功執行 createDataBase.sql");
        } catch (Exception e) {
            Main.getInst().getLogger().severe("❌ 執行 createDataBase.sql 失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 關閉 MySQL 連線池
     */
    public static void close() {
        if (dataSource != null) {
            dataSource.close();
            logger.info("MySQL database connection closed.");
        }
    }

}
