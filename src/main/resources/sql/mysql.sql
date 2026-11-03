-- player_bag
CREATE TABLE IF NOT EXISTS `player_bag`
(
    player_uuid    VARCHAR(64) NOT NULL,
    solder         BIGINT NOT NULL,
    serialize      LONGTEXT NOT NULL,
    itemName       TEXT,
    PRIMARY KEY (player_uuid, solder)
);

-- player_info
CREATE TABLE IF NOT EXISTS `player_info`
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    player_name    VARCHAR(255) NOT NULL,
    player_uuid    VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- player_bag_page：玩家自訂的頁面名稱（page 為 0 起算）
CREATE TABLE IF NOT EXISTS `player_bag_page`
(
    player_uuid    VARCHAR(64) NOT NULL,
    page           INT         NOT NULL,
    name           VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_uuid, page)
);

-- player_bag_log：存取紀錄，每次關背包每種物品的淨增減（amount 正數放入、負數取出）
-- created_at 存毫秒時間戳，避開兩種資料庫 CURRENT_TIMESTAMP 時區不同的問題
CREATE TABLE IF NOT EXISTS `player_bag_log`
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    player_uuid    VARCHAR(64) NOT NULL,
    viewer_uuid    VARCHAR(64),
    viewer_name    VARCHAR(64),
    server         VARCHAR(64),
    source         VARCHAR(16) NOT NULL,
    material       VARCHAR(64) NOT NULL,
    item_name      TEXT,
    amount         INT         NOT NULL,
    created_at     BIGINT      NOT NULL,
    INDEX idx_bag_log_owner (player_uuid, created_at),
    INDEX idx_bag_log_time (created_at)
);
