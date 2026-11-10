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
 * 倉庫的畫面（礦物倉庫、副本素材倉庫共用）：跟背包同一張背景（U+E0A0），內容區每種物品一格、數量寫在說明裡
 * <p>
 * 控制列：45 一鍵存入、46 自動收集總開關、47～51 背包頁籤（點了回背包）、52 礦物倉庫分頁、53 副本素材分頁。
 * 目前這個倉庫的分頁說明裡寫容量與種類，多於 45 種時點它翻頁；點另一個倉庫的分頁切過去。
 */
final class VaultMenu {

    static final int PAGE_SIZE = BagMenu.PAGE_CONTENT_SIZE;
    static final int DEPOSIT_SLOT = 45;
    static final int TOGGLE_SLOT = 46;

    private static final int CMD_BUTTON = 870;

    private VaultMenu() {
    }

    static int maxPage(VaultAccount account) {
        return maxPage(account.visible().size());
    }

    private static int maxPage(int kinds) {
        return Math.max(1, (int) Math.ceil(kinds / (double) PAGE_SIZE));
    }

    /**
     * 內容區第 slot 格是哪種物品的鍵；空格回傳 null
     */
    static String keyAt(VaultAccount account, int page, int slot) {
        List<String> visible = account.visible();
        int index = page * PAGE_SIZE + slot;
        return slot >= 0 && slot < PAGE_SIZE && index < visible.size() ? visible.get(index) : null;
    }

    static void fill(Inventory inventory, Player viewer, VaultAccount account, int page, boolean readOnly) {
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, null);

        VaultKind kind = account.kind;
        List<String> visible = account.visible();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            if (index >= visible.size()) break;
            String key = visible.get(index);
            inventory.setItem(slot, entry(kind, key, account.entries.get(key), readOnly));
        }

        if (!readOnly) {
            inventory.setItem(DEPOSIT_SLOT, ItemManage.createItem(Material.HOPPER, "§e一鍵存入", kind.depositLore(), CMD_BUTTON));
            inventory.setItem(TOGGLE_SLOT, toggle(kind, account.enabled));
            int bagPages = BagMenu.maxPage(PlayerBagService.getMaxSlot(viewer));
            for (int i = 0; i < BagMenu.TAB_COUNT; i++) {
                inventory.setItem(BagMenu.FIRST_TAB_SLOT + i, BagMenu.plainTab(i, bagPages));
            }
            for (VaultKind other : VaultKind.ALL) {
                if (other != kind) inventory.setItem(other.tabSlot, tab(other, viewer));
            }
        }
        inventory.setItem(kind.tabSlot, currentTab(account, visible.size(), page, readOnly));
    }

    static Component title(VaultKind kind, Player viewer, String ownerName, boolean isOwner) {
        if (!BagTitle.hasResourcePack(viewer)) {
            return Component.text("§1§l" + kind.title + (isOwner ? "" : " §8(" + ownerName + ")"));
        }
        return BagTitle.textured(kind.title, isOwner ? null : "(" + ownerName + ")");
    }

    /**
     * 倉庫分頁（不在這一頁時）：背包與另一個倉庫的畫面上用
     */
    static ItemStack tab(VaultKind kind, Player viewer) {
        List<String> lore = new ArrayList<>(kind.tabLore());
        lore.add(VaultService.of(kind).usable(viewer) ? "§e左鍵 §7打開" : "§cVIP 以上才能使用");
        return ItemManage.createItem(Material.PAPER, "§f" + kind.label, lore, kind.tabCmd);
    }

    /**
     * 目前這個倉庫的分頁：容量、種類、開關狀態與用法（原本資訊書的內容），種類多於一頁時點它翻頁
     */
    private static ItemStack currentTab(VaultAccount account, int kinds, int page, boolean readOnly) {
        VaultKind kind = account.kind;
        long total = account.total();
        long space = account.space();
        List<String> lore = new ArrayList<>();
        lore.add("§a目前在這一頁");
        if (account.spaceKnown()) {
            lore.add("§7已存 §f" + String.format("%,d", total) + "§7/§f" + String.format("%,d", space) + " 件");
            if (space <= 0 && kind.noSpace != null) {
                if (total > 0) lore.add("§cVIP 以上才能存入，現在只能取出");
            } else if (total > space) {
                lore.add("§c超過容量：只能取出");
                lore.add("§c取到 " + String.format("%,d", space) + " 件以下才能再存");
            }
        } else {
            lore.add("§7已存 §f" + String.format("%,d", total) + " 件");
            lore.add("§7容量看對方的 VIP 階級");
        }
        lore.add("§7種類 §f" + kinds);
        lore.add("§7自動收集 " + (account.enabled ? "§a開" : "§8關"));
        int maxPage = maxPage(kinds);
        if (maxPage > 1) {
            lore.add("§7第 §f" + (page + 1) + "§7/§f" + maxPage + " §7頁");
            lore.add("§e左鍵 §7下一頁　§e右鍵 §7上一頁");
        }
        lore.add("");
        lore.addAll(kind.helpLore());
        if (readOnly) lore.add("§e唯讀模式");
        return ItemManage.createItem(Material.PAPER, "§f" + kind.label, lore, kind.tabCmd + 1);
    }

    private static ItemStack entry(VaultKind kind, String key, VaultAccount.Entry entry, boolean readOnly) {
        ItemStack item = kind.icon(key);
        if (item == null) item = missing(key);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // 副本素材保留原本的說明（所屬副本等），接在後面
        List<String> lore = new ArrayList<>();
        if (meta.hasLore() && meta.getLore() != null) {
            lore.addAll(meta.getLore());
            lore.add("");
        }
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

    /**
     * 產生不了的物品（MythicMobs 拿掉了這個代號、或還沒載入）：數量照樣顯示，取不出來
     */
    private static ItemStack missing(String key) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§c" + key);
            meta.setLore(List.of("§7這個物品暫時產生不了，取不出來", "§7請截圖回報管理員"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack toggle(VaultKind kind, boolean enabled) {
        List<String> lore = new ArrayList<>(kind.toggleLore());
        lore.add("");
        lore.add("§e點擊 §7" + (enabled ? "關掉" : "打開"));
        return enabled
                ? ItemManage.createItem(Material.LIME_DYE, "§a自動收集：開", lore, kind.toggleCmd)
                : ItemManage.createItem(Material.GRAY_DYE, "§7自動收集：關", lore, kind.toggleCmd);
    }
}
