package org.weiwei.treasureBag.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一次存檔要寫進資料庫的格位變更
 * <p>
 * 每一格記的是「最後的樣子」（新內容或刪除），不是增減量，
 * 所以重複寫入、失敗後重試都不會多出或少掉物品。
 */
public class BagChanges {

    private final UUID owner;
    private final String ownerName;
    private final Map<Integer, SlotChange> changes = new LinkedHashMap<>();

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
        return changes.isEmpty();
    }

    public int size() {
        return changes.size();
    }

    /**
     * 合併較新的變更：同一格以新的內容為準，但「讀進來時的原值」保留舊的，
     * 這樣嚴格模式比對資料庫時，比的仍是最早那次讀到的狀態
     */
    public BagChanges then(BagChanges newer) {
        BagChanges merged = new BagChanges(owner, newer.ownerName != null ? newer.ownerName : ownerName);
        changes.values().forEach(merged::put);
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
}
