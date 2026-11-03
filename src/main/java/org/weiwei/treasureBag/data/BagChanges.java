package org.weiwei.treasureBag.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次存檔要寫進資料庫的格位變更，以及同一次的存取紀錄
 * <p>
 * 每一格記的是「最後的樣子」（新內容或刪除），不是增減量，
 * 所以重複寫入、失敗後重試都不會多出或少掉物品。
 * 存取紀錄跟格位變更寫在同一筆交易，存檔成功才會留下紀錄。
 */
public class BagChanges {

    /** 紀錄來源：一般關背包 */
    public static final String SOURCE_CLOSE = "close";
    /** 紀錄來源：強制開啟掉落溢出物品 */
    public static final String SOURCE_FORCE = "force";

    private final UUID owner;
    private final String ownerName;
    private final Map<Integer, SlotChange> changes = new LinkedHashMap<>();
    private final List<LogEntry> logs = new ArrayList<>();
    private UUID viewer;
    private String viewerName;
    private String source = SOURCE_CLOSE;

    public BagChanges(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void put(SlotChange change) {
        changes.put(change.slot(), change);
    }

    public Collection<SlotChange> changes() {
        return changes.values();
    }

    public boolean isEmpty() {
        return changes.isEmpty() && logs.isEmpty();
    }

    public List<LogEntry> logs() {
        return logs;
    }

    public void addLog(LogEntry entry) {
        logs.add(entry);
    }

    public UUID viewer() {
        return viewer;
    }

    public String viewerName() {
        return viewerName;
    }

    public String source() {
        return source;
    }

    /**
     * 設定是誰、因為什麼造成這次變更（寫進存取紀錄）
     */
    public BagChanges by(UUID viewer, String viewerName, String source) {
        this.viewer = viewer;
        this.viewerName = viewerName;
        this.source = source;
        return this;
    }

    public int size() {
        return changes.size();
    }

    /**
     * 合併較新的變更：同一格以新的內容為準，但「讀進來時的原值」保留舊的，
     * 這樣嚴格模式比對資料庫時，比的仍是最早那次讀到的狀態
     */
    public BagChanges then(BagChanges newer) {
        BagChanges merged = new BagChanges(owner, newer.ownerName != null ? newer.ownerName : ownerName)
                .by(newer.viewer, newer.viewerName, newer.source);
        changes.values().forEach(merged::put);
        merged.logs.addAll(logs);
        merged.logs.addAll(newer.logs);
        for (SlotChange change : newer.changes()) {
            SlotChange older = merged.changes.get(change.slot());
            String expected = older != null ? older.expected() : change.expected();
            merged.put(new SlotChange(change.slot(), expected, change.value(), change.itemName()));
        }
        return merged;
    }

    /**
     * 單一格位的變更
     *
     * @param slot     全域格位
     * @param expected 讀進背包時資料庫裡的內容（null 表示原本是空格）
     * @param value    要寫入的內容（null 表示刪除這格）
     * @param itemName 物品名稱，只給人工查資料用
     */
    public record SlotChange(int slot, String expected, String value, String itemName) {
    }

    /**
     * 一種物品這次的淨增減
     *
     * @param material 物品種類
     * @param itemName 物品名稱
     * @param amount   正數放入、負數取出
     */
    public record LogEntry(String material, String itemName, int amount) {
    }
}
