package org.weiwei.treasureBag.service;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * MythicMobs 物品：讀物品的代號、照代號產生物品
 * <p>
 * 代號存在 PDC {@code mythicmobs:type}（MythicMobs 5.14，不是舊的 NBT MYTHIC_TYPE），讀它不用 MythicMobs。
 * 產生物品要 MythicMobs：沒裝、還沒載入或版本不合時回傳 null，副本素材倉庫只能看不能取。
 * MythicMobs 的類別只在 {@link Api} 裡碰，沒裝 MythicMobs 時這個類別照樣載入得了。
 */
final class MythicItems {

    private static final NamespacedKey TYPE = new NamespacedKey("mythicmobs", "type");
    /** 畫面圖示（數量 1）；/treasurebag reload 清掉，取出一律重新產生 */
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static boolean broken;

    private MythicItems() {
    }

    /**
     * 物品的 MythicMobs 代號；不是 MythicMobs 物品回傳 null
     */
    static String type(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return item.getPersistentDataContainer().get(TYPE, PersistentDataType.STRING);
    }

    /**
     * 照代號產生物品；產生不了（沒有這個代號、MythicMobs 沒載入）回傳 null
     */
    static ItemStack create(String id, int amount) {
        if (broken || !Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return null;
        try {
            return Api.generate(id, amount);
        } catch (LinkageError e) {
            // MythicMobs 版本不合，之後不再試
            broken = true;
            BagStorage.logWarning("無法使用 MythicMobs 產生物品（" + e + "），副本素材倉庫暫時取不出東西");
            return null;
        } catch (RuntimeException e) {
            BagStorage.logWarning("MythicMobs 產生物品失敗: " + id + " " + e);
            return null;
        }
    }

    /**
     * 畫面用的圖示（數量 1，會快取）；產生不了回傳 null
     */
    static ItemStack icon(String id) {
        ItemStack cached = ICONS.get(id);
        if (cached != null) return cached.clone();
        ItemStack icon = create(id, 1);
        if (icon == null) return null;
        ICONS.put(id, icon);
        return icon.clone();
    }

    static void clearIcons() {
        ICONS.clear();
    }

    private static final class Api {
        static ItemStack generate(String id, int amount) {
            return MythicBukkit.inst().getItemManager().getItem(id)
                    .map(item -> BukkitAdapter.adapt(item.generateItemStack(amount)))
                    .orElse(null);
        }
    }
}
