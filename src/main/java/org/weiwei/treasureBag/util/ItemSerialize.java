package org.weiwei.treasureBag.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.weiwei.treasureBag.Main;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * ItemSerialize 工具包 - 提供物品序列化/反序列化相關方法
 */

public class ItemSerialize {

    // 📌 ========== 🛠 單一物品序列化/反序列化 🛠 ==========

    /**
     * 將單一 `ItemStack` 物品轉換為 Base64 字串
     *
     * @param item `ItemStack`
     * @return Base64 字串
     * @throws IllegalStateException 無法轉換時拋出異常
     */
    public static String itemToBase64(ItemStack item) throws IllegalStateException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeObject(item); // 序列化物品
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("無法儲存 ItemStack", e);
        }
    }

    /**
     * 透過 Base64 字串反序列化回 `ItemStack`
     *
     * @param base64Data Base64 字串
     * @return 反序列化後的 `ItemStack`
     * @throws IOException 無法解碼時拋出異常
     */
    public static ItemStack itemFromBase64(String base64Data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(base64Data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            Main.getInst().getLogger().warning("❌ 無法解碼 ItemStack: " + e.getMessage());
            return null;
        }
    }

    // 📌 ========== 🛠 玩家背包序列化/反序列化 🛠 ==========

    /**
     * 將玩家的 `PlayerInventory` 轉換為 Base64 字串陣列
     * 第一個字串代表主要物品欄，第二個字串代表裝備欄
     *
     * @param playerInventory 玩家物品欄
     * @return Base64 字串陣列: [ 主要物品欄, 裝備欄 ]
     * @throws IllegalStateException 無法轉換時拋出異常
     */
    public static String[] playerInventoryToBase64(PlayerInventory playerInventory) throws IllegalStateException {
        String content = inventoryToBase64(playerInventory); // 轉換主要物品欄
        String armor = itemStackArrayToBase64(playerInventory.getArmorContents()); // 轉換裝備欄
        return new String[]{content, armor};
    }

    // 📌 ========== 🛠 `Inventory` 轉 Base64 🛠 ==========

    /**
     * 將 `Inventory` 序列化為 Base64 字串
     *
     * @param inventory 物品欄
     * @return Base64 字串
     * @throws IllegalStateException 無法轉換時拋出異常
     */
    public static String inventoryToBase64(Inventory inventory) throws IllegalStateException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(inventory.getSize()); // 儲存物品欄大小
            for (int i = 0; i < inventory.getSize(); i++) {
                dataOutput.writeObject(inventory.getItem(i)); // 序列化每個格子的物品
            }

            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("無法儲存物品欄", e);
        }
    }

    /**
     * 透過 Base64 字串反序列化回 `Inventory`
     *
     * @param data Base64 物品欄數據
     * @return 反序列化後的 `Inventory`
     * @throws IOException 當無法解碼時拋出異常
     */
    public static Inventory inventoryFromBase64(String data) throws IOException {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            Inventory inventory = Bukkit.createInventory(null, dataInput.readInt());

            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, (ItemStack) dataInput.readObject()); // 讀取並恢復物品
            }

            dataInput.close();
            return inventory;
        } catch (ClassNotFoundException e) {
            throw new IOException("無法解碼類型", e);
        }
    }

    // 📌 ========== 🛠 `ItemStack[]` 轉 Base64 🛠 ==========

    /**
     * 將 `ItemStack[]` 物品陣列序列化為 Base64 字串
     *
     * @param items `ItemStack` 陣列
     * @return Base64 字串
     * @throws IllegalStateException 無法轉換時拋出異常
     */
    public static String itemStackArrayToBase64(ItemStack[] items) throws IllegalStateException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length); // 儲存物品陣列大小
            for (ItemStack item : items) {
                dataOutput.writeObject(item); // 序列化物品
            }

            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("無法儲存 ItemStack 陣列", e);
        }
    }

    /**
     * 透過 Base64 字串反序列化回 `ItemStack[]` 陣列
     *
     * @param data Base64 物品數據
     * @return 反序列化後的 `ItemStack[]`
     * @throws IOException 當無法解碼時拋出異常
     */
    public static ItemStack[] itemStackArrayFromBase64(String data) throws IOException {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack[] items = new ItemStack[dataInput.readInt()];

            for (int i = 0; i < items.length; i++) {
                items[i] = (ItemStack) dataInput.readObject(); // 讀取物品
            }

            dataInput.close();
            return items;
        } catch (ClassNotFoundException e) {
            throw new IOException("無法解碼類型", e);
        }
    }
}
