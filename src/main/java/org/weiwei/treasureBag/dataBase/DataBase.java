package org.weiwei.treasureBag.dataBase;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;
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
         * 解析設定檔的 type 欄位；無法辨識或未設定時回傳 MYSQL，
         * 讓升級前就存在的 DataBase.yml 維持原本行為
         */
        private static DataBaseType parse(String raw) {
            if (raw == null || raw.isBlank()) return MYSQL;

            for (DataBaseType dataBaseType : values()) {
                if (dataBaseType.name().equalsIgnoreCase(raw.trim())) return dataBaseType;
            }
            logger.warning("未知的資料庫類型 [ " + raw + " ]，改用 mysql");
            return MYSQL;
        }
    }


    private static void loadDataBase() {
        File file = new File(Main.getInst().getDataFolder(), "DataBase.yml");
        if (!file.exists()) {
            Main.getInst().getLogger().info("Create DataBase.yml");
            Main.getInst().saveResource("DataBase.yml", true);
        }
        dataBaseConfig = YamlConfiguration.loadConfiguration(file);
    }

    // 獲取資料庫連線
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
     */
    public static boolean initialize() {
        DataBase.loadDataBase();

        if (dataBaseConfig == null) {
            logger.warning("資料庫連線失敗");
            return false;
        }

        type = DataBaseType.parse(dataBaseConfig.getString("type"));

        try {
            HikariConfig config = (type == DataBaseType.SQLITE) ? buildSqliteConfig() : buildMysqlConfig();
            dataSource = new HikariDataSource(config);
            logger.info(type.name() + " database connected successfully!");

            createTable();
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
        File folder = Main.getInst().getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("無法建立插件資料夾: " + folder.getAbsolutePath());
        }

        String fileName = dataBaseConfig.getString("sqlite.file", "treasure_bag.db");
        File dataBaseFile = new File(folder, fileName);

        HikariConfig config = new HikariConfig();
        // journal_mode=WAL 讓讀寫不互相阻塞；busy_timeout 讓短暫鎖競爭自動重試而非直接失敗
        config.setJdbcUrl("jdbc:sqlite:" + dataBaseFile.getAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000");
        // 明確指定 driver：sqlite-jdbc 已 shade 進插件 jar，
        // 但 Bukkit 的 classloader 下交給 DriverManager 自行搜尋不一定找得到
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName(dataBaseFile.getName());

        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setConnectionTestQuery("SELECT 1");
        return config;
    }


    /*--------------------------------------------------
                     新增DataBase
     --------------------------------------------------*/
    private static void createTable() {
        String resource = type.getSchemaResource();

        try (InputStream inputStream = Main.getInst().getResource(resource)) {
            if (inputStream == null) {
                Main.getInst().getLogger().severe("❌ 找不到建表 SQL 資源: " + resource);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String sql = reader.lines().collect(Collectors.joining("\n"));

                try (Connection conn = dataSource.getConnection()) {
                    for (String statement : splitStatements(sql)) {
                        try (PreparedStatement pstmt = conn.prepareStatement(statement)) {
                            pstmt.execute();
                        }
                    }
                }
            }
            Main.getInst().getLogger().info("✅ 成功執行 " + resource);
        } catch (Exception e) {
            Main.getInst().getLogger().severe("❌ 執行 " + resource + " 失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static final Pattern BLOCK_BEGIN = Pattern.compile("\\bBEGIN\\b");
    private static final Pattern BLOCK_END = Pattern.compile("\\bEND\\b");

    /**
     * 將 SQL 檔切成可逐句執行的語句，並略過 BEGIN ... END 區塊內的分號
     * （SQLite trigger 的 body 帶分號，單純 split(";") 會把語句切爛）
     * <p>
     * 註解行（--）會被移除，因此 schema 中的 BEGIN / END 必須是真正的區塊關鍵字，
     * 不可出現在字串常值或識別名裡。
     *
     * @param sql 整份 SQL 內容
     * @return 去掉結尾分號的語句列表
     */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int blockDepth = 0;

        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;

            current.append(line).append('\n');

            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (BLOCK_BEGIN.matcher(upper).find()) blockDepth++;
            if (BLOCK_END.matcher(upper).find()) blockDepth = Math.max(0, blockDepth - 1);

            if (blockDepth > 0 || !trimmed.endsWith(";")) continue;

            addStatement(statements, current.toString());
            current.setLength(0);
        }

        // 最後一句若沒有分號結尾仍要執行
        addStatement(statements, current.toString());
        return statements;
    }

    private static void addStatement(List<String> statements, String raw) {
        String statement = raw.trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (!statement.isEmpty()) statements.add(statement);
    }

    /**
     * 關閉資料庫連線池
     */
    public static void close() {
        if (dataSource != null) {
            dataSource.close();
            logger.info("Database connection closed.");
        }
    }

}
