package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.weiwei.treasureBag.util.ItemManage;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 超出格取出介面的畫面：前 45 格放還沒拿的超出格物品（照原本的格位排），最下面一排是控制列
 * <p>
 * 控制列：45 上一頁、47 回到背包、49 說明、53 下一頁（沒有上一頁／下一頁時那格空著）。
 * 按鈕都是原版物品、不設 CustomModelData，標題是純文字，沒裝材質包也看得懂。
 */
final class OverflowMenu {

    static final int PREV_SLOT = 45;
    static final int BACK_SLOT = 47;
    static final int HELP_SLOT = 49;
    static final int NEXT_SLOT = 53;
    /** 全部拿完時，內容區正中間放提示 */
    private static final int DONE_SLOT = 22;

    private OverflowMenu() {
    }

    static Component title() {
        return Component.text(Message.getMsg(Message.MENU_TITLE__OVERFLOW_MENU));
    }

    /**
     * 畫整個介面
     *
     * @param slots   還沒拿的超出格（全域格位，由小到大）
     * @param items   全域格位 → 物品
     * @param page    目前第幾頁（0 起算，呼叫端已夾好範圍）
     * @param maxSlot 背包現在能用的格數
     */
    static void fill(Inventory inventory, List<Integer> slots, Map<Integer, ItemStack> items, int page, int maxSlot) {
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, null);

        int total = slots.size();
        for (int rawSlot = 0; rawSlot < OverflowLayout.PAGE_SIZE; rawSlot++) {
            int index = OverflowLayout.indexAt(page, rawSlot, total);
            if (index < 0) break;
            int slot = slots.get(index);
            inventory.setItem(rawSlot, entry(items.get(slot), slot));
        }
        if (total == 0) {
            inventory.setItem(DONE_SLOT, ItemManage.createItem(Material.LIME_DYE, "§a超出格都拿完了",
                    List.of("§7點下面的「回到背包」就能正常使用背包")));
        }

        int pages = OverflowLayout.pageCount(total);
        String pageLine = "§7第 §f" + (page + 1) + "§7/§f" + pages + " §7頁";
        if (page > 0) inventory.setItem(PREV_SLOT, ItemManage.createItem(Material.ARROW, "§e上一頁", List.of(pageLine)));
        if (page < pages - 1) inventory.setItem(NEXT_SLOT, ItemManage.createItem(Material.ARROW, "§e下一頁", List.of(pageLine)));
        inventory.setItem(BACK_SLOT, back(total));
        inventory.setItem(HELP_SLOT, help(total, pages > 1 ? pageLine : null, maxSlot));
    }

    /**
     * 超出格物品本身（複製一份，說明後面接上原本在第幾頁、怎麼拿）；給玩家的是 session 裡的那份，不是這個
     */
    private static ItemStack entry(ItemStack item, int slot) {
        ItemStack display = item.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        List<String> lore = new ArrayList<>();
        if (meta.hasLore() && meta.getLore() != null) {
            lore.addAll(meta.getLore());
            lore.add("");
        }
        lore.add("§8原本在背包第 " + (slot / BagMenu.PAGE_CONTENT_SIZE + 1) + " 頁");
        lore.add("§e點一下 §7拿到身上");
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private static ItemStack back(int remaining) {
        if (remaining > 0) {
            return ItemManage.createItem(Material.CHEST, "§7回到背包", List.of(
                    "§c還有 §f" + remaining + " §c格超出格沒拿完",
                    "§7背包格數變少了，超出的格子要先清空",
                    "§7才能正常開背包"));
        }
        return ItemManage.createItem(Material.CHEST, "§a回到背包", List.of("§e點一下 §7打開背包"));
    }

    private static ItemStack help(int remaining, String pageLine, int maxSlot) {
        List<String> lore = new ArrayList<>();
        lore.add("§7背包格數變少了（現在能用 §f" + maxSlot + " §7格），");
        lore.add("§7上面這些是超出的格子，東西都還在。");
        lore.add("§e點一下 §7就拿到身上，拿完就能正常開背包。");
        lore.add("§7身上要有空位，放不下的不會拿出來。");
        lore.add("");
        lore.add("§7還有 §f" + remaining + " §7格沒拿");
        if (pageLine != null) lore.add(pageLine);
        return ItemManage.createItem(Material.BOOK, "§e超出格取出", lore);
    }
}
