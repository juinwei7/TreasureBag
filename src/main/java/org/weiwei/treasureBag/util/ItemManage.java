package org.weiwei.treasureBag.util;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

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
        return createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null, 300);
    }



}
