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
import java.util.List;
import java.util.UUID;

public class PlayerBagRepository {

    public PlayerInfo getOrCreatePlayerInfo(Player player) {
        return getOrCreatePlayerInfo(player.getUniqueId(), player.getName());
    }

    /**
     * 支援離線玩家的版本（UUID + 名稱）
     */
    public PlayerInfo getOrCreatePlayerInfo(UUID uuid, String playerName) {
        String displayName = playerName != null ? playerName : uuid.toString();

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) return null;

            if (playerName != null) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE player_info SET player_name = ? WHERE player_uuid = ?"
                )) {
                    update.setString(1, playerName);
                    update.setString(2, uuid.toString());
                    update.executeUpdate();
                }
            }

            PlayerInfo playerInfo = findPlayerInfo(connection, uuid);
            if (playerInfo != null) return playerInfo;

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_info (player_name, player_uuid) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            )) {
                insert.setString(1, displayName);
                insert.setString(2, uuid.toString());
                insert.executeUpdate();

                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) return findPlayerInfo(connection, uuid);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法建立玩家背包資料: " + displayName, e);
        }

        return null;
    }

    public List<PlayerInfo> loadPlayerInfos() {
        List<PlayerInfo> playerInfos = new ArrayList<>();

        try (Connection connection = DataBase.getConnection()) {
            if (connection == null) return playerInfos;

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
            if (connection == null) return null;

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
            if (connection == null) return playerBags;

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
            if (connection == null) return;

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
            if (connection == null) return;

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
