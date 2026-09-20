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
--   2. SQLite 沒有 ON UPDATE CURRENT_TIMESTAMP，改用下方 trigger 補上
CREATE TABLE IF NOT EXISTS `player_info`
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    player_name    VARCHAR(255) NOT NULL,
    player_uuid    VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 取代 MySQL 的 ON UPDATE CURRENT_TIMESTAMP
-- SQLite 預設關閉 recursive_triggers，因此在 trigger 內再次 UPDATE 同一張表不會遞迴
CREATE TRIGGER IF NOT EXISTS player_info_touch_last_update
    AFTER UPDATE ON player_info
BEGIN
    UPDATE player_info SET last_update_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;
