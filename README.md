# TreasureBag

Paper 伺服器用的隨身背包插件。玩家可以透過指令開啟自己的隨身背包，背包容量依權限決定，資料可儲存在 MySQL 或 SQLite。

## 功能

- `/treasurebag` 或 `/bag` 直接開啟自己的隨身背包
- `/treasurebag open [玩家名稱]` 開啟自己或指定玩家的背包
- 權限決定玩家可用背包格數
- 支援多頁背包，最後一排為控制列：整理、一鍵存入、頁籤 1～5（點了直接跳頁，超出權限的頁顯示上鎖）、背包資訊
- 整理：依物品種類排好，同種物品自動疊在一起
- 一鍵存入：身上（不含快捷列、裝備、副手）只要背包裡已經有同種的物品，就全部存進去
- 頁面命名：右鍵頁籤後輸入 `/bag name <頁碼> <名稱>`，名稱顯示在頁籤與標題
- 存取紀錄：每次關背包記下每種物品的淨增減（背包內換位置不算），管理員用 `/bag log <玩家>` 查，保留 30 天
- 有裝伺服器材質包的玩家看到材質介面（背景字 U+E0A0、按鈕 CustomModelData 870～880）；沒裝的看到純文字標題與原版物品
- 超出權限可用格數的格子會顯示上鎖，不能放入物品
- 關閉背包時自動保存到資料庫（背景執行緒寫入，只寫有變動的格子，不卡主執行緒）
- 支援從玩家背包 shift-click 快速移入隨身背包
- 禁止把界伏盒、收納袋等可攜式容器放入隨身背包
- 玩家權限降級導致背包容量縮小時，會先警告，不會直接噴出物品
- `/treasurebag force` 可由玩家確認後強制開啟，並將超出容量的物品掉落到玩家腳下
- `/treasurebag trade` 可將 Minepacks 背包資料轉移到 TreasureBag

## 環境需求

- Java 21
- Paper `1.21.11`
- MySQL 或 SQLite（SQLite 不需額外安裝，driver 已內含於插件 jar）
- Minepacks：選用，只有從 Minepacks 匯入資料（`/treasurebag trade`）時需要一起安裝
- Hu_custom：選用，用來讀玩家有沒有裝材質包（Redis 旗標 `hu_custom:pack:<uuid>`）；沒裝就一律當有材質包

## 安裝

1. 將建置完成的 `TreasureBag.jar` 放入伺服器 `plugins` 目錄。
2. 啟動伺服器，讓插件產生設定檔。
3. 編輯 `plugins/TreasureBag/DataBase.yml`。
4. 重啟伺服器，或確認資料庫連線後再使用插件。

## 建置

```bash
JAVA_HOME=/path/to/jdk-21 mvn package
```

建置完成後，jar 會產生在 `target/` 目錄。

## 設定

### Config.yml

`MAX_SLOT` 用來設定不同權限可使用的背包格數。

```yaml
MAX_SLOT:
  DEFAULT: 45
  VIP: 90
  VIP2: 135
  VIP3: 180
  VIP4: 225
```

另外兩個選填設定（舊的 Config.yml 沒有也沒關係，會用預設值）：

```yaml
# 寫進存取紀錄的分流名稱；不填就用伺服器資料夾名稱（lobby、ads_01…）
SERVER_NAME: lobby
# 存取紀錄保留天數，0 表示不清理；預設 30
LOG_KEEP_DAYS: 30
```

對應權限格式：

```text
treasure-bag.<節點名稱>
```

例如：

```text
treasure-bag.DEFAULT
treasure-bag.VIP
treasure-bag.VIP2
```

玩家同時擁有多個容量權限時，會取最大格數。

### DataBase.yml

`type` 決定使用哪個資料庫，可填 `mysql` 或 `sqlite`。

```yaml
# 資料庫類型: mysql 或 sqlite
type: mysql

mysql:
  host: "127.0.0.1"
  port: 3306
  database: "treasure_bag"
  user: "root"
  password: "password"

sqlite:
  # 資料庫檔案，存放於 plugins/TreasureBag/ 底下
  file: "treasure_bag.db"
```

選 `sqlite` 時不需要填 mysql 區塊，也不必另外安裝資料庫或 driver，插件會在 `plugins/TreasureBag/` 建立資料庫檔案。

升級舊版時若 `DataBase.yml` 沒有 `type` 欄位，會自動沿用 MySQL；`type` 填了無法辨識的值（例如打錯字）會直接啟動失敗並停用插件，不會靜默改用 MySQL。

若伺服器的 `/tmp` 以 `noexec` 掛載（部分硬化環境），SQLite 原生函式庫會無法載入，啟動指令加上 `-Dorg.sqlite.tmpdir=<可執行的目錄>` 即可解決。

兩種後端的資料互不相通，目前沒有提供互轉工具。

## 指令

| 指令 | 說明 | 權限 |
| --- | --- | --- |
| `/treasurebag` | 開啟自己的隨身背包 | 無 |
| `/bag` | 開啟自己的隨身背包 | 無 |
| `/treasurebag open` | 開啟自己的隨身背包 | 無 |
| `/treasurebag open <玩家>` | 開啟指定玩家背包（對方不在這台時唯讀） | `treasure-bag.admin` |
| `/treasurebag open <玩家> edit` | 對方不在這台時也允許修改 | `treasure-bag.admin` |
| `/treasurebag force` | 強制開啟自己的背包，並處理超出容量的物品 | 無 |
| `/treasurebag name <頁碼> [名稱]` | 幫自己的背包頁面取名字（最多 8 個字，不給名稱就是清除） | 無 |
| `/treasurebag log <玩家> [頁碼]` | 查看玩家的背包存取紀錄（主控台也能用） | `treasure-bag.admin` |
| `/treasurebag reload` | 重新載入設定檔 | `treasure.reload` |
| `/treasurebag trade` | 顯示 Minepacks 轉移的確認提示 | `treasure.trade` |
| `/treasurebag trade confirm` | 確認並執行 Minepacks 轉移 | `treasure.trade` |

`/bag` 是 `/treasurebag` 的別名，因此也可以使用 `/bag open`、`/bag force`。

## 權限

| 權限 | 用途 |
| --- | --- |
| `treasure-bag.<節點名稱>` | 依 `Config.yml` 的 `MAX_SLOT` 決定可用格數 |
| `treasure-bag.admin` | 允許開啟其他玩家背包 |
| `treasure.reload` | 允許使用 reload 指令 |
| `treasure.trade` | 允許使用 trade 指令 |

## 背包容量降級處理

如果玩家原本有較大的背包容量，後來權限被降級，資料庫中可能存在目前權限無法使用的格位。

玩家一般開啟背包時：

1. 插件會檢查是否有物品位於超出目前權限的格位。
2. 若有，背包不會直接打開。
3. 玩家會收到警告，告知背包空間縮小，超出的物品會掉落。
4. 訊息中會提供可點擊的 `/treasurebag force`。

玩家執行 `/treasurebag force` 後：

1. 超出可用格數的物品會掉落在玩家腳下。
2. 資料庫中超出格位的資料會被刪除。
3. 背包會以目前權限可用容量開啟。

這樣可以避免玩家在沒有預警的情況下噴出物品。

## 存檔與資料安全

- 開背包、關背包的資料庫讀寫都在背景執行緒，同一個背包的讀寫依序排隊：剛關掉馬上重開，一定讀到剛存好的內容。
- 關背包只寫有變動的格子；什麼都沒動就不碰資料庫。
- 同一台分流同時只能有一個人開著同一個背包：管理員查看線上玩家時，玩家自己開會被擋，反之亦然。
- 管理員查看不在這台的玩家（離線或在別的分流）預設唯讀，加 `edit` 才能改；改的時候只覆寫自己動過的格子。
- 讀不出來的格子（例如版本升級後舊資料解不開）會顯示成屏障「無法讀取的物品」並鎖住，資料庫那一列原封不動，不會因為存檔被刪掉。
- 存檔失敗時物品留在伺服器記憶體，每 10 秒自動補存；補存完成前這個背包不能再開。
- 外掛停用（關服、plugman reload）時會先存掉還開著的背包；資料庫連不上就寫成 `plugins/TreasureBag/unsaved/*.yml`，下次啟動自動補寫。補寫時發現那格已經被別台改過就不覆蓋，另存成 `*-conflict.yml` 並在主控台記錄，交給管理員處理。

## 禁止放入的物品

隨身背包禁止放入可攜式容器，避免容器套容器造成問題。

目前會阻擋：

- 界伏盒：`SHULKER_BOX`、所有顏色的 `*_SHULKER_BOX`
- 收納袋：`BUNDLE`、所有 `*_BUNDLE`

一般點擊、快捷鍵交換、副手交換、拖曳、shift-click 都會檢查。

## Minepacks 轉移

TreasureBag 支援從 **Minepacks** 插件匯入既有的背包資料，方便從 Minepacks 遷移過來。

使用前置條件：

1. 伺服器同時安裝 Minepacks 與 TreasureBag（沒裝 Minepacks 的伺服器不會註冊 `trade` 指令）。
2. 兩者都連得到各自的資料來源。
3. 建議在玩家較少時執行（轉移期間會有背包資料寫入）。

為避免誤觸，`/treasurebag trade` 需要二次確認：直接輸入只會顯示警告與確認提示，實際執行要輸入 `/treasurebag trade confirm`（或點擊提示訊息）。

確認後，指令會讀取 TreasureBag 資料庫中的玩家清單，逐一嘗試從 Minepacks 讀取背包，並把 Minepacks 中的物品「附加」到該玩家的 TreasureBag 背包（不會覆蓋既有物品，會接在後面的空格位）。

完成後會輸出：

```text
成功: X 無 Minepacks 背包: Y 空背包: Z 失敗: N
```

說明：

- `成功`：有物品成功轉入 TreasureBag
- `無 Minepacks 背包`：Minepacks 找不到該玩家背包資料
- `空背包`：有 Minepacks 背包，但裡面沒有物品
- `失敗`：轉移過程發生錯誤

## 資料表

插件啟動時會依 `type` 執行對應的建表 SQL（`sql/mysql.sql` 或 `sql/sqlite.sql`）建立資料表。

主要資料表：

- `player_info`：玩家 UUID、名稱、建立時間、更新時間
- `player_bag`：玩家 UUID、格位、序列化物品、物品名稱
- `player_bag_page`：玩家自訂的頁面名稱
- `player_bag_log`：存取紀錄（操作者、分流、物品、淨增減、毫秒時間戳）

背包物品以 Bukkit `ItemStack` 序列化後存入資料庫。

## 注意事項

- **不要提交真實的資料庫密碼。** `DataBase.yml` 內建的 `xxxx` 只是佔位符，正式帳密只放在伺服器的 `plugins/TreasureBag/DataBase.yml`，切勿寫進版控。
- 本插件僅支援 Paper `1.21.11`（`api-version` 已鎖定），其他伺服器核心或版本未經測試。
- SQLite 為單一寫入者，插件已限制連線池為 1；請勿手動調高，否則交易可能拿到 `SQLITE_BUSY`。
- 背包物品以 Bukkit `ItemStack` 序列化儲存，屬版本綁定格式；跨 Minecraft 大版本升級後，舊資料不保證能正確反序列化，升級前請先備份資料庫。

## 授權

本專案採用 [MIT License](LICENSE) 授權，歡迎自由使用、修改與散布，唯需保留版權與授權聲明。

發布的 `TreasureBag.jar` 會打包以下第三方函式庫，各自的授權如下：

| 函式庫 | 授權 |
| --- | --- |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | Apache-2.0 |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Apache-2.0 |

Paper API、VaultAPI、PlaceholderAPI、Minepacks API 為 `provided` 依賴，不會打包進 jar。
