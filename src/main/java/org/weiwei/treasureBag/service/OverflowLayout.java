package org.weiwei.treasureBag.service;

/**
 * 超出格取出介面的純計算：分頁、身上放不放得下（不碰 Bukkit，有單元測試）
 */
final class OverflowLayout {

    /** 每頁幾格內容（跟背包一樣前 45 格，最下面一排是控制列） */
    static final int PAGE_SIZE = BagMenu.PAGE_CONTENT_SIZE;

    private OverflowLayout() {
    }

    /**
     * 總頁數（至少 1，全部拿完時也有一頁空的）
     *
     * @param total 還沒拿的超出格數量
     */
    static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /**
     * 頁碼夾在 0～最後一頁（拿走最後一頁唯一的一件時退回前一頁）
     */
    static int clampPage(int page, int total) {
        return Math.max(0, Math.min(page, pageCount(total) - 1));
    }

    /**
     * 第 page 頁的第 rawSlot 格是清單裡的第幾件；控制列、空格回傳 -1
     */
    static int indexAt(int page, int rawSlot, int total) {
        if (page < 0 || rawSlot < 0 || rawSlot >= PAGE_SIZE) return -1;
        int index = page * PAGE_SIZE + rawSlot;
        return index < total ? index : -1;
    }

    /**
     * 整組放不放得下：先疊到身上同種的堆，剩下的每 maxStack 件佔一個空格
     * （跟 addItem 的放法一樣；件數超過一組上限的會拆成好幾格）
     *
     * @param amount     要放的件數
     * @param maxStack   這種物品一格最多幾件
     * @param stackRoom  身上同種的堆加起來還能疊幾件
     * @param emptySlots 身上幾個空格
     */
    static boolean fits(int amount, int maxStack, int stackRoom, int emptySlots) {
        int rest = amount - Math.max(0, stackRoom);
        if (rest <= 0) return true;
        int perSlot = Math.max(1, maxStack);
        return (rest + perSlot - 1) / perSlot <= emptySlots;
    }
}
