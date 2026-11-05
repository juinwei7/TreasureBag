package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.weiwei.treasureBag.util.ItemManage;

import java.util.ArrayList;
import java.util.List;

/**
 * 礦物倉庫的畫面：跟背包同一張背景（U+E0A0），內容區每種物品一格、名稱照原版、數量寫在說明裡
 * <p>
 * 控制列：45 一鍵存入、46 自動收集總開關、47～51 背包頁籤（點了回背包）、52 礦物倉庫分頁（多於 45 種時點它翻頁）、53 資訊。
 */
final class OreMenu {

    static final int PAGE_SIZE = BagMenu.PAGE_CONTENT_SIZE;
    static final int DEPOSIT_SLOT = 45;
    static final int TOGGLE_SLOT = 46;
    static final int ORE_TAB_SLOT = BagMenu.ORE_TAB_SLOT;
    static final int INFO_SLOT = 53;

    private static final int CMD_BUTTON = 870;

    private OreMenu() {
    }

    static int maxPage(OreAccount account) {
        return maxPage(account.visible().size());
    }

    private static int maxPage(int kinds) {
        return Math.max(1, (int) Math.ceil(kinds / (double) PAGE_SIZE));
    }

    /**
     * 內容區第 slot 格是哪種物品；空格回傳 null
     */
    static Material materialAt(OreAccount account, int page, int slot) {
        List<Material> visible = account.visible();
        int index = page * PAGE_SIZE + slot;
        return slot >= 0 && slot < PAGE_SIZE && index < visible.size() ? visible.get(index) : null;
    }

    static void fill(Inventory inventory, Player viewer, OreAccount account, int page, boolean readOnly) {
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, null);

        List<Material> visible = account.visible();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            if (index >= visible.size()) break;
            Material material = visible.get(index);
            inventory.setItem(slot, entry(material, account.entries.get(material), readOnly));
        }

        if (!readOnly) {
            inventory.setItem(DEPOSIT_SLOT, ItemManage.createItem(Material.HOPPER, "§e一鍵存入",
                    List.of("§7身上的物品（不含快捷列）", "§7清單裡有的種類全部存進來"), CMD_BUTTON));
            inventory.setItem(TOGGLE_SLOT, toggle(account.enabled));
            int bagPages = BagMenu.maxPage(PlayerBagService.getMaxSlot(viewer));
            for (int i = 0; i < BagMenu.TAB_COUNT; i++) {
                inventory.setItem(BagMenu.FIRST_TAB_SLOT + i, BagMenu.plainTab(i, bagPages));
            }
        }
        inventory.setItem(ORE_TAB_SLOT, BagMenu.oreTab(true, page, maxPage(visible.size())));
        inventory.setItem(INFO_SLOT, info(account, visible.size(), readOnly));
    }

    static Component title(Player viewer, String ownerName, boolean isOwner) {
        if (!BagTitle.hasResourcePack(viewer)) {
            return Component.text("§1§l礦物倉庫" + (isOwner ? "" : " §8(" + ownerName + ")"));
        }
        return BagTitle.textured(BagTitle.ORE_TITLE, isOwner ? null : "(" + ownerName + ")");
    }

    private static ItemStack entry(Material material, OreAccount.Entry entry, boolean readOnly) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = new ArrayList<>();
        lore.add("§7數量 §f" + String.format("%,d", entry.amount));
        lore.add("§7自動收集 " + (entry.auto ? "§a開" : "§8關"));
        if (!readOnly) {
            lore.add("");
            lore.add("§e左鍵 §7取出一組");
            lore.add("§eShift＋左鍵 §7取出到身上放滿");
            lore.add("§e右鍵 §7存入身上所有這種物品");
            lore.add("§eShift＋右鍵 §7開關自動收集");
            if (entry.amount == 0) lore.add("§eQ §7從清單移除");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack toggle(boolean enabled) {
        List<String> lore = List.of(
                "§7挖方塊時身上放不下的東西，",
                "§7清單裡有開自動收集的會收進倉庫",
                "",
                "§e點擊 §7" + (enabled ? "關掉" : "打開"));
        return enabled
                ? ItemManage.createItem(Material.LIME_DYE, "§a自動收集：開", lore, CMD_BUTTON)
                : ItemManage.createItem(Material.GRAY_DYE, "§7自動收集：關", lore, CMD_BUTTON);
    }

    private static ItemStack info(OreAccount account, int kinds, boolean readOnly) {
        long total = account.total();
        long space = account.space();
        List<String> lore = new ArrayList<>();
        lore.add("§7已存 §f" + String.format("%,d", total) + "§7/§f" + String.format("%,d", space) + " 件");
        lore.add("§7種類 §f" + kinds);
        lore.add("§7自動收集 " + (account.enabled ? "§a開" : "§8關"));
        lore.add("");
        lore.add("§7點身上的物品就能把它加進清單");
        lore.add("§7每個分流都能存取同一個倉庫");
        if (readOnly) lore.add("§e唯讀模式");
        return ItemManage.createItem(Material.BOOK, "§b礦物倉庫", lore, CMD_BUTTON);
    }
}
