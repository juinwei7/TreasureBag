package org.weiwei.treasureBag.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.data.VaultRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一種倉庫的資料庫讀寫（IO 執行緒），跟背包共用 {@link BagStorage#enqueue} 的佇列：同一位玩家的工作依序執行
 * <p>
 * 增加寫不進去 → 留在 {@link #unsaved} 每 5 秒重試；讀倉庫時會把它算進去，畫面上的數量不會少。
 * 取出是先在資料庫扣掉再回主執行緒給物品，扣掉但還沒給的記在 {@link #undelivered}，外掛停用時寫回去。
 * <p>
 * 停用時的交接靠 {@link #lock} 與 {@link #closed}：停用流程在鎖裡把「排了還沒開始寫的、等重試的、扣了還沒交出的」
 * 全部接手並設 closed；IO 執行緒上晚一步做完的工作看到 closed 就自己寫回或存檔，不會兩邊都寫、也不會都沒寫。
 * 寫不進去的存成 plugins/TreasureBag/unsaved-ore（或 unsaved-material）/*.yml，下次啟動補寫（ore_import 記檔名，補寫只會套用一次）。
 * 跟背包的 unsaved/ 分開放：背包的補寫程式會把那個資料夾裡的檔案都當成背包資料。
 */
final class VaultStorage {

    private static final AtomicInteger DUMP_COUNTER = new AtomicInteger();

    private final VaultKind kind;
    private final VaultRepository repository;

    private final Object lock = new Object();
    /** 停用流程已經接手（lock 保護） */
    private boolean closed;
    /** 已排進佇列、還沒開始寫的增加（用物件本身比對，lock 保護） */
    private final Set<VaultRepository.Change> queued = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 寫不進去、等重試的增加：擁有者 → 物品 → 件數（只在這位擁有者的佇列裡改，停用流程在 lock 裡接手） */
    private final Map<UUID, Map<String, Long>> unsaved = new ConcurrentHashMap<>();
    /** 已經排了重試、還沒跑完的擁有者 */
    private final Set<UUID> retrying = ConcurrentHashMap.newKeySet();
    /** 已從資料庫扣掉、還沒交給玩家的取出 */
    private final Set<Withdrawal> undelivered = ConcurrentHashMap.newKeySet();

    VaultStorage(VaultKind kind) {
        this.kind = kind;
        this.repository = kind.repository;
    }

    /**
     * 讀出來的倉庫（數量已加上還在等重試的部分）
     */
    record Loaded(boolean enabled, Long space, Map<String, VaultRepository.Row> rows) {
    }

    /**
     * 資料庫已經扣掉的一筆取出（用物件本身比對，不用內容）
     */
    static final class Withdrawal {
        final UUID owner;
        final String material;
        final long amount;

        Withdrawal(UUID owner, String material, long amount) {
            this.owner = owner;
            this.material = material;
            this.amount = amount;
        }
    }

    void start() {
        synchronized (lock) {
            closed = false;
        }
    }

    CompletableFuture<Loaded> load(UUID owner, List<String> defaults) {
        return BagStorage.enqueue(owner, () -> {
            VaultRepository.Account account = repository.load(owner, defaults);
            Map<String, VaultRepository.Row> rows = new LinkedHashMap<>();
            account.rows().forEach(row -> rows.put(row.material(), row));
            Map<String, Long> waiting = unsaved.get(owner);
            if (waiting != null) {
                waiting.forEach((material, amount) -> rows.merge(material, new VaultRepository.Row(material, amount, true),
                        (old, add) -> new VaultRepository.Row(material, old.amount() + add.amount(), old.autoCollect())));
            }
            return new Loaded(account.enabled(), account.space(), rows);
        });
    }

    /**
     * 增加數量；失敗就留著重試（物品已經從玩家身上拿走，不能丟）
     * <p>
     * 排進佇列前先記在 {@link #queued}：關服時佇列等太久被放棄、這筆根本沒跑到，停用流程會直接寫。
     */
    void add(VaultRepository.Change change) {
        if (change.deltas().isEmpty()) return;
        boolean late;
        synchronized (lock) {
            late = closed;
            if (!late) queued.add(change);
        }
        if (late) {
            dump(change.owner(), change.deltas());
            return;
        }
        BagStorage.enqueue(change.owner(), () -> {
            synchronized (lock) {
                // 停用流程已經接手這筆
                if (!queued.remove(change)) return null;
            }
            try {
                repository.add(change, BagStorage.serverName());
            } catch (Exception e) {
                BagStorage.logSevere("無法寫入" + kind.label + "，稍後自動重試: " + change.owner() + " " + change.deltas(), e);
                failed(change.owner(), change.deltas());
            }
            return null;
        });
    }

    /**
     * 取出；回傳的 Withdrawal 要先 {@link #claim} 成功才能把物品交給玩家；扣不到東西回傳 null
     */
    CompletableFuture<Withdrawal> withdraw(UUID owner, String material, long want, UUID viewer, String viewerName) {
        return BagStorage.enqueue(owner, () -> {
            long taken = repository.withdraw(owner, material, want, viewer, viewerName,
                    BagStorage.serverName(), repository.sourceDeposit);
            if (taken <= 0) return null;
            Withdrawal withdrawal = new Withdrawal(owner, material, taken);
            synchronized (lock) {
                if (!closed) {
                    undelivered.add(withdrawal);
                    return withdrawal;
                }
            }
            // 停用流程已經寫回過了，這筆交不到玩家手上，自己寫回去
            BagStorage.logWarning("停用中才扣完的取出，寫回" + kind.label + ": " + owner + " " + material + " x" + taken);
            writeOrDump(owner, Map.of(material, taken));
            return null;
        });
    }

    /**
     * 主執行緒要交物品前呼叫：true 表示可以交；false 表示停用流程已經把它寫回倉庫了
     */
    boolean claim(Withdrawal withdrawal) {
        return undelivered.remove(withdrawal);
    }

    /**
     * 管理員扣除（記成管理員來源）
     */
    CompletableFuture<Long> take(UUID owner, String material, long want, UUID viewer, String viewerName) {
        return BagStorage.enqueue(owner, () -> repository.withdraw(owner, material, want, viewer, viewerName,
                BagStorage.serverName(), repository.sourceAdmin));
    }

    CompletableFuture<Void> setAutoCollect(UUID owner, String material, boolean auto) {
        return BagStorage.enqueue(owner, () -> {
            repository.setAutoCollect(owner, material, auto);
            return null;
        });
    }

    CompletableFuture<Boolean> removeEmpty(UUID owner, String material) {
        return BagStorage.enqueue(owner, () -> repository.removeEmpty(owner, material));
    }

    CompletableFuture<Void> setEnabled(UUID owner, boolean enabled) {
        return BagStorage.enqueue(owner, () -> {
            repository.setEnabled(owner, enabled);
            return null;
        });
    }

    CompletableFuture<Void> setSpace(UUID owner, Long space) {
        return BagStorage.enqueue(owner, () -> {
            repository.setSpace(owner, space);
            return null;
        });
    }

    /**
     * 重試寫不進去的增加（主執行緒的定時工作呼叫）
     */
    void retryUnsaved() {
        for (UUID owner : unsaved.keySet()) {
            if (!retrying.add(owner)) continue;
            BagStorage.enqueue(owner, () -> {
                Map<String, Long> amounts;
                synchronized (lock) {
                    if (closed) return null;
                    amounts = unsaved.remove(owner);
                }
                if (amounts == null) return null;
                try {
                    repository.add(retryChange(owner, amounts), BagStorage.serverName());
                    BagStorage.logInfo(kind.label + "補存完成: " + owner + " " + amounts);
                } catch (Exception e) {
                    BagStorage.logWarning(kind.label + "補存仍失敗，稍後再試: " + owner + " " + e.getMessage());
                    failed(owner, amounts);
                }
                return null;
            }).whenComplete((ignored, error) -> retrying.remove(owner));
        }
    }

    /**
     * 外掛停用（{@link BagStorage#shutdown} 已等佇列、關掉執行緒之後才呼叫）：
     * 接手還沒送出的自動收集、排了還沒寫的、等重試的、扣了還沒交出去的，直接寫回；
     * 資料庫一失敗就不再試，剩下的全部存成檔案（每筆都等連線逾時的話，關服會卡好幾分鐘）
     *
     * @param pending 各玩家還沒送出的增加
     */
    void shutdown(Map<UUID, Map<String, Long>> pending) {
        Map<UUID, Map<String, Long>> all = new HashMap<>();
        pending.forEach((owner, amounts) -> amounts.forEach((material, amount) -> merge(all, owner, material, amount)));
        synchronized (lock) {
            closed = true;
            for (VaultRepository.Change change : queued) {
                change.deltas().forEach((material, amount) -> merge(all, change.owner(), material, amount));
            }
            unsaved.forEach((owner, amounts) -> amounts.forEach((material, amount) -> merge(all, owner, material, amount)));
            for (Withdrawal withdrawal : undelivered) {
                merge(all, withdrawal.owner, withdrawal.material, withdrawal.amount);
                BagStorage.logWarning("停用時有取出還沒交給玩家，寫回" + kind.label + ": " + withdrawal.owner + " "
                        + withdrawal.material + " x" + withdrawal.amount);
            }
            queued.clear();
            unsaved.clear();
            undelivered.clear();
            retrying.clear();
        }

        boolean databaseDown = false;
        for (Map.Entry<UUID, Map<String, Long>> entry : all.entrySet()) {
            if (databaseDown) {
                dump(entry.getKey(), entry.getValue());
                continue;
            }
            try {
                repository.add(retryChange(entry.getKey(), entry.getValue()), BagStorage.serverName());
            } catch (Exception e) {
                BagStorage.logSevere("停用時無法寫入" + kind.label + "，剩下的改存成檔案: " + entry.getKey() + " " + entry.getValue(), e);
                databaseDown = true;
                dump(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 開機補寫上次停用沒寫進去的檔案；檔名記進 ore_import，跟增加同一筆交易，改名失敗下次也不會重複套用
     */
    void recoverFiles() {
        File[] files = folder().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        for (File file : files) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                UUID owner = UUID.fromString(yaml.getString("owner", ""));
                Map<String, Long> amounts = new HashMap<>();
                ConfigurationSection section = yaml.getConfigurationSection("amounts");
                if (section != null) {
                    for (String material : section.getKeys(false)) amounts.put(material, section.getLong(material));
                }
                String marker = kind.recoverMarker + BagStorage.serverName() + ":" + file.getName();
                boolean applied = repository.addOnce(retryChange(owner, amounts), BagStorage.serverName(), marker);
                File done = new File(file.getParentFile(), file.getName() + ".applied");
                if (!file.renameTo(done)) BagStorage.logWarning("補寫完成但無法改名（已記錄，不會重複套用）: " + file.getPath());
                BagStorage.logInfo((applied ? "已補寫上次未存的" + kind.label + ": " : "這個檔案之前已經補寫過: ") + owner + " " + amounts);
            } catch (Exception e) {
                BagStorage.logSevere("補寫" + kind.label + "暫存檔失敗，下次啟動再試: " + file.getPath(), e);
            }
        }
    }

    /**
     * 增加寫不進去：還沒停用就留著重試，已經停用（停用流程寫完了）就自己存檔
     */
    private void failed(UUID owner, Map<String, Long> amounts) {
        synchronized (lock) {
            if (!closed) {
                Map<String, Long> waiting = unsaved.computeIfAbsent(owner, key -> new HashMap<>());
                amounts.forEach((material, amount) -> waiting.merge(material, amount, Long::sum));
                return;
            }
        }
        dump(owner, amounts);
    }

    private void writeOrDump(UUID owner, Map<String, Long> amounts) {
        try {
            repository.add(retryChange(owner, amounts), BagStorage.serverName());
        } catch (Exception e) {
            BagStorage.logSevere("無法寫回" + kind.label + "，改存成檔案: " + owner + " " + amounts, e);
            dump(owner, amounts);
        }
    }

    private static void merge(Map<UUID, Map<String, Long>> all, UUID owner, String material, long amount) {
        all.computeIfAbsent(owner, key -> new HashMap<>()).merge(material, amount, Long::sum);
    }

    /**
     * 重試、補寫、停用寫回：不寫存取紀錄（第一次寫入時的紀錄已經跟著失敗了）
     */
    private VaultRepository.Change retryChange(UUID owner, Map<String, Long> amounts) {
        return new VaultRepository.Change(owner, null, null, repository.sourceDeposit, amounts, true, false);
    }

    private File folder() {
        return new File(Main.getInst().getDataFolder(), kind.unsavedFolder);
    }

    private void dump(UUID owner, Map<String, Long> amounts) {
        File folder = folder();
        if (!folder.exists() && !folder.mkdirs()) {
            BagStorage.logSevere("無法建立 " + kind.unsavedFolder + " 資料夾，" + kind.label + "變更只剩日誌: " + owner + " " + amounts, null);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("owner", owner.toString());
        yaml.set("created", System.currentTimeMillis());
        new ArrayList<>(amounts.entrySet()).forEach(entry -> yaml.set("amounts." + entry.getKey(), entry.getValue()));
        File file = new File(folder, owner + "-" + System.currentTimeMillis() + "-" + DUMP_COUNTER.incrementAndGet() + ".yml");
        try {
            yaml.save(file);
            BagStorage.logSevere(kind.label + "變更已存成檔案: " + file.getPath(), null);
        } catch (Exception e) {
            BagStorage.logSevere("無法寫入" + kind.label + "暫存檔: " + file.getPath() + " " + amounts, e);
        }
    }
}
