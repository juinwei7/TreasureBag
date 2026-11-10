-- player_bag
-- 與 MySQL 版完全相同：SQLite 接受反引號、VARCHAR / BIGINT / LONGTEXT 型別名與複合主鍵
CREATE TABLE IF NOT EXISTS `player_bag`
(
    player_uuid    VARCHAR(64) NOT NULL,
    solder         BIGINT NOT NULL,
    serialize      LONGTEXT NOT NULL,
    itemName       TEXT,
    PRIMARY KEY (player_uuid, solder)
);

-- player_info
-- 與 MySQL 版有兩處差異：
--   1. AUTOINCREMENT 只允許用在 INTEGER PRIMARY KEY，不可寫成 BIGINT
--   2. SQLite 沒有 ON UPDATE CURRENT_TIMESTAMP：last_update_at 改由
--      PlayerBagRepository 的 UPDATE 明確寫入（該寫法兩方言通用）
CREATE TABLE IF NOT EXISTS `player_info`
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    player_name    VARCHAR(255) NOT NULL,
    player_uuid    VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- player_bag_page：與 MySQL 版相同
CREATE TABLE IF NOT EXISTS `player_bag_page`
(
    player_uuid    VARCHAR(64) NOT NULL,
    page           INT         NOT NULL,
    name           VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_uuid, page)
);

-- player_bag_log：AUTOINCREMENT 只能用 INTEGER PRIMARY KEY；SQLite 不支援在 CREATE TABLE 裡寫 INDEX，另外建
CREATE TABLE IF NOT EXISTS `player_bag_log`
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid    VARCHAR(64) NOT NULL,
    viewer_uuid    VARCHAR(64),
    viewer_name    VARCHAR(64),
    server         VARCHAR(64),
    source         VARCHAR(16) NOT NULL,
    material       VARCHAR(64) NOT NULL,
    item_name      TEXT,
    amount         INT         NOT NULL,
    created_at     BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bag_log_owner ON player_bag_log (player_uuid, created_at);
CREATE INDEX IF NOT EXISTS idx_bag_log_time ON player_bag_log (created_at);


-- player_ore：與 MySQL 版相同。礦物倉庫（每人每種物品一列，amount 是件數）。數量一律用 amount = amount + ? 增減，
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

-- player_material：與 MySQL 版相同。副本素材倉庫（每人每種素材一列，material 放 MythicMobs 物品代號、amount 是件數），加減規則同 player_ore
CREATE TABLE IF NOT EXISTS `player_material`
(
    player_uuid    VARCHAR(64) NOT NULL,
    material       VARCHAR(64) NOT NULL,
    amount         BIGINT      NOT NULL DEFAULT 0,
    auto_collect   INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (player_uuid, material)
);

-- player_material_setting：副本素材倉庫的總開關與個別容量（space 為 NULL 表示照 VIP 階級）
CREATE TABLE IF NOT EXISTS `player_material_setting`
(
    player_uuid    VARCHAR(64) NOT NULL PRIMARY KEY,
    enabled        INT         NOT NULL DEFAULT 1,
    space          BIGINT
);

-- ore_import：已匯入的 ExtraStorage 資料檔（分流名稱＋檔案 MD5），同一份檔案只匯入一次；兩種倉庫開機補寫暫存檔的記號也記在這裡
CREATE TABLE IF NOT EXISTS `ore_import`
(
    source         VARCHAR(128) NOT NULL PRIMARY KEY,
    players        INT          NOT NULL,
    created_at     BIGINT       NOT NULL
);

-- 清掉舊版建立過的 trigger（如存在）；schema 檔不可含 BEGIN ... END 區塊，
-- 因為 splitStatements() 以分號切割語句
DROP TRIGGER IF EXISTS player_info_touch_last_update;
