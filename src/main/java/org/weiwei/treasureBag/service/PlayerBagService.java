package org.weiwei.treasureBag.service;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.PlayerBagRepository;
import org.weiwei.treasureBag.entity.PlayerBag;
import org.weiwei.treasureBag.entity.PlayerInfo;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.ItemManage;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerBagService {

    public static final int PAGE_CONTENT_SIZE = 45;
    public static final int PREVIOUS_PAGE_SLOT = 48;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 50;

    private static final PlayerBagRepository REPOSITORY = new PlayerBagRepository();
    private static final Map<UUID, BagSession> SESSIONS = new HashMap<>();
    private static final Set<UUID> PAGE_SWITCHING = new HashSet<>();

    /**
     * 開啟背包
     */
    public static void open(Player player) {
        try {
            int maxSlot = getMaxSlot(player);
            PlayerInfo playerInfo = REPOSITORY.getOrCreatePlayerInfo(player);
            List<PlayerBag> playerBags = REPOSITORY.loadPlayerBags(playerInfo);
            if (hasOverflow(playerBags, maxSlot)) {
                sendOverflowWarning(player, countOverflow(playerBags, maxSlot));
                return;
            }
            open(player, playerInfo, maxSlot, playerBags);
        } catch (Exception e) {
            Main.getInst().getLogger().severe("無法開啟玩家背包: " + player.getName());
            e.printStackTrace();
            Message.sendPrefix(player, Message.MESSAGE__ERROR, "無法開啟背包");
        }
    }

    /**
     * 強制開啟背包：將超出權限格數的物品掉落到地上，再開啟背包
     */
    public static void forceOpen(Player player) {
        try {
            int maxSlot = getMaxSlot(player);
            PlayerInfo playerInfo = REPOSITORY.getOrCreatePlayerInfo(player);
            List<PlayerBag> playerBags = REPOSITORY.loadPlayerBags(playerInfo);
            List<PlayerBag> allowedBags = new ArrayList<>();
            int dropCount = 0;
            int overflowSlotCount = 0;

            for (PlayerBag playerBag : playerBags) {
                if (playerBag == null || playerBag.getSolder() == null) continue;

                if (playerBag.getSolder() >= maxSlot) {
                    overflowSlotCount++;
                    ItemStack item = playerBag.getItem();
                    if (item != null && !item.getType().isAir()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), item.clone());
                        dropCount++;
                    }
                    continue;
                }

                ItemStack item = playerBag.getItem();
                if (item == null || item.getType().isAir()) continue;
                allowedBags.add(playerBag);
            }

            if (overflowSlotCount > 0) {
                REPOSITORY.deletePlayerBagsFromSlot(playerInfo, maxSlot);
                Message.sendPrefix(player, Message.MESSAGE__BAG_FORCE_DROPPED, dropCount);
            }
            open(player, playerInfo, maxSlot, allowedBags);
        } catch (Exception e) {
            Main.getInst().getLogger().severe("無法強制開啟玩家背包: " + player.getName());
            e.printStackTrace();
            Message.sendPrefix(player, Message.MESSAGE__ERROR, "無法強制開啟背包");
        }
    }

    /**
     * 以管理員身份查看指定玩家的背包（支援線上 / 離線玩家）
     *
     * @param viewer     查看者
     * @param targetName 目標玩家名稱
     */
    public static void openOther(Player viewer, String targetName) {
        try {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null) {
                PlayerInfo playerInfo = REPOSITORY.getOrCreatePlayerInfo(target);
                open(viewer, playerInfo, getMaxSlot(target), REPOSITORY.loadPlayerBags(playerInfo));
                return;
            }

            PlayerInfo playerInfo = REPOSITORY.findPlayerInfoByName(targetName);
            if (playerInfo == null) {
                Message.sendPrefix(viewer, Message.MESSAGE__NO_PLAYER);
                return;
            }

            open(viewer, playerInfo, getMaxSlotFromConfig(), REPOSITORY.loadPlayerBags(playerInfo));
        } catch (Exception e) {
            Main.getInst().getLogger().severe("無法開啟玩家背包: " + targetName);
            e.printStackTrace();
            Message.sendPrefix(viewer, Message.MESSAGE__ERROR, "無法開啟背包");
        }
    }

    /**
     * 內部通用開啟邏輯：建立 Session 並顯示第 0 頁
     *
     * @param viewer     查看者（可能不是背包擁有者）
     * @param playerInfo 背包擁有者資訊
     * @param maxSlot    可用格數上限
     * @param playerBags 從資料庫讀取的背包物品
     */
    private static void open(Player viewer, PlayerInfo playerInfo, int maxSlot, List<PlayerBag> playerBags) {
        if (playerInfo == null) {
            Message.sendPrefix(viewer, Message.MESSAGE__NO_PLAYER);
            return;
        }

        Map<Integer, ItemStack> items = toItemMap(playerBags);
        SESSIONS.put(viewer.getUniqueId(), new BagSession(playerInfo, maxSlot, items));
        openPage(viewer, 0);
    }

    /**
     * 開啟指定頁碼的背包 GUI
     *
     * @param player 玩家
     * @param page   頁碼（0-based）
     */
    public static void openPage(Player player, int page) {
        BagSession session = getSession(player);
        if (session == null) return;

        int maxPage = getMaxPage(session.maxSlot());
        int fixedPage = Math.max(0, Math.min(page, maxPage - 1));
        session.setPage(fixedPage);

        String title = Message.getMsg(Message.MENU_TITLE__BAG_MENU, fixedPage + 1, maxPage);
        CustomGui gui = new CustomGui(GuiType.BAG_MENU, player, title);
        gui.setPage(fixedPage);
        gui.setMaxSlot(session.maxSlot());

        Inventory inventory = gui.getInventory();
        fillContent(inventory, session);
        fillControlRow(inventory, fixedPage, maxPage, session.maxSlot());
        player.openInventory(inventory);
    }

    /**
     * 儲存當前頁內容後切換到指定頁碼
     *
     * @param player 玩家
     * @param gui    當前 GUI
     * @param page   目標頁碼（0-based）
     */
    public static void switchPage(Player player, CustomGui gui, int page) {
        savePage(player, gui);
        PAGE_SWITCHING.add(player.getUniqueId());
        openPage(player, page);
    }

    /**
     * 判斷該 rawSlot 是否為底部控制列（上一頁 / 資訊 / 下一頁）
     *
     * @param rawSlot 點擊的原始格位
     * @return true 表示屬於控制列
     */
    public static boolean isControlSlot(int rawSlot) {
        return rawSlot >= PAGE_CONTENT_SIZE;
    }

    /**
     * 判斷該內容格位是否已超出玩家可用格數（鎖定狀態）
     *
     * @param gui     當前 GUI
     * @param rawSlot 點擊的原始格位
     * @return true 表示已鎖定，不可操作
     */
    public static boolean isLockedContentSlot(CustomGui gui, int rawSlot) {
        if (rawSlot < 0 || rawSlot >= PAGE_CONTENT_SIZE) return false;
        int globalSlot = gui.getPage() * PAGE_CONTENT_SIZE + rawSlot;
        return globalSlot >= gui.getMaxSlot();
    }

    /**
     * 將當前頁面的物品暫存回 Session（關閉或翻頁前呼叫）
     *
     * @param player 玩家
     * @param gui    當前 GUI
     */
    public static void savePage(Player player, CustomGui gui) {
        BagSession session = getSession(player);
        if (session == null || gui == null) return;

        Inventory inventory = gui.getInventory();
        int pageOffset = gui.getPage() * PAGE_CONTENT_SIZE;
        for (int slot = 0; slot < PAGE_CONTENT_SIZE; slot++) {
            int globalSlot = pageOffset + slot;
            if (globalSlot >= session.maxSlot()) break;

            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                session.items().remove(globalSlot);
                continue;
            }
            session.items().put(globalSlot, item.clone());
        }
    }

    /**
     * 關閉背包：儲存本頁後將 Session 資料寫回資料庫
     * 若為翻頁觸發的關閉則只儲存不寫 DB
     *
     * @param player 玩家
     * @param gui    當前 GUI
     */
    public static void close(Player player, CustomGui gui) {
        savePage(player, gui);
        if (PAGE_SWITCHING.remove(player.getUniqueId())) return;

        BagSession session = SESSIONS.remove(player.getUniqueId());
        if (session == null) return;
        try {
            REPOSITORY.saveAllowedPlayerBags(
                    session.playerInfo(),
                    toPlayerBags(session.playerInfo(), session.items(), session.maxSlot()),
                    session.maxSlot()
            );
        } catch (Exception e) {
            Main.getInst().getLogger().severe("無法保存玩家背包: " + player.getName());
            e.printStackTrace();
            Message.sendPrefix(player, Message.MESSAGE__ERROR, "無法保存背包");
        }
    }

    /**
     * 取得玩家的最大頁數
     *
     * @param player 玩家
     * @return 最大頁數（至少 1）
     */
    public static int getMaxPage(Player player) {
        return getMaxPage(getMaxSlot(player));
    }

    /**
     * 取得玩家的 Session；若不存在則回傳 null
     */
    private static BagSession getSession(Player player) {
        return SESSIONS.get(player.getUniqueId());
    }

    /**
     * 將 Session 中的物品填入 GUI 的內容區（前 45 格）
     * 超出可用格數的格位填入預設佔位物品
     */
    private static void fillContent(Inventory inventory, BagSession session) {
        int pageOffset = session.page() * PAGE_CONTENT_SIZE;
        for (int slot = 0; slot < PAGE_CONTENT_SIZE; slot++) {
            int globalSlot = pageOffset + slot;
            if (globalSlot >= session.maxSlot()) {
                inventory.setItem(slot, ItemManage.getDefaultItem());
                continue;
            }
            ItemStack item = session.items().get(globalSlot);
            if (item != null) inventory.setItem(slot, item.clone());
        }
    }

    /**
     * 填入底部控制列：背景、上一頁箭頭、資訊書、下一頁箭頭
     *
     * @param inventory 目標物品欄
     * @param page      當前頁碼（0-based）
     * @param maxPage   最大頁數
     * @param maxSlot   可用格數上限
     */
    private static void fillControlRow(Inventory inventory, int page, int maxPage, int maxSlot) {
        for (int slot = PAGE_CONTENT_SIZE; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, ItemManage.getDefaultItem());
        }

        inventory.setItem(PREVIOUS_PAGE_SLOT, createControlItem(Material.ARROW, "§e上一頁"));
        inventory.setItem(INFO_SLOT, createInfoItem(page, maxPage, maxSlot));
        inventory.setItem(NEXT_PAGE_SLOT, createControlItem(Material.ARROW, "§e下一頁"));
    }

    /**
     * 建立底部資訊格（Book）：顯示頁碼與可用格數
     */
    private static ItemStack createInfoItem(int page, int maxPage, int maxSlot) {
        List<String> lore = List.of(
                "§7目前頁數: §f" + (page + 1) + "§7/§f" + maxPage,
                "§7可使用格數: §f" + maxSlot
        );
        return ItemManage.createItem(Material.BOOK, "§b背包資訊", lore, 0);
    }

    /**
     * 建立控制按鈕（箭頭）並設定顯示名稱
     */
    private static ItemStack createControlItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 依 maxSlot 計算最大頁數（至少 1 頁）
     *
     * @param maxSlot 可用格數上限
     * @return 最大頁數
     */
    private static int getMaxPage(int maxSlot) {
        return Math.max(1, (int) Math.ceil(maxSlot / (double) PAGE_CONTENT_SIZE));
    }

    /**
     * 判斷背包中是否有物品的格位超出可用上限（溢出）
     *
     * @param playerBags 背包物品列表
     * @param maxSlot    可用格數上限
     * @return true 表示存在溢出物品
     */
    private static boolean hasOverflow(List<PlayerBag> playerBags, int maxSlot) {
        return playerBags.stream()
                .anyMatch(playerBag -> playerBag != null
                        && playerBag.getSolder() != null
                        && playerBag.getSolder() >= maxSlot);
    }

    /**
     * 計算溢出物品的數量
     *
     * @param playerBags 背包物品列表
     * @param maxSlot    可用格數上限
     * @return 溢出物品筆數
     */
    private static int countOverflow(List<PlayerBag> playerBags, int maxSlot) {
        return (int) playerBags.stream()
                .filter(playerBag -> playerBag != null
                        && playerBag.getSolder() != null
                        && playerBag.getSolder() >= maxSlot)
                .count();
    }

    /**
     * 傳送溢出警告訊息，並提供可點擊的強制開啟指令連結
     *
     * @param player        玩家
     * @param overflowCount 溢出物品數量
     */
    private static void sendOverflowWarning(Player player, int overflowCount) {
        Message.sendPrefix(player, Message.MESSAGE__BAG_SPACE_SHRINK, overflowCount);

        TextComponent forceMessage = Component.text(Message.getMsg(Message.MESSAGE__PREFIX))
                .append(Component.text(Message.getMsg(Message.MESSAGE__BAG_FORCE_CLICK))
                        .clickEvent(ClickEvent.runCommand("/treasurebag force"))
                        .hoverEvent(HoverEvent.showText(Component.text(Message.getMsg(Message.MESSAGE__BAG_FORCE_HOVER)))));
        player.sendMessage(forceMessage);
    }

    /**
     * 將 PlayerBag 列表轉為 {@code Map<格位, ItemStack>}，過濾空值
     */
    private static Map<Integer, ItemStack> toItemMap(List<PlayerBag> playerBags) {
        Map<Integer, ItemStack> items = new HashMap<>();
        for (PlayerBag playerBag : playerBags) {
            if (playerBag == null || playerBag.getSolder() == null) continue;

            ItemStack item = playerBag.getItem();
            if (item == null || item.getType().isAir()) continue;
            items.put(playerBag.getSolder().intValue(), item);
        }
        return items;
    }

    /**
     * 將 Session 的物品 Map 轉回 PlayerBag 列表（只保留 0 ~ maxSlot-1 範圍內的有效物品）
     *
     * @param playerInfo 背包擁有者
     * @param items      格位 → 物品的 Map
     * @param maxSlot    可用格數上限
     * @return 可存入資料庫的 PlayerBag 列表
     */
    private static List<PlayerBag> toPlayerBags(PlayerInfo playerInfo, Map<Integer, ItemStack> items, int maxSlot) {
        List<PlayerBag> playerBags = new ArrayList<>();
        if (playerInfo == null || playerInfo.getPlayerUUID() == null) return playerBags;

        for (int slot = 0; slot < maxSlot; slot++) {
            ItemStack item = items.get(slot);
            if (item == null || item.getType().isAir()) continue;

            PlayerBag playerBag = new PlayerBag();
            playerBag.setPlayerUUID(playerInfo.getPlayerUUID());
            playerBag.setSolder((long) slot);
            playerBag.setItem(item);
            playerBags.add(playerBag);
        }
        return playerBags;
    }

    /**
     * 依玩家所擁有的權限節點取得最大可用格數
     * 設定路徑：{@code MAX_SLOT.<key>}，對應權限：{@code treasure-bag.<key>}
     *
     * @param player 玩家
     * @return 玩家有權使用的最大格數；若無任何對應權限則回傳 0
     */
    public static int getMaxSlot(Player player) {
        if (ConfigManager.getConfig() == null || !ConfigManager.getConfig().isConfigurationSection("MAX_SLOT")) {
            return 0;
        }

        List<String> keys = new ArrayList<>(ConfigManager.getConfig().getConfigurationSection("MAX_SLOT").getKeys(false));
        return keys.stream()
                .filter(key -> player.hasPermission("treasure-bag." + key))
                .map(key -> ConfigManager.getConfig().getInt("MAX_SLOT." + key, 0))
                .max(Comparator.naturalOrder())
                .orElse(0);
    }

    /**
     * 取得設定檔中所有組別裡最大的格數（供離線玩家使用）
     *
     * @return 設定檔中最大格數；若無設定則回傳 0
     */
    public static int getMaxSlotFromConfig() {
        if (ConfigManager.getConfig() == null || !ConfigManager.getConfig().isConfigurationSection("MAX_SLOT")) {
            return 0;
        }
        return ConfigManager.getConfig().getConfigurationSection("MAX_SLOT").getKeys(false)
                .stream()
                .mapToInt(key -> ConfigManager.getConfig().getInt("MAX_SLOT." + key, 0))
                .max()
                .orElse(0);
    }

    private static class BagSession {
        private final PlayerInfo playerInfo;
        private final int maxSlot;
        private final Map<Integer, ItemStack> items;
        private int page;

        private BagSession(PlayerInfo playerInfo, int maxSlot, Map<Integer, ItemStack> items) {
            this.playerInfo = playerInfo;
            this.maxSlot = maxSlot;
            this.items = items;
        }

        private PlayerInfo playerInfo() {
            return playerInfo;
        }

        private int maxSlot() {
            return maxSlot;
        }

        private Map<Integer, ItemStack> items() {
            return items;
        }

        private int page() {
            return page;
        }

        private void setPage(int page) {
            this.page = page;
        }
    }
}
