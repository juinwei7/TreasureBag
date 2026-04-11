package org.weiwei.treasureBag.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.weiwei.treasureBag.Main;

import java.util.HashMap;
import java.util.Map;

/**
 * Cooldown 冷卻
 */

public class Cooldown {

    static BukkitTask cooldownTask;

    private static Map<String, Long> cooldownMap = new HashMap<>();

    /**
     * 檢查是否在冷卻中，若冷卻已過自動清除資料
     *
     * @param key 唯一辨識用 key，例如 playerUUID:action
     * @return 是否仍在冷卻中
     */
    public static boolean isOnCooldown(String key) {
        Long cooldownEndTime = cooldownMap.get(key);
        if (cooldownEndTime == null) return false;
        long currentTime = System.currentTimeMillis();
        if (cooldownEndTime < currentTime) {
            cooldownMap.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 取得剩餘秒數（若未在冷卻中則回傳 0）
     */
    public static int getRemainingSeconds(String key) {
        Long endTime = cooldownMap.get(key);
        if (endTime == null) return 0;
        long remain = endTime - System.currentTimeMillis();
        return (int) Math.max(remain / 1000L, 0);
    }

    /**
     * 在冷卻時提示玩家（用 actionbar），回傳是否仍在冷卻
     *
     * @param key     唯一識別 key（如 player.getName() + ":sell"）
     * @param player  玩家
     * @param message 提示文字前綴，例如 "冷卻中，還有 "
     * @return 是否在冷卻中
     */
    public static boolean checkAndNotify(String key, Player player, String message) {
        if (isOnCooldown(key)) {
            int seconds = getRemainingSeconds(key);
            message = (message != null) ? message : "";
            message = message.replace("{0}", String.valueOf(seconds));
            message = message.replace("&", "§");
            sendActionBar(player, message);
            return true;
        }
        return false;
    }

    /**
     * 設定冷卻時間（以 key 區分）
     *
     * @param key     冷卻辨識用 key（可用 itemName、playerName:action 等）
     * @param seconds 冷卻秒數
     */
    public static void setCooldown(String key, int seconds) {
        long cooldownEndTime = System.currentTimeMillis() + (seconds * 1000L);
        cooldownMap.put(key, cooldownEndTime);
    }

    /**
     * 清除冷卻時間
     *
     * @param key
     */
    public static void clearCooldown(String key) {
        cooldownMap.remove(key);
    }


    public static void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    /**
     * 定期清除過期的冷卻時間
     */
    public static void clearExpiredKeys() {
        cooldownTask = Bukkit.getScheduler().runTaskTimer(Main.getInst(), () -> {
            long currentTime = System.currentTimeMillis();
            cooldownMap.entrySet().removeIf(entry -> entry.getValue() < currentTime);
        }, 0L, 1200L);
    }

    /**
     * 停止清除過期的冷卻時間
     */
    public static void stopClearExpiredKeys() {
        if (cooldownTask != null) {
            cooldownTask.cancel();
            cooldownTask = null;
        }
    }
}
