package org.weiwei.treasureBag.service;

import org.weiwei.treasureBag.data.VaultRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一位玩家的一個倉庫（主執行緒），鍵見 {@link VaultKind}
 * <p>
 * {@link #entries} 的數量是這台知道的最新值：存入、自動收集當下就加上去，資料庫在背景追上。
 * 重新讀取期間送出的增加記在 {@link #afterReload}，讀完時「資料庫的值＋這段期間送出的＋還沒送出的」就是正確數量；
 * 取出則是回到主執行緒才扣（在讀取之前排隊的，讀取結果已經扣過，會被覆蓋掉，所以也不會重扣）。
 */
final class VaultAccount {

    /**
     * 倉庫裡的一種物品
     */
    static final class Entry {
        long amount;
        boolean auto;

        Entry(long amount, boolean auto) {
            this.amount = amount;
            this.auto = auto;
        }
    }

    final VaultKind kind;
    final UUID owner;
    final Map<String, Entry> entries = new HashMap<>();
    /** 自動收集到、還沒送進資料庫的件數（每 5 秒送一次） */
    final Map<String, Long> pending = new HashMap<>();
    /** 重新讀取中送出的增加；null 表示沒在讀取 */
    Map<String, Long> afterReload;
    /** 讀取完要做的事（true＝讀到了） */
    final List<Consumer<Boolean>> waiting = new ArrayList<>();

    boolean loaded;
    boolean enabled = true;
    /** 管理員個別設定的容量；null 照 VIP 階級 */
    Long space;
    /** 照 VIP 階級的容量；-1 表示不知道（管理員看不在這台的玩家） */
    long tierSpace = -1;
    /** 取出還在跑，先不接受下一次 */
    boolean withdrawing;
    long lastFullNotice;

    VaultAccount(VaultKind kind, UUID owner) {
        this.kind = kind;
        this.owner = owner;
    }

    long space() {
        return space != null ? space : Math.max(0, tierSpace);
    }

    /** 知不知道容量（看不在這台的玩家時不知道他的 VIP 階級） */
    boolean spaceKnown() {
        return space != null || tierSpace >= 0;
    }

    long total() {
        long total = 0;
        for (Entry entry : entries.values()) total += entry.amount;
        return total;
    }

    long free() {
        return Math.max(0, space() - total());
    }

    /**
     * 畫面上要列出的種類（照倉庫種類的順序）
     */
    List<String> visible() {
        return kind.order(entries.keySet());
    }

    /**
     * 這台先加上去（存入、自動收集、退回）
     */
    void add(String key, long amount, boolean autoForNew) {
        entries.computeIfAbsent(key, k -> new Entry(0, autoForNew)).amount += amount;
    }

    /**
     * 送出了一筆增加：讀取中就記下來，讀完要補回去
     */
    void sent(Map<String, Long> amounts) {
        if (afterReload == null) return;
        amounts.forEach((key, amount) -> afterReload.merge(key, amount, Long::sum));
    }

    /**
     * 套用讀取結果
     */
    void apply(VaultStorage.Loaded result, Map<String, Long> sentDuringLoad) {
        entries.clear();
        for (VaultRepository.Row row : result.rows().values()) {
            if (!kind.valid(row.material())) continue;
            entries.put(row.material(), new Entry(row.amount(), row.autoCollect()));
        }
        if (sentDuringLoad != null) sentDuringLoad.forEach((key, amount) -> add(key, amount, true));
        pending.forEach((key, amount) -> add(key, amount, true));
        enabled = result.enabled();
        space = result.space();
        loaded = true;
    }

    /**
     * 取出還沒送出的自動收集（清空）
     */
    Map<String, Long> drainPending() {
        Map<String, Long> deltas = new HashMap<>(pending);
        pending.clear();
        return deltas;
    }
}
