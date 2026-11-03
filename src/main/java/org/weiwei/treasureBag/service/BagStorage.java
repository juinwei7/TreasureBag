package org.weiwei.treasureBag.service;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.data.BagChanges;
import org.weiwei.treasureBag.data.BagLog;
import org.weiwei.treasureBag.data.PlayerBagRepository;
import org.weiwei.treasureBag.dataBase.DataBase;
import org.weiwei.treasureBag.entity.PlayerBag;
import org.weiwei.treasureBag.entity.PlayerInfo;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * 背包的資料庫存取：全部在 IO 執行緒跑，不佔主執行緒
 * <p>
 * 同一位擁有者的讀寫依序排隊（{@link #enqueue}），先存完才讀，不會讀到還沒寫進去的舊資料。
 * 存檔失敗的變更留在 {@link #UNSAVED} 定時重試；重試成功前不讓這個背包再被開啟。
 * 外掛停用時還存不進去，就寫成 plugins/TreasureBag/unsaved/*.yml，下次啟動補寫。
 * 重試與補寫用嚴格模式：資料庫內容跟原值不符的格位不寫，另存 conflict 檔給管理員處理。
 */
public class BagStorage {

    private static final long RETRY_PERIOD_TICKS = 20L * 10;
    private static final long LOG_CLEAN_PERIOD_TICKS = 20L * 60 * 60 * 6;
    private static final int DEFAULT_LOG_KEEP_DAYS = 30;
    private static final long SHUTDOWN_WAIT_SECONDS = 15;
    private static final String UNSAVED_FOLDER = "unsaved";

    private static final PlayerBagRepository REPOSITORY = new PlayerBagRepository();
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    /** 每位擁有者最後一個排隊中的工作 */
    private static final Map<UUID, CompletableFuture<?>> TAILS = new ConcurrentHashMap<>();
    /** 存檔失敗、等待重試的變更 */
    private static final Map<UUID, BagChanges> UNSAVED = new ConcurrentHashMap<>();
    /** 這次開機已確認過的玩家資料，避免每次開背包都查 player_info */
    private static final Map<UUID, PlayerInfo> PLAYER_INFOS = new ConcurrentHashMap<>();

    private static ExecutorService executor;
    private static BukkitTask retryTask;
    private static BukkitTask logCleanTask;
    private static volatile boolean shuttingDown;
    /** 分流名稱，寫進存取紀錄；Config.yml 沒設 SERVER_NAME 就用伺服器資料夾名稱（lobby、ads_01…） */
    private static String serverName;

    /**
     * 啟動 IO 執行緒與重試排程（資料庫初始化成功後呼叫）
     */
    public static void start() {
        shuttingDown = false;
        // SQLite 只有一條連線，多開執行緒只會互搶
        int threads = DataBase.getType() == DataBase.DataBaseType.SQLITE ? 1 : 2;
        AtomicInteger counter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "TreasureBag-IO-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        serverName = resolveServerName();
        retryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                Main.getInst(), BagStorage::retryUnsaved, RETRY_PERIOD_TICKS, RETRY_PERIOD_TICKS);
        logCleanTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                Main.getInst(), BagStorage::cleanLogs, 20L * 60, LOG_CLEAN_PERIOD_TICKS);
        recoverUnsavedFiles();
    }

    private static String resolveServerName() {
        String configured = ConfigManager.getConfig() != null ? ConfigManager.getConfig().getString("SERVER_NAME") : null;
        if (configured != null && !configured.isBlank()) return configured.trim();
        Path folder = Bukkit.getWorldContainer().getAbsoluteFile().toPath().normalize().getFileName();
        return folder != null ? folder.toString() : "unknown";
    }

    /**
     * 刪掉超過保留天數的存取紀錄（每台都會跑，重複刪不影響結果）
     */
    private static void cleanLogs() {
        int days = ConfigManager.getConfig() != null
                ? ConfigManager.getConfig().getInt("LOG_KEEP_DAYS", DEFAULT_LOG_KEEP_DAYS)
                : DEFAULT_LOG_KEEP_DAYS;
        if (days <= 0) return;
        try {
            int deleted = REPOSITORY.deleteLogsBefore(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
            if (deleted > 0) logInfo("已清除 " + days + " 天前的背包存取紀錄 " + deleted + " 筆");
        } catch (Exception e) {
            logWarning("清理背包存取紀錄失敗: " + e.getMessage());
        }
    }

    /**
     * 讀進來的背包
     *
     * @param brokenRows 讀不出來的格位，連同溢出範圍的一起列出
     */
    record LoadedBag(PlayerInfo playerInfo, Map<Integer, ItemStack> items, Map<Integer, String> baseline,
                     Map<Integer, ItemStack> baselineItems, Set<Integer> brokenRows, Map<Integer, String> pageNames) {
    }

    /**
     * 讀取背包（排在這位擁有者之前的存檔之後）
     */
    static CompletableFuture<LoadedBag> load(UUID owner, String ownerName) {
        return enqueue(owner, () -> {
            // 排在前面的存檔剛失敗：不能用資料庫的舊內容開背包，等補存完成
            if (UNSAVED.containsKey(owner)) throw new SavePendingException();
            PlayerInfo playerInfo = ensurePlayerInfo(owner, ownerName);
            List<PlayerBag> rows = REPOSITORY.loadPlayerBags(playerInfo);

            Map<Integer, ItemStack> items = new HashMap<>();
            Map<Integer, String> baseline = new HashMap<>();
            Map<Integer, ItemStack> baselineItems = new HashMap<>();
            Set<Integer> broken = new HashSet<>();
            for (PlayerBag row : rows) {
                if (row == null || row.getSolder() == null) continue;
                int slot = row.getSolder().intValue();
                ItemStack item = row.getItem();
                if (item == null || item.getType().isAir()) {
                    broken.add(slot);
                    logWarning("讀不出來的背包格位（保留不動）: " + playerInfo.getPlayerName() + " 格位 " + slot
                            + " " + row.getItemName());
                    continue;
                }
                items.put(slot, item);
                baseline.put(slot, row.getSerialize());
                // 另存一份不會被背包操作改到的副本，關背包時拿來比對物品本身
                baselineItems.put(slot, item.clone());
            }
            Map<Integer, String> pageNames = new HashMap<>(REPOSITORY.loadPageNames(owner));
            return new LoadedBag(playerInfo, items, baseline, baselineItems, broken, pageNames);
        });
    }

    /**
     * 管理員用名稱找玩家（找不到回傳 null）
     */
    static CompletableFuture<PlayerInfo> findByName(String name) {
        return CompletableFuture.supplyAsync(() -> REPOSITORY.findPlayerInfoByName(name), executor);
    }

    /**
     * 直接寫入一組變更，失敗時回傳失敗的 future（不進重試佇列，由呼叫端決定怎麼處理）
     * 強制開啟用它刪溢出格：刪除與存取紀錄同一筆交易，失敗就不掉物品
     */
    static CompletableFuture<Void> apply(BagChanges changes) {
        return enqueue(changes.owner(), () -> {
            REPOSITORY.applyChanges(changes, false, serverName);
            return null;
        });
    }

    /**
     * 設定頁面名稱（name 為 null 表示清除）
     */
    static CompletableFuture<Void> savePageName(UUID owner, int page, String name) {
        return enqueue(owner, () -> {
            REPOSITORY.savePageName(owner, page, name);
            return null;
        });
    }

    /**
     * 讀取存取紀錄（新的在前）
     */
    static CompletableFuture<List<BagLog>> loadLogs(UUID owner, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> REPOSITORY.loadLogs(owner, offset, limit), executor);
    }

    /**
     * 存檔：比對與寫入都在 IO 執行緒；失敗就放進重試佇列並通知觀看者
     */
    static void save(BagSession.Snapshot snapshot, UUID viewer) {
        if (shuttingDown) {
            saveNow(List.of(snapshot));
            return;
        }
        enqueue(snapshot.owner(), () -> {
            BagChanges changes = snapshot.diff();
            if (changes.isEmpty()) return null;
            try {
                REPOSITORY.applyChanges(changes, false, serverName);
            } catch (Exception e) {
                logSevere("無法保存玩家背包，稍後自動重試: " + snapshot.ownerName(), e);
                UNSAVED.merge(snapshot.owner(), changes, BagChanges::then);
                runSync(() -> {
                    Player player = Bukkit.getPlayer(viewer);
                    if (player != null) Message.sendPrefix(player, Message.MESSAGE__BAG_SAVE_FAILED);
                });
            }
            return null;
        });
    }

    /**
     * 直接寫回指定格位（強制開啟刪完溢出格後人卻下線時，把物品寫回去）
     * 失敗就放進重試佇列，不讓物品消失
     */
    static void restore(BagChanges changes) {
        if (changes.isEmpty()) return;
        enqueue(changes.owner(), () -> {
            try {
                REPOSITORY.applyChanges(changes, false, serverName);
            } catch (Exception e) {
                logSevere("無法寫回溢出物品，稍後自動重試: " + changes.ownerName(), e);
                UNSAVED.merge(changes.owner(), changes, BagChanges::then);
            }
            return null;
        });
    }

    /**
     * 這位擁有者是否還有存檔失敗、等待重試的資料
     */
    static boolean hasUnsaved(UUID owner) {
        return UNSAVED.containsKey(owner);
    }

    /**
     * 外掛停用：等排隊中的工作做完，再把還開著的背包與失敗資料直接寫入；寫不進去就存成檔案
     *
     * @param openBags 還開著的背包
     */
    static void shutdown(List<BagSession.Snapshot> openBags) {
        shuttingDown = true;
        if (retryTask != null) retryTask.cancel();
        if (logCleanTask != null) logCleanTask.cancel();

        try {
            CompletableFuture.allOf(TAILS.values().toArray(new CompletableFuture[0]))
                    .get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logWarning("等待背包存檔逾時或失敗，改由主執行緒直接寫入: " + e.getMessage());
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        saveNow(openBags);

        for (BagChanges changes : new ArrayList<>(UNSAVED.values())) {
            UNSAVED.remove(changes.owner());
            writeStrictOrDump(changes);
        }
        PLAYER_INFOS.clear();
    }

    /**
     * 在目前執行緒直接寫入（只在停用流程使用）
     */
    private static void saveNow(List<BagSession.Snapshot> snapshots) {
        for (BagSession.Snapshot snapshot : snapshots) {
            BagChanges changes = snapshot.diff();
            BagChanges pending = UNSAVED.remove(snapshot.owner());
            if (pending != null) changes = pending.then(changes);
            if (changes.isEmpty()) continue;
            try {
                REPOSITORY.applyChanges(changes, false, serverName);
            } catch (Exception e) {
                logSevere("停用時無法保存玩家背包，改存成檔案: " + snapshot.ownerName(), e);
                dump(changes, "");
            }
        }
    }

    private static void retryUnsaved() {
        if (shuttingDown) return;
        for (UUID owner : new ArrayList<>(UNSAVED.keySet())) {
            if (TAILS.containsKey(owner)) continue;
            enqueue(owner, () -> {
                BagChanges changes = UNSAVED.get(owner);
                if (changes == null) return null;
                List<BagChanges.SlotChange> conflicts = REPOSITORY.applyChanges(changes, true, serverName);
                UNSAVED.remove(owner, changes);
                logInfo("背包補存完成: " + changes.ownerName() + "（" + changes.size() + " 格）");
                reportConflicts(changes, conflicts);
                runSync(() -> {
                    Player player = Bukkit.getPlayer(owner);
                    if (player != null) Message.sendPrefix(player, Message.MESSAGE__BAG_SAVE_RECOVERED);
                });
                return null;
            }).exceptionally(e -> {
                logWarning("背包補存仍失敗，稍後再試: " + owner + " " + e.getMessage());
                return null;
            });
        }
    }

    private static void writeStrictOrDump(BagChanges changes) {
        try {
            reportConflicts(changes, REPOSITORY.applyChanges(changes, true, serverName));
        } catch (Exception e) {
            logSevere("停用時無法補存玩家背包，改存成檔案: " + changes.ownerName(), e);
            dump(changes, "");
        }
    }

    /**
     * 原值不符而沒寫入的格位：記錄並另存成 conflict 檔，交給管理員比對處理
     */
    private static void reportConflicts(BagChanges changes, List<BagChanges.SlotChange> conflicts) {
        if (conflicts.isEmpty()) return;
        BagChanges conflict = new BagChanges(changes.owner(), changes.ownerName());
        conflicts.forEach(conflict::put);
        logSevere("背包補存時有 " + conflicts.size() + " 格已被其他分流改過，未寫入，已另存 conflict 檔: "
                + changes.ownerName() + " 格位 " + conflicts.stream().map(c -> c.slot() + ":" + c.itemName()).toList(), null);
        dump(conflict, "-conflict");
    }

    // ---------- 存成檔案 / 開機補寫 ----------

    private static File unsavedFolder() {
        return new File(Main.getInst().getDataFolder(), UNSAVED_FOLDER);
    }

    private static void dump(BagChanges changes, String suffix) {
        File folder = unsavedFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            logSevere("無法建立 unsaved 資料夾，背包變更只剩日誌: " + changes.ownerName(), null);
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("owner", changes.owner().toString());
        yaml.set("name", changes.ownerName());
        yaml.set("created", System.currentTimeMillis());
        for (BagChanges.SlotChange change : changes.changes()) {
            String path = "slots." + change.slot();
            yaml.set(path + ".expected", change.expected());
            yaml.set(path + ".value", change.value());
            yaml.set(path + ".itemName", change.itemName());
        }

        File file = new File(folder, changes.owner() + "-" + System.currentTimeMillis() + suffix + ".yml");
        try {
            yaml.save(file);
            logSevere("背包變更已存成檔案: " + file.getPath(), null);
        } catch (Exception e) {
            logSevere("無法寫入背包暫存檔: " + file.getPath(), e);
        }
    }

    /**
     * 開機時補寫上次停用沒存進去的檔案（conflict 檔不處理，留給管理員）
     */
    private static void recoverUnsavedFiles() {
        File[] files = unsavedFolder().listFiles((dir, name) -> name.endsWith(".yml") && !name.contains("-conflict"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);

        for (File file : files) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                BagChanges changes = new BagChanges(UUID.fromString(yaml.getString("owner", "")), yaml.getString("name"));
                ConfigurationSection slots = yaml.getConfigurationSection("slots");
                if (slots != null) {
                    for (String key : slots.getKeys(false)) {
                        ConfigurationSection slot = slots.getConfigurationSection(key);
                        if (slot == null) continue;
                        changes.put(new BagChanges.SlotChange(Integer.parseInt(key), slot.getString("expected"),
                                slot.getString("value"), slot.getString("itemName")));
                    }
                }

                reportConflicts(changes, REPOSITORY.applyChanges(changes, true, serverName));
                File done = new File(file.getParentFile(), file.getName() + ".applied");
                if (!file.renameTo(done)) logWarning("補寫完成但無法改名: " + file.getPath());
                logInfo("已補寫上次未存的背包: " + changes.ownerName() + "（" + changes.size() + " 格）");
            } catch (Exception e) {
                logSevere("補寫背包暫存檔失敗，下次啟動再試: " + file.getPath(), e);
            }
        }
    }

    /**
     * 這位擁有者有存檔失敗、正在等補存的資料
     */
    static class SavePendingException extends RuntimeException {
        SavePendingException() {
            super("背包資料補存中", null, false, false);
        }
    }

    // ---------- 工具 ----------

    private static <T> CompletableFuture<T> enqueue(UUID owner, Supplier<T> task) {
        CompletableFuture<T> next = new CompletableFuture<>();
        TAILS.compute(owner, (key, previous) -> {
            CompletableFuture<?> before = previous != null ? previous : DONE;
            before.handle((result, error) -> null).thenRunAsync(() -> {
                try {
                    next.complete(task.get());
                } catch (Throwable e) {
                    next.completeExceptionally(e);
                }
            }, executor).exceptionally(e -> {
                // 執行緒池已關閉（外掛停用中）
                next.completeExceptionally(e);
                return null;
            });
            return next;
        });
        next.whenComplete((result, error) -> TAILS.remove(owner, next));
        return next;
    }

    private static PlayerInfo ensurePlayerInfo(UUID owner, String ownerName) {
        PlayerInfo cached = PLAYER_INFOS.get(owner);
        if (cached != null && (ownerName == null || ownerName.equals(cached.getPlayerName()))) return cached;

        PlayerInfo playerInfo = REPOSITORY.getOrCreatePlayerInfo(owner, ownerName);
        PLAYER_INFOS.put(owner, playerInfo);
        return playerInfo;
    }

    /**
     * 回主執行緒執行；外掛停用中就不排（排程器會拒絕）
     */
    static void runSync(Runnable runnable) {
        if (!Main.getInst().isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        Bukkit.getScheduler().runTask(Main.getInst(), runnable);
    }

    static void logInfo(String message) {
        Main.getInst().getLogger().info(message);
    }

    static void logWarning(String message) {
        Main.getInst().getLogger().warning(message);
    }

    static void logSevere(String message, Throwable error) {
        if (error == null) {
            Main.getInst().getLogger().severe(message);
            return;
        }
        Main.getInst().getLogger().log(Level.SEVERE, message, error);
    }
}
