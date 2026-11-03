package org.weiwei.treasureBag.data;

import org.bukkit.entity.Player;
import org.weiwei.treasureBag.dataBase.DataBase;
import org.weiwei.treasureBag.entity.PlayerBag;
import org.weiwei.treasureBag.entity.PlayerInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class PlayerBagRepository {

    public PlayerInfo getOrCreatePlayerInfo(Player player) {
        return getOrCreatePlayerInfo(player.getUniqueId(), player.getName());
    }

    /**
     * 支援離線玩家的版本（UUID + 名稱）
     * 已有資料且名稱沒變就只讀不寫，名稱改了才 UPDATE
     */
    public PlayerInfo getOrCreatePlayerInfo(UUID uuid, String playerName) {
        String displayName = playerName != null ? playerName : uuid.toString();

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            PlayerInfo playerInfo = findPlayerInfo(connection, uuid);
            if (playerInfo != null) {
                if (playerName != null && !playerName.equals(playerInfo.getPlayerName())) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE player_info SET player_name = ?, last_update_at = CURRENT_TIMESTAMP WHERE player_uuid = ?"
                    )) {
                        update.setString(1, playerName);
                        update.setString(2, uuid.toString());
                        update.executeUpdate();
                    }
                    playerInfo.setPlayerName(playerName);
                }
                return playerInfo;
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_info (player_name, player_uuid) VALUES (?, ?)"
            )) {
                insert.setString(1, displayName);
                insert.setString(2, uuid.toString());
                insert.executeUpdate();
            } catch (Exception insertEx) {
                // 並發情況下可能重複 INSERT（唯一鍵衝突），嘗試再次查詢
                PlayerInfo retry = findPlayerInfo(connection, uuid);
                if (retry != null) return retry;
                throw insertEx;
            }

            // INSERT 成功，以 UUID 查詢並回傳（不依賴 generated keys）
            return findPlayerInfo(connection, uuid);
        } catch (Exception e) {
            throw new IllegalStateException("無法建立玩家背包資料: " + displayName, e);
        }
    }

    public List<PlayerInfo> loadPlayerInfos() {
        List<PlayerInfo> playerInfos = new ArrayList<>();

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, player_name, player_uuid, created_at, last_update_at FROM player_info"
            )) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        playerInfos.add(mapPlayerInfo(resultSet));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法讀取所有玩家資料", e);
        }

        return playerInfos;
    }

    public PlayerInfo findPlayerInfoByName(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, player_name, player_uuid, created_at, last_update_at FROM player_info WHERE LOWER(player_name) = LOWER(?)"
            )) {
                statement.setString(1, playerName);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return null;
                    return mapPlayerInfo(resultSet);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法讀取玩家資料: " + playerName, e);
        }
    }

    public List<PlayerBag> loadPlayerBags(PlayerInfo playerInfo) {
        List<PlayerBag> playerBags = new ArrayList<>();
        if (playerInfo == null || playerInfo.getPlayerUUID() == null) return playerBags;

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid, solder, serialize, itemName FROM player_bag WHERE player_uuid = ?"
            )) {
                statement.setString(1, playerInfo.getPlayerUUID().toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        playerBags.add(mapPlayerBag(resultSet));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法讀取玩家背包資料: " + playerInfo.getPlayerName(), e);
        }

        return playerBags;
    }

    public void saveAllowedPlayerBags(PlayerInfo playerInfo, List<PlayerBag> playerBags, int maxSlot) {
        if (playerInfo == null || playerInfo.getPlayerUUID() == null || maxSlot <= 0) return;

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try {
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM player_bag WHERE player_uuid = ? AND solder >= 0 AND solder < ?"
                )) {
                    delete.setString(1, playerInfo.getPlayerUUID().toString());
                    delete.setInt(2, maxSlot);
                    delete.executeUpdate();
                }

                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO player_bag (player_uuid, solder, serialize, itemName) VALUES (?, ?, ?, ?)"
                )) {
                    for (PlayerBag playerBag : playerBags) {
                        if (playerBag == null || playerBag.getPlayerUUID() == null || playerBag.getSolder() == null || playerBag.getSerialize() == null) {
                            continue;
                        }

                        insert.setString(1, playerBag.getPlayerUUID().toString());
                        insert.setLong(2, playerBag.getSolder());
                        insert.setString(3, playerBag.getSerialize());
                        insert.setString(4, playerBag.getItemName());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }

                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法保存玩家背包資料: " + playerInfo.getPlayerName(), e);
        }
    }

    public void deletePlayerBagsFromSlot(PlayerInfo playerInfo, int startSlot) {
        if (playerInfo == null || playerInfo.getPlayerUUID() == null) return;

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM player_bag WHERE player_uuid = ? AND solder >= ?"
            )) {
                statement.setString(1, playerInfo.getPlayerUUID().toString());
                statement.setInt(2, startSlot);
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法刪除玩家超出背包格資料: " + playerInfo.getPlayerName(), e);
        }
    }

    /**
     * 只寫有變動的格位，包在單一交易中
     * <p>
     * 一般存檔用非嚴格模式（不先讀資料庫，直接覆寫）；
     * 失敗重試與開機補寫用嚴格模式：資料庫目前的內容要等於變更記下的原值才寫，
     * 不相等代表這段時間別台已經改過，該格跳過並回傳給呼叫端處理。
     *
     * @param changes 格位變更
     * @param strict  是否比對原值
     * @return 嚴格模式下比對不符而沒寫入的格位
     */
    public List<BagChanges.SlotChange> applyChanges(BagChanges changes, boolean strict) {
        List<BagChanges.SlotChange> conflicts = new ArrayList<>();
        if (changes == null || changes.isEmpty()) return conflicts;
        String uuid = changes.owner().toString();

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try {
                connection.setAutoCommit(false);
                Map<Integer, String> current = strict ? loadSerializeBySlot(connection, uuid) : Map.of();

                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM player_bag WHERE player_uuid = ? AND solder = ?"
                     );
                     PreparedStatement replace = connection.prepareStatement(
                             "REPLACE INTO player_bag (player_uuid, solder, serialize, itemName) VALUES (?, ?, ?, ?)"
                     )) {
                    boolean hasDelete = false, hasReplace = false;
                    for (BagChanges.SlotChange change : changes.changes()) {
                        if (strict && !Objects.equals(current.get(change.slot()), change.expected())) {
                            conflicts.add(change);
                            continue;
                        }

                        if (change.value() == null) {
                            delete.setString(1, uuid);
                            delete.setLong(2, change.slot());
                            delete.addBatch();
                            hasDelete = true;
                            continue;
                        }

                        replace.setString(1, uuid);
                        replace.setLong(2, change.slot());
                        replace.setString(3, change.value());
                        replace.setString(4, change.itemName());
                        replace.addBatch();
                        hasReplace = true;
                    }
                    if (hasDelete) delete.executeBatch();
                    if (hasReplace) replace.executeBatch();
                }

                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法保存玩家背包資料: " + changes.ownerName(), e);
        }
        return conflicts;
    }

    /**
     * 刪除指定的格位（強制開啟時清掉已掉落到地上的溢出物品）
     */
    public void deleteSlots(UUID playerUUID, Collection<Integer> slots) {
        if (playerUUID == null || slots == null || slots.isEmpty()) return;

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) throw new IllegalStateException("資料庫連線不可用");

            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM player_bag WHERE player_uuid = ? AND solder = ?"
                )) {
                    for (int slot : slots) {
                        statement.setString(1, playerUUID.toString());
                        statement.setLong(2, slot);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法刪除玩家超出背包格資料: " + playerUUID, e);
        }
    }

    private Map<Integer, String> loadSerializeBySlot(Connection connection, String uuid) throws Exception {
        Map<Integer, String> result = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT solder, serialize FROM player_bag WHERE player_uuid = ?"
        )) {
            select.setString(1, uuid);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    result.put((int) resultSet.getLong("solder"), resultSet.getString("serialize"));
                }
            }
        }
        return result;
    }

    private PlayerInfo findPlayerInfo(Connection connection, UUID uuid) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, player_name, player_uuid, created_at, last_update_at FROM player_info WHERE player_uuid = ?"
        )) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) return null;
                return mapPlayerInfo(resultSet);
            }
        }
    }

    private PlayerInfo mapPlayerInfo(ResultSet resultSet) throws Exception {
        PlayerInfo playerInfo = new PlayerInfo();
        playerInfo.setId(resultSet.getLong("id"));
        playerInfo.setPlayerName(resultSet.getString("player_name"));
        playerInfo.setPlayerUUID(UUID.fromString(resultSet.getString("player_uuid")));

        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp lastUpdateAt = resultSet.getTimestamp("last_update_at");
        if (createdAt != null) playerInfo.setCreatedAt(createdAt);
        if (lastUpdateAt != null) playerInfo.setLastUpdateAt(lastUpdateAt);
        return playerInfo;
    }

    private PlayerBag mapPlayerBag(ResultSet resultSet) throws Exception {
        PlayerBag playerBag = new PlayerBag();
        playerBag.setPlayerUUID(UUID.fromString(resultSet.getString("player_uuid")));
        playerBag.setSolder(resultSet.getLong("solder"));
        playerBag.setSerialize(resultSet.getString("serialize"));
        playerBag.setItemName(resultSet.getString("itemName"));
        return playerBag;
    }
}
