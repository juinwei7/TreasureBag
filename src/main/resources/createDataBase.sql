-- player_bag
CREATE TABLE IF NOT EXISTS `player_bag`
(
    player_uuid    VARCHAR(64) NOT NULL,
    solder         BIGINT NOT NULL,
    serialize      LONGTEXT NOT NULL,
    itemName       TEXT,
    PRIMARY KEY (player_uuid, solder)
);

-- Creating table for GiftItem
CREATE TABLE IF NOT EXISTS `player_info`
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    player_name    VARCHAR(255) NOT NULL,
    player_uuid           VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
