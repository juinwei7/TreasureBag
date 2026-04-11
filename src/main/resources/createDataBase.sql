-- player_bag
CREATE TABLE IF NOT EXISTS `player_bag`
(
    player_info_id LONG PRIMARY KEY,
    solder         LONG,
    serialize      BLOB NOT NULL,
    itemName       VARCHAR(200)
);

-- Creating table for GiftItem
CREATE TABLE IF NOT EXISTS `player_info`
(
    id             LONG PRIMARY KEY AUTO_INCREMENT,
    player_name    VARCHAR(255) NOT NULL,
    uuid           VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
