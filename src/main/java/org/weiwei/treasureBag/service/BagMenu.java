package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.weiwei.treasureBag.util.ItemManage;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 背包介面的畫面：標題、內容區、底部控制列
 * <p>
 * 控制列（第 46～54 格）：45 整理、46 一鍵存入、47～51 頁籤、52 礦物倉庫分頁（只有主人自己開時有）、53 資訊。
 * 按鈕用原版物品＋CustomModelData，沒裝材質包的玩家也看得懂是什麼。
 * 頁籤圖示只有 1～5，背包超過 5 頁時頁籤會跟著目前頁滑動，名稱寫的仍是真正頁碼。
 */
final class BagMenu {

    static final int PAGE_CONTENT_SIZE = 45;
    static final int SORT_SLOT = 45;
    static final int DEPOSIT_SLOT = 46;
    static final int FIRST_TAB_SLOT = 47;
    static final int TAB_COUNT = 5;
    static final int ORE_TAB_SLOT = 52;
    static final int INFO_SLOT = 53;

    private static final int CMD_BUTTON = 870;
    private static final int CMD_TAB = 870;
    private static final int CMD_TAB_CURRENT = 875;
    private static final int CMD_TAB_LOCKED = 880;
    private static final int CMD_LOCKED_SLOT = 870;
    private static final int CMD_ORE_TAB = 881;
    private static final int CMD_ORE_TAB_CURRENT = 882;

    private BagMenu() {
    }

    /**
     * 填入內容區（前 45 格）：超出可用格數放鎖住的格子，讀不出來的格位放損壞提示
     */
    static void fillContent(Inventory inventory, BagSession session) {
        int pageOffset = session.page() * PAGE_CONTENT_SIZE;
        for (int slot = 0; slot < PAGE_CONTENT_SIZE; slot++) {
            int globalSlot = pageOffset + slot;
            if (globalSlot >= session.maxSlot()) {
                inventory.setItem(slot, lockedSlot());
                continue;
            }
            if (session.brokenSlots().contains(globalSlot)) {
                inventory.setItem(slot, ItemManage.getBrokenItem());
                continue;
            }
            ItemStack item = session.items().get(globalSlot);
            inventory.setItem(slot, item == null ? null : item.clone());
        }
    }

    /**
     * 填入底部控制列
     *
     * @param canEdit 觀看者能不能改內容（唯讀時不放整理、存入）
     * @param isOwner 觀看者是不是背包主人（只有主人能幫頁面取名字）
     */
    static void fillControlRow(Inventory inventory, BagSession session, boolean canEdit, boolean isOwner) {
        for (int slot = PAGE_CONTENT_SIZE; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }

        if (canEdit) {
            inventory.setItem(SORT_SLOT, ItemManage.createItem(Material.BRUSH, "§e整理這一頁",
                    List.of("§7依種類排好，同種物品自動疊在一起", "§7其他頁不會動到"), CMD_BUTTON));
            inventory.setItem(DEPOSIT_SLOT, ItemManage.createItem(Material.HOPPER, "§e一鍵存入",
                    List.of("§7身上的物品（不含快捷列）", "§7只要背包裡已經有同種的，就全部存進來"), CMD_BUTTON));
        }

        int maxPage = maxPage(session.maxSlot());
        int start = firstTabPage(session.page(), maxPage);
        for (int i = 0; i < TAB_COUNT; i++) {
            inventory.setItem(FIRST_TAB_SLOT + i, tab(session, start + i, i, maxPage, isOwner && canEdit));
        }

        if (isOwner && canEdit) inventory.setItem(ORE_TAB_SLOT, oreTab(false, 0, 1));
        inventory.setItem(INFO_SLOT, info(session, maxPage, canEdit));
    }

    /**
     * 礦物倉庫分頁
     *
     * @param current 目前就在礦物倉庫（點了翻頁）
     */
    static ItemStack oreTab(boolean current, int page, int maxPage) {
        List<String> lore = new ArrayList<>();
        if (current) {
            lore.add("§a目前在這一頁");
            if (maxPage > 1) {
                lore.add("§7第 §f" + (page + 1) + "§7/§f" + maxPage + " §7頁");
                lore.add("§e左鍵 §7下一頁　§e右鍵 §7上一頁");
            }
        } else {
            lore.add("§7挖方塊時身上放不下的東西會收進來");
            lore.add("§7每個分流都是同一個倉庫");
            lore.add("§e左鍵 §7打開");
        }
        return ItemManage.createItem(Material.PAPER, "§f礦物倉庫", lore, current ? CMD_ORE_TAB_CURRENT : CMD_ORE_TAB);
    }

    /**
     * 礦物倉庫畫面上的背包頁籤（沒有讀背包，不顯示頁名）
     *
     * @param page     第幾頁（0 起算，也是第幾個頁籤）
     * @param bagPages 背包可用頁數
     */
    static ItemStack plainTab(int page, int bagPages) {
        if (page >= bagPages) {
            return ItemManage.createItem(Material.PAPER, "§8第 " + (page + 1) + " 頁（未開放）",
                    List.of("§7升級 VIP 可以多開一頁（每頁 45 格）"), CMD_TAB_LOCKED);
        }
        return ItemManage.createItem(Material.PAPER, "§f第 " + (page + 1) + " 頁",
                List.of("§e左鍵 §7回到背包這一頁"), CMD_TAB + page);
    }

    /**
     * 點到的頁籤是第幾頁（0 起算）；不是頁籤或是鎖住的頁回傳 -1
     */
    static int tabPage(BagSession session, int rawSlot) {
        if (rawSlot < FIRST_TAB_SLOT || rawSlot >= FIRST_TAB_SLOT + TAB_COUNT) return -1;
        int maxPage = maxPage(session.maxSlot());
        int page = firstTabPage(session.page(), maxPage) + rawSlot - FIRST_TAB_SLOT;
        return page < maxPage ? page : -1;
    }

    /**
     * 介面標題：有材質包時用背景字＋標題字型，沒有時用純文字
     */
    static Component title(Player viewer, BagSession session, String ownerName, boolean isOwner) {
        String pageName = session.pageNames().get(session.page());

        if (!BagTitle.hasResourcePack(viewer)) {
            int maxPage = maxPage(session.maxSlot());
            return Component.text(Message.getMsg(Message.MENU_TITLE__BAG_MENU, session.page() + 1, maxPage)
                    + (pageName != null ? " §8· §1" + pageName : "")
                    + (isOwner ? "" : " §8(" + ownerName + ")"));
        }

        // 有材質包時頁碼看頁籤就知道，標題只放頁名；看別人的背包時補上對方名字
        String suffix = pageName != null ? "· " + pageName : "";
        if (!isOwner) suffix = (suffix + " (" + ownerName + ")").trim();
        return BagTitle.textured(suffix);
    }

    static int maxPage(int maxSlot) {
        return Math.max(1, (int) Math.ceil(maxSlot / (double) PAGE_CONTENT_SIZE));
    }

    private static int firstTabPage(int page, int maxPage) {
        if (maxPage <= TAB_COUNT) return 0;
        return Math.max(0, Math.min(page - TAB_COUNT / 2, maxPage - TAB_COUNT));
    }

    private static ItemStack tab(BagSession session, int page, int position, int maxPage, boolean canRename) {
        if (page >= maxPage) {
            return ItemManage.createItem(Material.PAPER, "§8第 " + (page + 1) + " 頁（未開放）",
                    List.of("§7升級 VIP 可以多開一頁（每頁 45 格）"), CMD_TAB_LOCKED);
        }

        String pageName = session.pageNames().get(page);
        String name = "§f第 " + (page + 1) + " 頁" + (pageName != null ? " §7· §f" + pageName : "");
        boolean current = page == session.page();
        List<String> lore = new ArrayList<>();
        lore.add(current ? "§a目前在這一頁" : "§e左鍵 §7切換到這一頁");
        if (canRename) lore.add("§e右鍵 §7幫這一頁取名字");
        return ItemManage.createItem(Material.PAPER, name, lore, (current ? CMD_TAB_CURRENT : CMD_TAB) + position);
    }

    private static ItemStack info(BagSession session, int maxPage, boolean canEdit) {
        int used = 0;
        for (int slot : session.items().keySet()) {
            if (slot < session.maxSlot()) used++;
        }
        used += session.brokenSlots().size();

        List<String> lore = new ArrayList<>();
        lore.add("§7目前頁數: §f" + (session.page() + 1) + "§7/§f" + maxPage);
        lore.add("§7已使用: §f" + used + "§7/§f" + session.maxSlot() + " 格");
        if (!canEdit) lore.add("§e唯讀模式");
        return ItemManage.createItem(Material.BOOK, "§b背包資訊", lore, CMD_BUTTON);
    }

    private static ItemStack lockedSlot() {
        return ItemManage.createItem(Material.GRAY_STAINED_GLASS_PANE, "§8未開放的格子",
                List.of("§7升級 VIP 可以使用更多格子"), CMD_LOCKED_SLOT);
    }
}
