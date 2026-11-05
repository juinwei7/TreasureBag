package org.weiwei.treasureBag.service;

import org.bukkit.Material;
import org.weiwei.treasureBag.data.OreRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一位玩家的礦物倉庫（主執行緒）
 * <p>
 * {@link #entries} 的數量是這台知道的最新值：存入、自動收集當下就加上去，資料庫在背景追上。
 * 重新讀取期間送出的增加記在 {@link #afterReload}，讀完時「資料庫的值＋這段期間送出的＋還沒送出的」就是正確數量；
 * 取出則是回到主執行緒才扣（在讀取之前排隊的，讀取結果已經扣過，會被覆蓋掉，所以也不會重扣）。
 */
final class OreAccount {

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

    final UUID owner;
    final Map<Material, Entry> entries = new EnumMap<>(Material.class);
    /** 自動收集到、還沒送進資料庫的件數（每 5 秒送一次） */
    final Map<Material, Long> pending = new EnumMap<>(Material.class);
    /** 重新讀取中送出的增加；null 表示沒在讀取 */
    Map<Material, Long> afterReload;
    /** 讀取完要做的事（true＝讀到了） */
    final List<Consumer<Boolean>> waiting = new ArrayList<>();

    boolean loaded;
    boolean enabled = true;
    /** 個別容量；null 用預設 */
    Long space;
    /** 取出還在跑，先不接受下一次 */
    boolean withdrawing;
    long lastFullNotice;

    OreAccount(UUID owner) {
        this.owner = owner;
    }

    long space() {
        return space != null ? space : OreService.defaultSpace();
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
     * 畫面上要列出的種類（原版物品順序）；不是物品的種類（舊資料殘留）不列
     */
    List<Material> visible() {
        List<Material> list = new ArrayList<>();
        for (Material material : entries.keySet()) {
            if (material.isItem()) list.add(material);
        }
        return list;
    }

    /**
     * 這台先加上去（存入、自動收集、退回）
     */
    void add(Material material, long amount, boolean autoForNew) {
        entries.computeIfAbsent(material, key -> new Entry(0, autoForNew)).amount += amount;
    }

    /**
     * 送出了一筆增加：讀取中就記下來，讀完要補回去
     */
    void sent(Map<Material, Long> amounts) {
        if (afterReload == null) return;
        amounts.forEach((material, amount) -> afterReload.merge(material, amount, Long::sum));
    }

    /**
     * 套用讀取結果
     */
    void apply(OreStorage.Loaded result, Map<Material, Long> sentDuringLoad) {
        entries.clear();
        for (OreRepository.Row row : result.rows().values()) {
            Material material = Material.getMaterial(row.material());
            if (material == null) continue;
            entries.put(material, new Entry(row.amount(), row.autoCollect()));
        }
        if (sentDuringLoad != null) sentDuringLoad.forEach((material, amount) -> add(material, amount, true));
        pending.forEach((material, amount) -> add(material, amount, true));
        enabled = result.enabled();
        space = result.space();
        loaded = true;
    }

    /**
     * 取出還沒送出的自動收集（清空）
     */
    Map<String, Long> drainPending() {
        Map<String, Long> deltas = new HashMap<>();
        pending.forEach((material, amount) -> deltas.put(material.name(), amount));
        pending.clear();
        return deltas;
    }
}
