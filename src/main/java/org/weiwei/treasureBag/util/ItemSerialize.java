package org.weiwei.treasureBag.util;

import org.bukkit.inventory.ItemStack;
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
}
