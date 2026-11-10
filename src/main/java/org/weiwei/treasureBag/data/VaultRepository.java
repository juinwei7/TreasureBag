package org.weiwei.treasureBag.data;

import org.weiwei.treasureBag.dataBase.DataBase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 倉庫的資料庫存取，兩種資料庫通用的 SQL：礦物倉庫（player_ore、player_ore_setting）、
 * 副本素材倉庫（player_material、player_material_setting），兩種倉庫共用 ore_import（匯入與補寫的記號）
 * <p>
 * 數量只用 {@code amount = amount + ?} 增減、取出用 {@code amount >= ?} 條件扣，
 * 同一位玩家在不同分流同時寫入也不會互相覆蓋或多扣。連線不可用一律拋例外，呼叫端靠它決定重試。
 * 兩種倉庫的欄位一樣，material 欄放物品的鍵（原版材質名稱或 MythicMobs 物品代號）。
 */
public class VaultRepository {

    /** ExtraStorage 舊資料匯入時寫進 player_bag_log 的來源（只有礦物倉庫） */
    public static final String SOURCE_IMPORT = "ore_import";

    public static final VaultRepository ORE = new VaultRepository("player_ore", "player_ore_setting", "ore");
    public static final VaultRepository MATERIAL = new VaultRepository("player_material", "player_material_setting", "material");

    private final String table;
    private final String settingTable;
    /** 存入、取出時寫進 player_bag_log 的來源（ore、material） */
    public final String sourceDeposit;
    /** 取出的東西放不下、退回倉庫 */
    public final String sourceReturn;
    /** 管理員補發、扣除 */
    public final String sourceAdmin;

    private VaultRepository(String table, String settingTable, String source) {
        this.table = table;
        this.settingTable = settingTable;
        this.sourceDeposit = source;
        this.sourceReturn = source + "_return";
        this.sourceAdmin = source + "_admin";
    }

    /**
     * 倉庫裡的一種物品
     */
    public record Row(String material, long amount, boolean autoCollect) {
    }

    /**
     * 讀出來的倉庫
     *
     * @param space 個別容量，null 表示用預設
     */
    public record Account(boolean enabled, Long space, List<Row> rows) {
    }

    /**
     * 一筆增加：deltas 為物品種類 → 增加的件數（都是正數）
     *
     * @param autoForNew 倉庫原本沒有這種物品時，新的一列要不要開自動收集
     * @param log        要不要寫存取紀錄（自動收集太頻繁，不寫）
     */
    public record Change(UUID owner, UUID viewer, String viewerName, String source,
                         Map<String, Long> deltas, boolean autoForNew, boolean log) {
    }

    /**
     * 匯入的一位玩家
     *
     * @param amounts 物品種類 → 件數（可為 0，代表只在清單上）
     * @param auto    要開自動收集的種類
     * @param space   個別容量，null 表示用預設
     */
    public record Imported(UUID owner, boolean enabled, Long space, Map<String, Long> amounts, Set<String> auto) {
    }

    /**
     * 讀取倉庫；第一次用（沒有設定列）就建立預設清單
     */
    public Account load(UUID owner, List<String> defaults) {
        try (Connection connection = connection()) {
            Account account = read(connection, owner);
            if (account != null) return account;

            if (createDefaults(connection, owner, defaults)) return read(connection, owner);
            // 別台剛好同時建好了
            Account retry = read(connection, owner);
            if (retry == null) throw new IllegalStateException("無法建立倉庫 " + table + ": " + owner);
            return retry;
        } catch (SQLException e) {
            throw new IllegalStateException("無法讀取倉庫 " + table + ": " + owner, e);
        }
    }

    private Account read(Connection connection, UUID owner) throws SQLException {
        boolean enabled;
        Long space;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled, space FROM " + settingTable + " WHERE player_uuid = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                enabled = resultSet.getInt(1) != 0;
                long value = resultSet.getLong(2);
                space = resultSet.wasNull() ? null : value;
            }
        }

        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT material, amount, auto_collect FROM " + table + " WHERE player_uuid = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new Row(resultSet.getString(1), resultSet.getLong(2), resultSet.getInt(3) != 0));
                }
            }
        }
        return new Account(enabled, space, rows);
    }

    /**
     * 建立設定列與預設清單（已經有的種類不動）；設定列被別台搶先建立就回傳 false
     */
    private boolean createDefaults(Connection connection, UUID owner, List<String> defaults) throws SQLException {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + settingTable + " (player_uuid, enabled, space) VALUES (?, 1, NULL)")) {
                insert.setString(1, owner.toString());
                insert.executeUpdate();
            } catch (SQLException duplicate) {
                connection.rollback();
                return false;
            }

            Set<String> existing = materials(connection, owner);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + table + " (player_uuid, material, amount, auto_collect) VALUES (?, ?, 0, 1)")) {
                boolean any = false;
                for (String material : defaults) {
                    if (existing.contains(material)) continue;
                    insert.setString(1, owner.toString());
                    insert.setString(2, material);
                    insert.addBatch();
                    any = true;
                }
                if (any) insert.executeBatch();
            }
            connection.commit();
            return true;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * 增加數量（沒有這一列就建立），同一筆交易寫存取紀錄
     */
    public void add(Change change, String server) {
        if (change.deltas().isEmpty()) return;
        try (Connection connection = connection()) {
            try {
                connection.setAutoCommit(false);
                for (Map.Entry<String, Long> entry : change.deltas().entrySet()) {
                    increment(connection, change.owner(), entry.getKey(), entry.getValue(), change.autoForNew());
                }
                if (change.log()) {
                    Map<String, Long> logs = new HashMap<>(change.deltas());
                    insertLogs(connection, change.owner(), change.viewer(), change.viewerName(), server, change.source(), logs);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("無法寫入倉庫 " + table + ": " + change.owner(), e);
        }
    }

    /**
     * 增加數量，並在同一筆交易把 marker 記進 ore_import；marker 已經記過就什麼都不做並回傳 false
     * （開機補寫暫存檔用：補寫成功但檔案改名失敗，下次開機也不會重複加）
     */
    public boolean addOnce(Change change, String server, String marker) {
        try (Connection connection = connection()) {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement mark = connection.prepareStatement(
                        "INSERT INTO ore_import (source, players, created_at) VALUES (?, 1, ?)")) {
                    mark.setString(1, marker);
                    mark.setLong(2, System.currentTimeMillis());
                    mark.executeUpdate();
                } catch (SQLException duplicate) {
                    connection.rollback();
                    if (imported(connection, marker)) return false;
                    throw duplicate;
                }
                for (Map.Entry<String, Long> entry : change.deltas().entrySet()) {
                    increment(connection, change.owner(), entry.getKey(), entry.getValue(), change.autoForNew());
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("無法補寫倉庫 " + table + ": " + marker, e);
        }
    }

    private void increment(Connection connection, UUID owner, String material, long delta, boolean autoForNew) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET amount = amount + ? WHERE player_uuid = ? AND material = ?")) {
            update.setLong(1, delta);
            update.setString(2, owner.toString());
            update.setString(3, material);
            if (update.executeUpdate() > 0) return;
        }
        // 別台同時建立同一列會撞主鍵，整筆交易失敗後由呼叫端重試，重試時就會走上面的 UPDATE
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (player_uuid, material, amount, auto_collect) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, owner.toString());
            insert.setString(2, material);
            insert.setLong(3, Math.max(0, delta));
            insert.setInt(4, autoForNew ? 1 : 0);
            insert.executeUpdate();
        }
    }

    /**
     * 取出：最多扣 want 件，倉庫不夠就扣光剩下的；回傳實際扣掉的件數（0 表示沒有）
     */
    public long withdraw(UUID owner, String material, long want, UUID viewer, String viewerName, String server, String source) {
        try (Connection connection = connection()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                // 每次重讀都要在交易外：MySQL 同一筆交易裡的 SELECT 會一直讀到第一次的快照
                connection.setAutoCommit(true);
                long take = Math.min(amount(connection, owner, material), want);
                if (take <= 0) return 0;

                connection.setAutoCommit(false);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + table + " SET amount = amount - ? WHERE player_uuid = ? AND material = ? AND amount >= ?")) {
                    update.setLong(1, take);
                    update.setString(2, owner.toString());
                    update.setString(3, material);
                    update.setLong(4, take);
                    if (update.executeUpdate() == 0) {
                        // 讀完到扣之間被別台取走了，重讀一次
                        connection.rollback();
                        continue;
                    }
                    insertLogs(connection, owner, viewer, viewerName, server, source, Map.of(material, -take));
                    connection.commit();
                    return take;
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                }
            }
            return 0;
        } catch (SQLException e) {
            throw new IllegalStateException("無法從倉庫取出 " + table + ": " + owner + " " + material, e);
        }
    }

    /**
     * 開關某種物品的自動收集（沒有這一列就建立一列數量 0 的）
     */
    public void setAutoCollect(UUID owner, String material, boolean auto) {
        try (Connection connection = connection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + table + " SET auto_collect = ? WHERE player_uuid = ? AND material = ?")) {
                update.setInt(1, auto ? 1 : 0);
                update.setString(2, owner.toString());
                update.setString(3, material);
                if (update.executeUpdate() > 0) return;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + table + " (player_uuid, material, amount, auto_collect) VALUES (?, ?, 0, ?)")) {
                insert.setString(1, owner.toString());
                insert.setString(2, material);
                insert.setInt(3, auto ? 1 : 0);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("無法更新倉庫自動收集 " + table + ": " + owner + " " + material, e);
        }
    }

    /**
     * 從清單移除；只有資料庫裡數量是 0 才刪，回傳是否刪掉
     */
    public boolean removeEmpty(UUID owner, String material) {
        try (Connection connection = connection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE player_uuid = ? AND material = ? AND amount = 0")) {
            delete.setString(1, owner.toString());
            delete.setString(2, material);
            return delete.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("無法移除倉庫物品 " + table + ": " + owner + " " + material, e);
        }
    }

    public void setEnabled(UUID owner, boolean enabled) {
        try (Connection connection = connection()) {
            ensureSetting(connection, owner);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + settingTable + " SET enabled = ? WHERE player_uuid = ?")) {
                update.setInt(1, enabled ? 1 : 0);
                update.setString(2, owner.toString());
                update.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("無法更新倉庫開關 " + table + ": " + owner, e);
        }
    }

    /**
     * 設定個別容量；space 為 null 表示改回預設
     */
    public void setSpace(UUID owner, Long space) {
        try (Connection connection = connection()) {
            ensureSetting(connection, owner);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + settingTable + " SET space = ? WHERE player_uuid = ?")) {
                if (space == null) update.setNull(1, Types.BIGINT);
                else update.setLong(1, space);
                update.setString(2, owner.toString());
                update.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("無法更新倉庫容量 " + table + ": " + owner, e);
        }
    }

    /**
     * 匯入一份 ExtraStorage 資料：整份同一筆交易，source 已匯入過就什麼都不做並回傳 false
     */
    public boolean importPlayers(String source, List<Imported> players, String server) {
        try (Connection connection = connection()) {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement mark = connection.prepareStatement(
                        "INSERT INTO ore_import (source, players, created_at) VALUES (?, ?, ?)")) {
                    mark.setString(1, source);
                    mark.setInt(2, players.size());
                    mark.setLong(3, System.currentTimeMillis());
                    mark.executeUpdate();
                } catch (SQLException duplicate) {
                    connection.rollback();
                    if (imported(connection, source)) return false;
                    throw duplicate;
                }

                for (Imported player : players) importPlayer(connection, player, server);
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("無法匯入 ExtraStorage 資料: " + source, e);
        }
    }

    private void importPlayer(Connection connection, Imported player, String server) throws SQLException {
        String uuid = player.owner().toString();
        Boolean enabled = null;
        Long space = null;
        boolean hasSetting = false;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT enabled, space FROM " + settingTable + " WHERE player_uuid = ?")) {
            select.setString(1, uuid);
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) {
                    hasSetting = true;
                    enabled = resultSet.getInt(1) != 0;
                    long value = resultSet.getLong(2);
                    space = resultSet.wasNull() ? null : value;
                }
            }
        }

        // 多台合併：任一台關掉就關、容量取比較小的
        boolean mergedEnabled = player.enabled() && (enabled == null || enabled);
        Long mergedSpace = space == null ? player.space()
                : player.space() == null ? space : Long.valueOf(Math.min(space, player.space()));
        try (PreparedStatement statement = connection.prepareStatement(hasSetting
                ? "UPDATE " + settingTable + " SET enabled = ?, space = ? WHERE player_uuid = ?"
                : "INSERT INTO " + settingTable + " (enabled, space, player_uuid) VALUES (?, ?, ?)")) {
            statement.setInt(1, mergedEnabled ? 1 : 0);
            if (mergedSpace == null) statement.setNull(2, Types.BIGINT);
            else statement.setLong(2, mergedSpace);
            statement.setString(3, uuid);
            statement.executeUpdate();
        }

        Set<String> existing = materials(connection, player.owner());
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET amount = amount + ?, auto_collect = CASE WHEN ? = 1 THEN 1 ELSE auto_collect END"
                        + " WHERE player_uuid = ? AND material = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + table + " (player_uuid, material, amount, auto_collect) VALUES (?, ?, ?, ?)")) {
            boolean hasUpdate = false, hasInsert = false;
            for (Map.Entry<String, Long> entry : player.amounts().entrySet()) {
                int auto = player.auto().contains(entry.getKey()) ? 1 : 0;
                if (existing.contains(entry.getKey())) {
                    update.setLong(1, entry.getValue());
                    update.setInt(2, auto);
                    update.setString(3, uuid);
                    update.setString(4, entry.getKey());
                    update.addBatch();
                    hasUpdate = true;
                    continue;
                }
                insert.setString(1, uuid);
                insert.setString(2, entry.getKey());
                insert.setLong(3, entry.getValue());
                insert.setInt(4, auto);
                insert.addBatch();
                hasInsert = true;
            }
            if (hasUpdate) update.executeBatch();
            if (hasInsert) insert.executeBatch();
        }

        Map<String, Long> logs = new HashMap<>();
        player.amounts().forEach((material, amount) -> {
            if (amount > 0) logs.put(material, amount);
        });
        insertLogs(connection, player.owner(), null, null, server, SOURCE_IMPORT, logs);
    }

    private boolean imported(Connection connection, String source) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM ore_import WHERE source = ?")) {
            select.setString(1, source);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void ensureSetting(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM " + settingTable + " WHERE player_uuid = ?")) {
            select.setString(1, owner.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + settingTable + " (player_uuid, enabled, space) VALUES (?, 1, NULL)")) {
            insert.setString(1, owner.toString());
            insert.executeUpdate();
        } catch (SQLException duplicate) {
            // 別台剛好同時建立，已經有就好
        }
    }

    private Set<String> materials(Connection connection, UUID owner) throws SQLException {
        Set<String> materials = new HashSet<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT material FROM " + table + " WHERE player_uuid = ?")) {
            select.setString(1, owner.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) materials.add(resultSet.getString(1));
            }
        }
        return materials;
    }

    private long amount(Connection connection, UUID owner, String material) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT amount FROM " + table + " WHERE player_uuid = ? AND material = ?")) {
            select.setString(1, owner.toString());
            select.setString(2, material);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    /**
     * 寫進背包的存取紀錄表（amount 正數放入、負數取出），/bag log 一起查得到
     */
    private void insertLogs(Connection connection, UUID owner, UUID viewer, String viewerName, String server,
                            String source, Map<String, Long> amounts) throws SQLException {
        if (amounts.isEmpty()) return;
        long now = System.currentTimeMillis();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO player_bag_log (player_uuid, viewer_uuid, viewer_name, server, source, material, item_name, amount, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Map.Entry<String, Long> entry : amounts.entrySet()) {
                insert.setString(1, owner.toString());
                insert.setString(2, viewer != null ? viewer.toString() : null);
                insert.setString(3, viewerName);
                insert.setString(4, server);
                insert.setString(5, source);
                insert.setString(6, entry.getKey());
                insert.setString(7, null);
                insert.setInt(8, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, entry.getValue())));
                insert.setLong(9, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Connection connection() {
        Connection connection = DataBase.getConnection();
        if (connection == null) throw new IllegalStateException("資料庫連線不可用");
        return connection;
    }
}
