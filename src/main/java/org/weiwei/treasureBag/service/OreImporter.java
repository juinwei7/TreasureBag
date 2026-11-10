package org.weiwei.treasureBag.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.sqlite.SQLiteConfig;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.data.VaultRepository;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 把這台 ExtraStorage 的資料（plugins/ExtraStorage/extrastorage.db，SQLite）匯入礦物倉庫
 * <p>
 * ExtraStorage 每台各存一份、互不相通，所以每台各匯入自己那份，數量加進中央資料庫（三台合計）。
 * 舊版留下的鍵有兩種寫法（COAL 與 COAL:0、LAPIS_LAZULI:4），冒號後面去掉合併成同一種。
 * 整份同一筆交易；ore_import 記「分流:檔案 MD5」，同一份檔案不會匯入兩次，匯入完把檔案改名成 .imported。
 * ExtraStorage 還在跑就不匯入（不然匯入後玩家還能從舊倉庫取出，等於複製）。
 * 對不到原版物品的鍵（自製物品前綴、改名過的物品）不匯入，寫成 ore-import-unknown-&lt;分流&gt;.yml 給管理員處理。
 */
public final class OreImporter {

    private static final String DATA_FILE = "ExtraStorage/extrastorage.db";
    private static final String CONFIG_FILE = "ExtraStorage/config.yml";
    private static final String DEFAULT_TABLE = "exstorage_data";
    private static final long OLD_DEFAULT_SPACE = 100_000;

    private OreImporter() {
    }

    private record Unknown(String owner, String key, long amount) {
    }

    /**
     * 有舊資料就匯入（開機時在主執行緒跑：玩家還進不來，不會看到匯入一半的倉庫）
     *
     * @return 結果說明（沒有檔案時回傳 null）
     */
    public static String run() {
        File plugins = Main.getInst().getDataFolder().getParentFile();
        File file = new File(plugins, DATA_FILE);
        if (!file.isFile()) return null;
        if (Bukkit.getPluginManager().getPlugin("ExtraStorage") != null) {
            String message = "ExtraStorage 還裝著，先不匯入礦物倉庫（移除 ExtraStorage 後重開，或用 /bag oreadmin import）";
            Main.getInst().getLogger().warning(message);
            return message;
        }

        try {
            YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(new File(plugins, CONFIG_FILE));
            String table = oldConfig.getString("Database.Table", DEFAULT_TABLE);
            long oldDefaultSpace = oldConfig.getLong("MaxSpace", OLD_DEFAULT_SPACE);
            if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("ExtraStorage 資料表名稱不合法: " + table);

            List<VaultRepository.Imported> players = new ArrayList<>();
            List<Unknown> unknown = new ArrayList<>();
            int skipped = read(file, table, oldDefaultSpace, players, unknown);

            String server = BagStorage.serverName();
            String source = server + ":" + md5(file);
            boolean done = importWithRetry(source, players, server);
            if (!unknown.isEmpty()) writeUnknown(server, unknown);

            File imported = new File(file.getParentFile(), file.getName() + ".imported");
            if (imported.exists()) imported = new File(file.getParentFile(), file.getName() + ".imported-" + System.currentTimeMillis());
            if (!file.renameTo(imported)) {
                Main.getInst().getLogger().warning("ExtraStorage 資料檔改名失敗（已記錄匯入過，不會重複匯入）: " + file.getPath());
            }

            long items = players.stream().flatMap(player -> player.amounts().values().stream()).mapToLong(Long::longValue).sum();
            String message = done
                    ? "已匯入 ExtraStorage：" + players.size() + " 位玩家、" + String.format("%,d", items) + " 件（略過 "
                    + skipped + " 位沒有存貨的；對不到物品的鍵 " + unknown.size() + " 筆）"
                    : "這份 ExtraStorage 資料之前已經匯入過（" + source + "），沒有重複匯入";
            Main.getInst().getLogger().info(message);
            return message;
        } catch (Exception e) {
            BagStorage.logSevere("匯入 ExtraStorage 失敗，資料檔保留，下次啟動或 /bag oreadmin import 再試: " + file.getPath(), e);
            return "匯入失敗：" + e.getMessage();
        }
    }

    /**
     * 整份同一筆交易；玩家剛好在別台建立倉庫會撞主鍵、整筆退回，重讀再來（最多 3 次）
     */
    private static boolean importWithRetry(String source, List<VaultRepository.Imported> players, String server) {
        VaultRepository repository = VaultRepository.ORE;
        for (int attempt = 1; ; attempt++) {
            try {
                return repository.importPlayers(source, players, server);
            } catch (IllegalStateException e) {
                if (attempt >= 3) throw e;
                Main.getInst().getLogger().warning("匯入 ExtraStorage 第 " + attempt + " 次失敗，重試: " + e.getMessage());
            }
        }
    }

    /**
     * 讀整份資料，合併同一種物品的兩種寫法
     *
     * @return 略過（全部是 0、沒改過設定）的玩家數
     */
    private static int read(File file, String table, long oldDefaultSpace,
                            List<VaultRepository.Imported> players, List<Unknown> unknown) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        int skipped = 0;
        try (Connection connection = config.createConnection("jdbc:sqlite:" + file.getAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT uuid, status, space, filter, unfilter FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String uuid = resultSet.getString(1);
                boolean enabled = resultSet.getInt(2) != 0;
                long space = resultSet.getLong(3);
                Map<String, Long> amounts = new LinkedHashMap<>();
                Set<String> auto = new HashSet<>();
                collect(uuid, resultSet.getString(4), amounts, auto, unknown);
                collect(uuid, resultSet.getString(5), amounts, null, unknown);

                Long customSpace = space == oldDefaultSpace ? null : space;
                boolean empty = amounts.values().stream().allMatch(amount -> amount == 0);
                if (empty && enabled && customSpace == null) {
                    // 沒有存貨也沒改過設定：第一次打開時會拿到預設清單
                    skipped++;
                    continue;
                }
                players.add(new VaultRepository.Imported(UUID.fromString(uuid), enabled, customSpace, amounts, auto));
            }
        }
        return skipped;
    }

    /**
     * 解析 {"COAL:0":123,...}，鍵去掉冒號後面合併
     *
     * @param auto 這一欄是自動收集清單就傳入，數量以外也記下要開自動收集
     */
    private static void collect(String owner, String json, Map<String, Long> amounts, Set<String> auto, List<Unknown> unknown) {
        if (json == null || json.isBlank()) return;
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            long amount = entry.getValue().getAsLong();
            int colon = key.indexOf(':');
            Material material = Material.matchMaterial(colon >= 0 ? key.substring(0, colon) : key);
            if (material == null || !material.isItem() || material.isAir()) {
                if (amount > 0) unknown.add(new Unknown(owner, key, amount));
                continue;
            }
            amounts.merge(material.name(), Math.max(0, amount), Long::sum);
            if (auto != null) auto.add(material.name());
        }
    }

    private static void writeUnknown(String server, List<Unknown> unknown) {
        YamlConfiguration yaml = new YamlConfiguration();
        Map<String, Integer> counter = new HashMap<>();
        for (Unknown item : unknown) {
            int index = counter.merge(item.owner(), 1, Integer::sum);
            String path = item.owner() + "." + index;
            yaml.set(path + ".key", item.key());
            yaml.set(path + ".amount", item.amount());
        }
        File out = new File(Main.getInst().getDataFolder(), "ore-import-unknown-" + server + ".yml");
        try {
            yaml.save(out);
            Main.getInst().getLogger().warning("有 " + unknown.size() + " 筆 ExtraStorage 資料對不到原版物品，沒有匯入，已存到 " + out.getPath());
        } catch (Exception e) {
            BagStorage.logSevere("無法寫入對不到物品的匯入清單: " + unknown, e);
        }
    }

    private static String md5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
