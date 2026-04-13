package org.weiwei.treasureBag.service;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

    public static void open(Player player) {
        try {
            int maxSlot = getMaxSlot(player);
            PlayerInfo playerInfo = REPOSITORY.getOrCreatePlayerInfo(player);
            open(player, playerInfo, maxSlot);
        } catch (Exception e) {
            Main.getInst().getLogger().severe("無法開啟玩家背包: " + player.getName());
            e.printStackTrace();
            Message.sendPrefix(player, Message.MESSAGE__ERROR, "無法開啟背包");
        }
    }

    public static void openOther(Player viewer, String targetName) {
        try {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null) {
                open(viewer, REPOSITORY.getOrCreatePlayerInfo(target), getMaxSlot(target));
                return;
            }

            PlayerInfo playerInfo = REPOSITORY.findPlayerInfoByName(targetName);
            if (playerInfo == null) {
                Message.sendPrefix(viewer, Message.MESSAGE__NO_PLAYER);
                return;
            }

            open(viewer, playerInfo, getMaxSlotFromConfig());
        } catch (Exception e) {
            Main.getInst().getLogger().severe("無法開啟玩家背包: " + targetName);
            e.printStackTrace();
            Message.sendPrefix(viewer, Message.MESSAGE__ERROR, "無法開啟背包");
        }
    }

    private static void open(Player viewer, PlayerInfo playerInfo, int maxSlot) {
        if (playerInfo == null) {
            Message.sendPrefix(viewer, Message.MESSAGE__NO_PLAYER);
            return;
        }

        Map<Integer, ItemStack> items = toItemMap(REPOSITORY.loadPlayerBags(playerInfo));
        SESSIONS.put(viewer.getUniqueId(), new BagSession(playerInfo, maxSlot, items));
        openPage(viewer, 0);
    }

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

    public static void switchPage(Player player, CustomGui gui, int page) {
        savePage(player, gui);
        PAGE_SWITCHING.add(player.getUniqueId());
        openPage(player, page);
    }

    public static boolean isControlSlot(int rawSlot) {
        return rawSlot >= PAGE_CONTENT_SIZE;
    }

    public static boolean isLockedContentSlot(CustomGui gui, int rawSlot) {
        if (rawSlot < 0 || rawSlot >= PAGE_CONTENT_SIZE) return false;
        int globalSlot = gui.getPage() * PAGE_CONTENT_SIZE + rawSlot;
        return globalSlot >= gui.getMaxSlot();
    }

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

    public static int getMaxPage(Player player) {
        return getMaxPage(getMaxSlot(player));
    }

    private static BagSession getSession(Player player) {
        return SESSIONS.get(player.getUniqueId());
    }

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

    private static void fillControlRow(Inventory inventory, int page, int maxPage, int maxSlot) {
        for (int slot = PAGE_CONTENT_SIZE; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, ItemManage.getDefaultItem());
        }

        inventory.setItem(PREVIOUS_PAGE_SLOT, createControlItem(Material.ARROW, "§e上一頁"));
        inventory.setItem(INFO_SLOT, createInfoItem(page, maxPage, maxSlot));
        inventory.setItem(NEXT_PAGE_SLOT, createControlItem(Material.ARROW, "§e下一頁"));
    }

    private static ItemStack createInfoItem(int page, int maxPage, int maxSlot) {
        List<String> lore = List.of(
                "§7目前頁數: §f" + (page + 1) + "§7/§f" + maxPage,
                "§7可使用格數: §f" + maxSlot
        );
        return ItemManage.createItem(Material.BOOK, "§b背包資訊", lore, 0);
    }

    private static ItemStack createControlItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static int getMaxPage(int maxSlot) {
        return Math.max(1, (int) Math.ceil(maxSlot / (double) PAGE_CONTENT_SIZE));
    }

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

    // 獲取最高可用背包空間（依權限）
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

    // 取得設定檔中所有組別的最大格數（離線玩家使用）
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
