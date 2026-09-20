# TreasureBag

Paper 伺服器用的隨身背包插件。玩家可以透過指令開啟自己的隨身背包，背包容量依權限決定，資料可儲存在 MySQL 或 SQLite。

## 功能

- `/treasurebag` 或 `/bag` 直接開啟自己的隨身背包
- `/treasurebag open [玩家名稱]` 開啟自己或指定玩家的背包
- 權限決定玩家可用背包格數
- 支援多頁背包
- 最後一排為控制列：
  - 中間：背包資訊
  - 左側：上一頁
  - 右側：下一頁
  - 其他格子補灰色玻璃片
- 超出權限可用格數的格子會補灰色玻璃片，不能放入物品
- 關閉背包時自動保存到資料庫
- 支援從玩家背包 shift-click 快速移入隨身背包
- 禁止把界伏盒、收納袋等可攜式容器放入隨身背包
- 玩家權限降級導致背包容量縮小時，會先警告，不會直接噴出物品
- `/treasurebag force` 可由玩家確認後強制開啟，並將超出容量的物品掉落到玩家腳下
- `/treasurebag trade` 可將 Minepacks 背包資料轉移到 TreasureBag

## 環境需求

- Java 21
- Paper `1.21.11`
- MySQL 或 SQLite（SQLite 不需額外安裝，driver 已內含於插件 jar）
- Minepacks：只有執行 `/treasurebag trade` 轉移時需要

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

SQLite driver 已打包進 jar，僅保留 Mac ARM 與 Linux x86_64 / aarch64 的 native library。若要部署到 Windows 或 Alpine(musl) 伺服器，需調整 `pom.xml` 中 maven-shade-plugin 的 filter 後重新建置。

升級舊版時若 `DataBase.yml` 沒有 `type` 欄位，會自動沿用 MySQL。

兩種後端的資料互不相通，目前沒有提供互轉工具。

## 指令

| 指令 | 說明 | 權限 |
| --- | --- | --- |
| `/treasurebag` | 開啟自己的隨身背包 | 無 |
| `/bag` | 開啟自己的隨身背包 | 無 |
| `/treasurebag open` | 開啟自己的隨身背包 | 無 |
| `/treasurebag open <玩家>` | 開啟指定玩家背包 | `treasure-bag.admin` |
| `/treasurebag force` | 強制開啟自己的背包，並處理超出容量的物品 | 無 |
| `/treasurebag reload` | 重新載入設定檔 | `treasure.reload` |
| `/treasurebag trade` | 從 Minepacks 轉移背包資料 | `treasure.trade` |

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

## 禁止放入的物品

隨身背包禁止放入可攜式容器，避免容器套容器造成問題。

目前會阻擋：

- 界伏盒：`SHULKER_BOX`、所有顏色的 `*_SHULKER_BOX`
- 收納袋：`BUNDLE`、所有 `*_BUNDLE`

一般點擊、快捷鍵交換、副手交換、拖曳、shift-click 都會檢查。

## Minepacks 轉移

`/treasurebag trade` 會讀取資料庫中的玩家清單，逐一嘗試從 Minepacks 讀取背包，並把 Minepacks 中的物品轉入 TreasureBag。

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

背包物品以 Bukkit `ItemStack` 序列化後存入資料庫。
