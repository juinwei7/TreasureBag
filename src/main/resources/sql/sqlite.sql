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

-- 清掉舊版建立過的 trigger（如存在）；schema 檔不可含 BEGIN ... END 區塊，
-- 因為 splitStatements() 以分號切割語句
DROP TRIGGER IF EXISTS player_info_touch_last_update;
