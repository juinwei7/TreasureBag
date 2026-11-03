package org.weiwei.treasureBag.util;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class ItemManage {

    /**
     * 創建一般物品
     * @return ItemStack
     */
    public static ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta itemMeta = item.getItemMeta();
        itemMeta.setDisplayName(name);
        item.setItemMeta(itemMeta);
        return item;
    }

    /**
     * 創建一般物品
     * @return ItemStack
     */
    public static ItemStack createItem(Material material, Message msg) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta itemMeta = item.getItemMeta();
        String s = Message.getMsg(msg);
        itemMeta.setDisplayName(s);
        item.setItemMeta(itemMeta);
        return item;
    }

    /**
     * 創建物品 (含 CustomModelData)
     * @return ItemStack
     */
    public static ItemStack createItem(Material material, String name, List<String> lore, int setCustomModelData) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta itemMeta = item.getItemMeta();
        itemMeta.setDisplayName(name);
        if (lore != null) itemMeta.setLore(lore);
        itemMeta.setCustomModelData(setCustomModelData);
        item.setItemMeta(itemMeta);
        return item;
    }

    /**
     * 創建物品 包含Nbt
     * @return ItemStack
     */
    public static ItemStack getDefaultItem() {
        return createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null, 0);
    }

    /**
     * 讀不出來的格位佔位物品（該格資料保留在資料庫，不能操作）
     */
    public static ItemStack getBrokenItem() {
        return createItem(Material.BARRIER, Message.getMsg(Message.MENU_ITEM__BROKEN_NAME),
                Message.getStringList(Message.MENU_ITEM__BROKEN_LORE), 0);
    }

    /**
     * 物品名稱：有自訂名稱用自訂名稱，否則用物品種類
     */
    public static String nameOf(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().name();
    }



}
