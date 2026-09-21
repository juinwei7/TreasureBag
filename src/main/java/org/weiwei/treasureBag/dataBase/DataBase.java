package org.weiwei.treasureBag.dataBase;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * DataBase
 * <p>
 * 支援 MySQL 與 SQLite 兩種後端，由 DataBase.yml 的 {@code type} 決定。
 * 兩者共用 {@link org.weiwei.treasureBag.data.PlayerBagRepository} 的 SQL，
 * 只有建表語法（schema 資源）與連線池參數不同。
 */

public class DataBase {

    private static final Logger logger = Main.getInst().getLogger();

    private static HikariDataSource dataSource = null;

    /**
     * 目前使用的資料庫後端；初始化前預設為 MySQL
     */
    @Getter
    private static DataBaseType type = DataBaseType.MYSQL;

    public static YamlConfiguration dataBaseConfig;


    /**
     * 支援的資料庫後端，各自對應一份建表 SQL 資源
     */
    @Getter
    public enum DataBaseType {

        MYSQL("sql/mysql.sql"),
        SQLITE("sql/sqlite.sql"),
        ;

        private final String schemaResource;

        DataBaseType(String schemaResource) {
            this.schemaResource = schemaResource;
        }

        /**
         * 解析設定檔的 type 欄位。
         * 未設定（升級前的舊 DataBase.yml 沒有此欄位）→ 回傳 MYSQL 維持原本行為；
         * 有設定但無法辨識（打錯字）→ 拋出例外讓啟動失敗，
         * 避免靜默退回 MySQL 把資料寫進錯誤的後端。
         */
        private static DataBaseType parse(String raw) {
            if (raw == null || raw.isBlank()) return MYSQL;

            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知的資料庫類型 [ " + raw + " ]，請使用 mysql 或 sqlite");
            }
        }
    }


    private static void loadDataBase() {
        File file = new File(Main.getInst().getDataFolder(), "DataBase.yml");
        if (!file.exists()) {
            logger.info("Create DataBase.yml");
            Main.getInst().saveResource("DataBase.yml", true);
        }
        dataBaseConfig = YamlConfiguration.loadConfiguration(file);
    }

    // 獲取資料庫連線；連線池不可用時回傳 null（Repository 端會視為錯誤拋出例外）
    public static Connection getConnection() {
        if (dataSource == null) {
            logger.severe("資料庫連線池尚未初始化，請檢查 DataBase.yml 與啟動日誌");
            return null;
        }

        try {
            return dataSource.getConnection();
        } catch (Exception e) {
            logger.severe("Failed to get database connection: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 依 DataBase.yml 的 type 初始化對應的連線池並建表
     *
     * @return false 表示連線池或建表失敗，呼叫端（Main.onEnable）應停用插件，
     *         避免插件在沒有資料表的狀態下運作
     */
    public static boolean initialize() {
        DataBase.loadDataBase();

        if (dataBaseConfig == null) {
            logger.severe("無法讀取 DataBase.yml");
            return false;
        }

        // 重複初始化（如重新載入）時先關閉舊連線池，避免洩漏
        close();

        try {
            type = DataBaseType.parse(dataBaseConfig.getString("type"));

            HikariConfig config = switch (type) {
                case MYSQL -> buildMysqlConfig();
                case SQLITE -> buildSqliteConfig();
            };
            dataSource = new HikariDataSource(config);

            if (!createTable()) return false;

            logger.info(type.name() + " database connected successfully!");
            return true;
        } catch (Exception e) {
            logger.severe("Failed to initialize " + type.name() + " connection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * MySQL 連線池設定
     */
    private static HikariConfig buildMysqlConfig() {
        String path = "mysql.";
        String host = dataBaseConfig.getString(path + "host");
        int port = dataBaseConfig.getInt(path + "port", 3306);
        String database = dataBaseConfig.getString(path + "database");
        String user = dataBaseConfig.getString(path + "user");
        String password = dataBaseConfig.getString(path + "password");

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
        return config;
    }

    /**
     * SQLite 連線池設定
     * <p>
     * SQLite 同時只允許一個寫入者，連線池必須限制為單一連線，
     * 否則 {@code saveAllowedPlayerBags()} 的交易會與其他連線互搶並拿到 SQLITE_BUSY。
     */
    private static HikariConfig buildSqliteConfig() {
        String fileName = dataBaseConfig.getString("sqlite.file", "treasure_bag.db");

        // 相對路徑掛在插件資料夾下；絕對路徑尊重原值
        File dataBaseFile = new File(fileName);
        if (!dataBaseFile.isAbsolute()) {
            dataBaseFile = new File(Main.getInst().getDataFolder(), fileName);
        }
        File parent = dataBaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("無法建立資料庫目錄: " + parent.getAbsolutePath());
        }

        HikariConfig config = new HikariConfig();
        // journal_mode=WAL 讓讀寫不互相阻塞；busy_timeout 讓短暫鎖競爭自動重試而非直接失敗
        config.setJdbcUrl("jdbc:sqlite:" + dataBaseFile.getAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000");
        // 明確指定 driver：sqlite-jdbc 已 shade 進插件 jar，
        // 但 Bukkit 的 classloader 下交給 DriverManager 自行搜尋不一定找得到
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName(dataBaseFile.getName());

        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setMaxLifetime(0); // 本地檔案連線不需定期回收（預設 30 分鐘會無意義地重建唯一的連線）
        config.setConnectionTimeout(2000); // 只有 1 條連線，借不到就快速失敗，避免長時間凍結主執行緒
        config.setConnectionTestQuery("SELECT 1");
        return config;
    }


    /*--------------------------------------------------
                     新增DataBase
     --------------------------------------------------*/
    private static boolean createTable() {
        String resource = type.getSchemaResource();

        try (InputStream inputStream = Main.getInst().getResource(resource)) {
            if (inputStream == null) {
                logger.severe("❌ 找不到建表 SQL 資源: " + resource);
                return false;
            }

            String sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = dataSource.getConnection()) {
                for (String statement : splitStatements(sql)) {
                    try (PreparedStatement pstmt = conn.prepareStatement(statement)) {
                        pstmt.execute();
                    }
                }
            }
            logger.info("✅ 成功執行 " + resource);
            return true;
        } catch (Exception e) {
            logger.severe("❌ 執行 " + resource + " 失敗: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 將 SQL 檔切成可逐句執行的語句：去除整行註解後以分號切割。
     * schema 檔中不可出現字面分號（字串常值內）或 BEGIN ... END 區塊。
     */
    private static List<String> splitStatements(String sql) {
        String noComments = Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));

        return Arrays.stream(noComments.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    /**
     * 關閉資料庫連線池
     */
    public static void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            logger.info("Database connection closed.");
        }
    }

}
