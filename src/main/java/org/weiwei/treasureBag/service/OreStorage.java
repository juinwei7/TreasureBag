package org.weiwei.treasureBag.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.data.OreRepository;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 礦物倉庫的資料庫讀寫（IO 執行緒），跟背包共用 {@link BagStorage#enqueue} 的佇列：同一位玩家的工作依序執行
 * <p>
 * 增加寫不進去 → 留在 {@link #UNSAVED} 每 5 秒重試；讀倉庫時會把它算進去，畫面上的數量不會少。
 * 取出是先在資料庫扣掉再回主執行緒給物品，扣掉但還沒給的記在 {@link #UNDELIVERED}，外掛停用時寫回去。
 * 停用時還寫不進去就存成 plugins/TreasureBag/unsaved-ore/*.yml，下次啟動補寫
 * （跟背包的 unsaved/ 分開放，背包的補寫程式會把那個資料夾裡的檔案都當成背包資料）。
 */
final class OreStorage {

    private static final String UNSAVED_FOLDER = "unsaved-ore";
    private static final OreRepository REPOSITORY = new OreRepository();

    /** 寫不進去、等重試的增加：擁有者 → 物品 → 件數（只在這位擁有者的佇列裡讀寫） */
    private static final Map<UUID, Map<String, Long>> UNSAVED = new ConcurrentHashMap<>();
    /** 已經排了重試、還沒跑完的擁有者 */
    private static final Set<UUID> RETRYING = ConcurrentHashMap.newKeySet();
    /** 已從資料庫扣掉、還沒交給玩家的取出 */
    private static final Set<Withdrawal> UNDELIVERED = ConcurrentHashMap.newKeySet();

    private OreStorage() {
    }

    /**
     * 讀出來的倉庫（數量已加上還在等重試的部分）
     */
    record Loaded(boolean enabled, Long space, Map<String, OreRepository.Row> rows) {
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

    static CompletableFuture<Loaded> load(UUID owner, List<String> defaults) {
        return BagStorage.enqueue(owner, () -> {
            OreRepository.Account account = REPOSITORY.load(owner, defaults);
            Map<String, OreRepository.Row> rows = new LinkedHashMap<>();
            account.rows().forEach(row -> rows.put(row.material(), row));
            Map<String, Long> unsaved = UNSAVED.get(owner);
            if (unsaved != null) {
                unsaved.forEach((material, amount) -> rows.merge(material, new OreRepository.Row(material, amount, true),
                        (old, add) -> new OreRepository.Row(material, old.amount() + add.amount(), old.autoCollect())));
            }
            return new Loaded(account.enabled(), account.space(), rows);
        });
    }

    /**
     * 增加數量；失敗就留著重試（物品已經從玩家身上拿走，不能丟）
     */
    static void add(OreRepository.Change change) {
        if (change.deltas().isEmpty()) return;
        BagStorage.enqueue(change.owner(), () -> {
            try {
                REPOSITORY.add(change, BagStorage.serverName());
            } catch (Exception e) {
                BagStorage.logSevere("無法寫入礦物倉庫，稍後自動重試: " + change.owner() + " " + change.deltas(), e);
                keep(change.owner(), change.deltas());
            }
            return null;
        });
    }

    /**
     * 取出；回傳的 Withdrawal 要先 {@link #claim} 成功才能把物品交給玩家；扣不到東西回傳 null
     */
    static CompletableFuture<Withdrawal> withdraw(UUID owner, String material, long want, UUID viewer, String viewerName) {
        return BagStorage.enqueue(owner, () -> {
            long taken = REPOSITORY.withdraw(owner, material, want, viewer, viewerName,
                    BagStorage.serverName(), OreRepository.SOURCE_DEPOSIT);
            if (taken <= 0) return null;
            Withdrawal withdrawal = new Withdrawal(owner, material, taken);
            UNDELIVERED.add(withdrawal);
            return withdrawal;
        });
    }

    /**
     * 主執行緒要交物品前呼叫：true 表示可以交；false 表示停用流程已經把它寫回倉庫了
     */
    static boolean claim(Withdrawal withdrawal) {
        return UNDELIVERED.remove(withdrawal);
    }

    /**
     * 管理員扣除（記成管理員來源）
     */
    static CompletableFuture<Long> take(UUID owner, String material, long want, UUID viewer, String viewerName) {
        return BagStorage.enqueue(owner, () -> REPOSITORY.withdraw(owner, material, want, viewer, viewerName,
                BagStorage.serverName(), OreRepository.SOURCE_ADMIN));
    }

    static CompletableFuture<Void> setAutoCollect(UUID owner, String material, boolean auto) {
        return BagStorage.enqueue(owner, () -> {
            REPOSITORY.setAutoCollect(owner, material, auto);
            return null;
        });
    }

    static CompletableFuture<Boolean> removeEmpty(UUID owner, String material) {
        return BagStorage.enqueue(owner, () -> REPOSITORY.removeEmpty(owner, material));
    }

    static CompletableFuture<Void> setEnabled(UUID owner, boolean enabled) {
        return BagStorage.enqueue(owner, () -> {
            REPOSITORY.setEnabled(owner, enabled);
            return null;
        });
    }

    static CompletableFuture<Void> setSpace(UUID owner, Long space) {
        return BagStorage.enqueue(owner, () -> {
            REPOSITORY.setSpace(owner, space);
            return null;
        });
    }

    /**
     * 重試寫不進去的增加（主執行緒的定時工作呼叫）
     */
    static void retryUnsaved() {
        for (UUID owner : UNSAVED.keySet()) {
            if (!RETRYING.add(owner)) continue;
            BagStorage.enqueue(owner, () -> {
                Map<String, Long> amounts = UNSAVED.remove(owner);
                if (amounts == null) return null;
                try {
                    REPOSITORY.add(retryChange(owner, amounts), BagStorage.serverName());
                    BagStorage.logInfo("礦物倉庫補存完成: " + owner + " " + amounts);
                } catch (Exception e) {
                    keep(owner, amounts);
                    BagStorage.logWarning("礦物倉庫補存仍失敗，稍後再試: " + owner + " " + e.getMessage());
                }
                return null;
            }).whenComplete((ignored, error) -> RETRYING.remove(owner));
        }
    }

    /**
     * 外掛停用（{@link BagStorage#shutdown} 已等佇列做完、關掉執行緒之後才呼叫）：
     * 還沒送出的自動收集、等重試的、扣了還沒交出去的，全部直接寫回；寫不進去就存成檔案
     *
     * @param pending 各玩家還沒送出的增加
     */
    static void shutdown(Map<UUID, Map<String, Long>> pending) {
        Map<UUID, Map<String, Long>> all = new HashMap<>();
        pending.forEach((owner, amounts) -> amounts.forEach((material, amount) -> merge(all, owner, material, amount)));
        UNSAVED.forEach((owner, amounts) -> amounts.forEach((material, amount) -> merge(all, owner, material, amount)));
        for (Withdrawal withdrawal : UNDELIVERED) {
            merge(all, withdrawal.owner, withdrawal.material, withdrawal.amount);
            BagStorage.logWarning("停用時有取出還沒交給玩家，寫回礦物倉庫: " + withdrawal.owner + " "
                    + withdrawal.material + " x" + withdrawal.amount);
        }
        UNSAVED.clear();
        UNDELIVERED.clear();
        RETRYING.clear();

        all.forEach((owner, amounts) -> {
            try {
                REPOSITORY.add(retryChange(owner, amounts), BagStorage.serverName());
            } catch (Exception e) {
                BagStorage.logSevere("停用時無法寫入礦物倉庫，改存成檔案: " + owner + " " + amounts, e);
                dump(owner, amounts);
            }
        });
    }

    /**
     * 開機補寫上次停用沒寫進去的檔案
     */
    static void recoverFiles() {
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
                REPOSITORY.add(retryChange(owner, amounts), BagStorage.serverName());
                File done = new File(file.getParentFile(), file.getName() + ".applied");
                if (!file.renameTo(done)) BagStorage.logWarning("補寫完成但無法改名: " + file.getPath());
                BagStorage.logInfo("已補寫上次未存的礦物倉庫: " + owner + " " + amounts);
            } catch (Exception e) {
                BagStorage.logSevere("補寫礦物倉庫暫存檔失敗，下次啟動再試: " + file.getPath(), e);
            }
        }
    }

    private static void keep(UUID owner, Map<String, Long> amounts) {
        Map<String, Long> unsaved = UNSAVED.computeIfAbsent(owner, key -> new HashMap<>());
        amounts.forEach((material, amount) -> unsaved.merge(material, amount, Long::sum));
    }

    private static void merge(Map<UUID, Map<String, Long>> all, UUID owner, String material, long amount) {
        all.computeIfAbsent(owner, key -> new HashMap<>()).merge(material, amount, Long::sum);
    }

    /**
     * 重試、補寫、停用寫回：不寫存取紀錄（第一次寫入時的紀錄已經跟著失敗了）
     */
    private static OreRepository.Change retryChange(UUID owner, Map<String, Long> amounts) {
        return new OreRepository.Change(owner, null, null, OreRepository.SOURCE_DEPOSIT, amounts, true, false);
    }

    private static File folder() {
        return new File(Main.getInst().getDataFolder(), UNSAVED_FOLDER);
    }

    private static void dump(UUID owner, Map<String, Long> amounts) {
        File folder = folder();
        if (!folder.exists() && !folder.mkdirs()) {
            BagStorage.logSevere("無法建立 unsaved-ore 資料夾，礦物倉庫變更只剩日誌: " + owner + " " + amounts, null);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("owner", owner.toString());
        yaml.set("created", System.currentTimeMillis());
        amounts.forEach((material, amount) -> yaml.set("amounts." + material, amount));
        File file = new File(folder, owner + "-" + System.currentTimeMillis() + ".yml");
        try {
            yaml.save(file);
            BagStorage.logSevere("礦物倉庫變更已存成檔案: " + file.getPath(), null);
        } catch (Exception e) {
            BagStorage.logSevere("無法寫入礦物倉庫暫存檔: " + file.getPath() + " " + amounts, e);
        }
    }
}
