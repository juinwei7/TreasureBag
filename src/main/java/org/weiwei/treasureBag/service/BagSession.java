package org.weiwei.treasureBag.service;

import org.bukkit.inventory.ItemStack;
import org.weiwei.treasureBag.data.BagChanges;
import org.weiwei.treasureBag.entity.PlayerInfo;
import org.weiwei.treasureBag.util.ItemManage;
import org.weiwei.treasureBag.util.ItemSerialize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一位觀看者開著的背包
 * <p>
 * items 是目前畫面上的內容（全域格位 → 物品）；baseline / baselineItems 是讀進來時資料庫的原始字串與解出來的物品，
 * 關背包時比對，只把有變的格位寫回去。brokenSlots 是讀不出來的格位，
 * 畫面上鎖住、存檔時跳過，資料庫那一列永遠不會被覆寫或刪掉。
 */
class BagSession {

    private final PlayerInfo owner;
    private final int maxSlot;
    private final Map<Integer, ItemStack> items;
    private final Map<Integer, String> baseline;
    private final Map<Integer, ItemStack> baselineItems;
    private final Set<Integer> brokenSlots;
    private final Map<Integer, String> pageNames;
    private final boolean readOnly;
    private int page;

    BagSession(PlayerInfo owner, int maxSlot, Map<Integer, ItemStack> items, Map<Integer, String> baseline,
               Map<Integer, ItemStack> baselineItems, Set<Integer> brokenSlots, Map<Integer, String> pageNames,
               boolean readOnly) {
        this.owner = owner;
        this.maxSlot = maxSlot;
        this.items = items;
        this.baseline = baseline;
        this.baselineItems = baselineItems;
        this.brokenSlots = brokenSlots;
        this.pageNames = pageNames;
        this.readOnly = readOnly;
    }

    PlayerInfo owner() {
        return owner;
    }

    UUID ownerUuid() {
        return owner.getPlayerUUID();
    }

    int maxSlot() {
        return maxSlot;
    }

    Map<Integer, ItemStack> items() {
        return items;
    }

    Set<Integer> brokenSlots() {
        return brokenSlots;
    }

    Map<Integer, String> pageNames() {
        return pageNames;
    }

    boolean readOnly() {
        return readOnly;
    }

    int page() {
        return page;
    }

    void setPage(int page) {
        this.page = page;
    }

    /**
     * 結束 session 時取出要存的內容；序列化與比對交給 IO 執行緒跑
     * session 結束後 items 不會再被改動，直接交出去不必複製
     */
    Snapshot snapshot(UUID viewer, String viewerName) {
        return new Snapshot(owner.getPlayerUUID(), owner.getPlayerName(), viewer, viewerName, maxSlot,
                new HashMap<>(items), baseline, baselineItems, brokenSlots);
    }

    record Snapshot(UUID owner, String ownerName, UUID viewer, String viewerName, int maxSlot,
                    Map<Integer, ItemStack> items, Map<Integer, String> baseline,
                    Map<Integer, ItemStack> baselineItems, Set<Integer> brokenSlots) {

        /**
         * 算出跟讀進來時不同的格位
         * 先比物品本身：同一個物品重新序列化，字串不一定跟資料庫存的一樣（實測有自訂名稱的物品會在兩種寫法間來回），
         * 只比字串的話這類格位每次關背包都會被重寫。物品相同就跳過，不同才序列化。
         * 單一物品序列化失敗時保留資料庫原值並記錄，不讓整包存檔失敗
         */
        BagChanges diff() {
            BagChanges changes = new BagChanges(owner, ownerName).by(viewer, viewerName, BagChanges.SOURCE_CLOSE);
            for (int slot = 0; slot < maxSlot; slot++) {
                if (brokenSlots.contains(slot)) continue;

                ItemStack item = items.get(slot);
                String expected = baseline.get(slot);
                if (item == null || item.getType().isAir()) {
                    if (expected != null) changes.put(new BagChanges.SlotChange(slot, expected, null, null));
                    continue;
                }

                ItemStack original = baselineItems.get(slot);
                if (expected != null && original != null && item.equals(original)) continue;

                String value;
                try {
                    value = ItemSerialize.itemToBase64(item);
                } catch (Exception e) {
                    BagStorage.logSevere("無法序列化物品，該格維持原狀: " + ownerName + " 格位 " + slot
                            + " " + item.getType() + " x" + item.getAmount() + " " + ItemManage.nameOf(item), e);
                    continue;
                }
                if (!value.equals(expected)) {
                    changes.put(new BagChanges.SlotChange(slot, expected, value, ItemManage.nameOf(item)));
                }
            }
            if (!changes.changes().isEmpty()) netChanges().forEach(changes::addLog);
            return changes;
        }

        /**
         * 每種物品這次的淨增減（背包內換位置不算）
         * 同種的判斷用 isSimilar，數量不同的同一種物品會合併計算
         */
        private List<BagChanges.LogEntry> netChanges() {
            List<ItemStack> kinds = new ArrayList<>();
            List<int[]> counts = new ArrayList<>();
            for (int slot = 0; slot < maxSlot; slot++) {
                if (brokenSlots.contains(slot)) continue;
                count(kinds, counts, baselineItems.get(slot), 0);
                count(kinds, counts, items.get(slot), 1);
            }

            List<BagChanges.LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < kinds.size(); i++) {
                int delta = counts.get(i)[1] - counts.get(i)[0];
                if (delta == 0) continue;
                ItemStack kind = kinds.get(i);
                entries.add(new BagChanges.LogEntry(kind.getType().name(), ItemManage.nameOf(kind), delta));
            }
            return entries;
        }

        private static void count(List<ItemStack> kinds, List<int[]> counts, ItemStack item, int side) {
            if (item == null || item.getType().isAir()) return;
            for (int i = 0; i < kinds.size(); i++) {
                if (kinds.get(i).isSimilar(item)) {
                    counts.get(i)[side] += item.getAmount();
                    return;
                }
            }
            kinds.add(item);
            int[] count = new int[2];
            count[side] = item.getAmount();
            counts.add(count);
        }
    }
}
