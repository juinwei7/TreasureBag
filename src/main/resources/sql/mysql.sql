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

-- player_ore：礦物倉庫（每人每種物品一列，amount 是件數）。數量一律用 amount = amount + ? 增減，
-- 取出用 amount >= ? 條件扣，各分流同時寫也不會互蓋或多扣
CREATE TABLE IF NOT EXISTS `player_ore`
(
    player_uuid    VARCHAR(64) NOT NULL,
    material       VARCHAR(64) NOT NULL,
    amount         BIGINT      NOT NULL DEFAULT 0,
    auto_collect   INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (player_uuid, material)
);

-- player_ore_setting：礦物倉庫的總開關與個別容量（space 為 NULL 表示用 Config.yml 的預設）
CREATE TABLE IF NOT EXISTS `player_ore_setting`
(
    player_uuid    VARCHAR(64) NOT NULL PRIMARY KEY,
    enabled        INT         NOT NULL DEFAULT 1,
    space          BIGINT
);

-- ore_import：已匯入的 ExtraStorage 資料檔（分流名稱＋檔案 MD5），同一份檔案只匯入一次
CREATE TABLE IF NOT EXISTS `ore_import`
(
    source         VARCHAR(128) NOT NULL PRIMARY KEY,
    players        INT          NOT NULL,
    created_at     BIGINT       NOT NULL
);
