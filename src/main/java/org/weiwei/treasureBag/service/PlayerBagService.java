package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.BagChanges;
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
import java.util.concurrent.CompletionException;

/**
 * 背包的開啟、翻頁、關閉流程（主執行緒）
 * <p>
 * 資料庫讀寫都交給 {@link BagStorage} 在 IO 執行緒跑：開背包時先排隊讀取，讀完回主執行緒建立 session；
 * 關背包時交出 snapshot，比對與寫入在背景完成。
 * <p>
 * 同一個背包在這台分流同時只會有一個 session（{@link #OWNERS}），
 * 管理員查看線上玩家、玩家自己開背包互相擋住，避免兩邊各存一次造成複製。
 */
public class PlayerBagService {

    public static final int PAGE_CONTENT_SIZE = 45;
    public static final int PREVIOUS_PAGE_SLOT = 48;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 50;

    /** 依現有資料推算格數（管理員查看不在這台的玩家） */
    private static final int INFER_MAX_SLOT = -1;

    /** 觀看者 → 開著的背包 */
    private static final Map<UUID, BagSession> SESSIONS = new HashMap<>();
    /** 背包擁有者 → 正在讀取或開著它的觀看者 */
    private static final Map<UUID, UUID> OWNERS = new HashMap<>();
    /** 正在讀取背包的觀看者（重複開啟直接忽略） */
    private static final Set<UUID> LOADING = new HashSet<>();
    private static final Set<UUID> PAGE_SWITCHING = new HashSet<>();

    private enum Mode {
        /** 一般開啟：有溢出物品就先警告 */
        NORMAL,
        /** 強制開啟：溢出物品掉落到地上 */
        FORCE,
        /** 管理員查看：不檢查溢出，只顯示可用範圍 */
        ADMIN
    }

    /**
     * 開啟背包
     */
    public static void open(Player player) {
        request(player, player.getUniqueId(), player.getName(), getMaxSlot(player), Mode.NORMAL, false);
    }

    /**
     * 強制開啟背包：將超出權限格數的物品掉落到地上，再開啟背包
     */
    public static void forceOpen(Player player) {
        request(player, player.getUniqueId(), player.getName(), getMaxSlot(player), Mode.FORCE, false);
    }

    /**
     * 以管理員身份查看指定玩家的背包
     * 對方在這台：可直接修改；不在這台（離線或在其他分流）：預設唯讀，edit 為 true 才可修改
     *
     * @param viewer     查看者
     * @param targetName 目標玩家名稱
     * @param edit       不在這台時是否允許修改
     */
    public static void openOther(Player viewer, String targetName, boolean edit) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (viewer.equals(target)) {
            open(viewer);
            return;
        }
        if (target != null) {
            request(viewer, target.getUniqueId(), target.getName(), getMaxSlot(target), Mode.ADMIN, false);
            return;
        }

        UUID viewerId = viewer.getUniqueId();
        BagStorage.findByName(targetName).whenComplete((playerInfo, error) -> BagStorage.runSync(() -> {
            Player viewerNow = Bukkit.getPlayer(viewerId);
            if (viewerNow == null) return;
            if (error != null) {
                BagStorage.logSevere("無法查詢玩家背包資料: " + targetName, error);
                Message.sendPrefix(viewerNow, Message.MESSAGE__ERROR, "無法開啟背包");
                return;
            }
            if (playerInfo == null) {
                Message.sendPrefix(viewerNow, Message.MESSAGE__NO_PLAYER);
                return;
            }

            // 查詢期間對方剛好進到這台
            Player targetNow = Bukkit.getPlayer(playerInfo.getPlayerUUID());
            if (targetNow != null) {
                request(viewerNow, targetNow.getUniqueId(), targetNow.getName(), getMaxSlot(targetNow), Mode.ADMIN, false);
                return;
            }

            // 對方可能在其他分流開著背包，這邊改了會互相覆蓋，預設只給看
            if (!edit) Message.sendPrefix(viewerNow, Message.MESSAGE__BAG_READ_ONLY, playerInfo.getPlayerName());
            request(viewerNow, playerInfo.getPlayerUUID(), playerInfo.getPlayerName(), INFER_MAX_SLOT, Mode.ADMIN, !edit);
        }));
    }

    /**
     * 開背包的共同入口：檢查鎖、排隊讀取，讀完回主執行緒處理
     */
    private static void request(Player viewer, UUID owner, String ownerName, int maxSlot, Mode mode, boolean readOnly) {
        UUID viewerId = viewer.getUniqueId();
        if (LOADING.contains(viewerId)) return;

        // 觀看者已經開著背包（例如管理員看完別人再開自己的）：先存檔結束，讀取會排在這次存檔之後
        finishSession(viewer);

        UUID holder = OWNERS.get(owner);
        if (holder != null && !holder.equals(viewerId)) {
            Message.sendPrefix(viewer, viewerId.equals(owner)
                    ? Message.MESSAGE__BAG_OWNER_IN_USE
                    : Message.MESSAGE__BAG_TARGET_IN_USE);
            return;
        }
        if (BagStorage.hasUnsaved(owner)) {
            Message.sendPrefix(viewer, Message.MESSAGE__BAG_SAVE_PENDING);
            return;
        }

        LOADING.add(viewerId);
        OWNERS.put(owner, viewerId);
        BagStorage.load(owner, ownerName).whenComplete((bag, error) -> BagStorage.runSync(() -> {
            LOADING.remove(viewerId);
            Player viewerNow = Bukkit.getPlayer(viewerId);
            if (error != null || viewerNow == null) {
                OWNERS.remove(owner, viewerId);
                if (viewerNow != null) sendLoadError(viewerNow, ownerName, error);
                return;
            }
            onLoaded(viewerNow, bag, maxSlot, mode, readOnly);
        }));
    }

    private static void onLoaded(Player viewer, BagStorage.LoadedBag bag, int requestedMaxSlot, Mode mode, boolean readOnly) {
        UUID viewerId = viewer.getUniqueId();
        UUID owner = bag.playerInfo().getPlayerUUID();
        int maxSlot = requestedMaxSlot == INFER_MAX_SLOT ? inferMaxSlot(bag) : requestedMaxSlot;

        Map<Integer, ItemStack> overflow = new HashMap<>();
        bag.items().forEach((slot, item) -> {
            if (slot >= maxSlot) overflow.put(slot, item);
        });

        if (overflow.isEmpty() || mode == Mode.ADMIN) {
            startSession(viewer, bag, maxSlot, readOnly);
            return;
        }

        if (mode == Mode.NORMAL) {
            OWNERS.remove(owner, viewerId);
            sendOverflowWarning(viewer, overflow.size());
            return;
        }

        // 強制開啟：先刪資料庫再掉落；刪除失敗就不掉，避免下次再掉一次（複製）
        BagStorage.deleteSlots(owner, overflow.keySet()).whenComplete((ignored, error) -> BagStorage.runSync(() -> {
            Player viewerNow = Bukkit.getPlayer(viewerId);
            if (error != null) {
                OWNERS.remove(owner, viewerId);
                BagStorage.logSevere("無法強制開啟玩家背包: " + bag.playerInfo().getPlayerName(), error);
                if (viewerNow != null) Message.sendPrefix(viewerNow, Message.MESSAGE__ERROR, "無法強制開啟背包");
                return;
            }
            if (viewerNow == null) {
                // 刪完人已經下線：物品寫回原格位，下次開背包會再提示
                restoreRows(bag, overflow.keySet());
                OWNERS.remove(owner, viewerId);
                return;
            }

            overflow.values().forEach(item -> viewerNow.getWorld().dropItemNaturally(viewerNow.getLocation(), item.clone()));
            overflow.keySet().forEach(slot -> {
                bag.items().remove(slot);
                bag.baseline().remove(slot);
                bag.baselineItems().remove(slot);
            });
            Message.sendPrefix(viewerNow, Message.MESSAGE__BAG_FORCE_DROPPED, overflow.size());
            startSession(viewerNow, bag, maxSlot, false);
        }));
    }

    private static void startSession(Player viewer, BagStorage.LoadedBag bag, int maxSlot, boolean readOnly) {
        Map<Integer, ItemStack> items = new HashMap<>();
        bag.items().forEach((slot, item) -> {
            if (slot < maxSlot) items.put(slot, item);
        });
        Set<Integer> broken = new HashSet<>();
        bag.brokenRows().forEach(slot -> {
            if (slot < maxSlot) broken.add(slot);
        });

        SESSIONS.put(viewer.getUniqueId(), new BagSession(bag.playerInfo(), maxSlot, items, bag.baseline(),
                bag.baselineItems(), broken, readOnly));
        openPage(viewer, 0);
    }

    private static void restoreRows(BagStorage.LoadedBag bag, Set<Integer> slots) {
        BagChanges changes = new BagChanges(bag.playerInfo().getPlayerUUID(), bag.playerInfo().getPlayerName());
        for (int slot : slots) {
            String value = bag.baseline().get(slot);
            if (value == null) continue;
            changes.put(new BagChanges.SlotChange(slot, null, value, ItemManage.nameOf(bag.items().get(slot))));
        }
        BagStorage.restore(changes);
    }

    private static void sendLoadError(Player viewer, String ownerName, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof BagStorage.SavePendingException) {
            Message.sendPrefix(viewer, Message.MESSAGE__BAG_SAVE_PENDING);
            return;
        }
        BagStorage.logSevere("無法開啟玩家背包: " + ownerName, cause);
        Message.sendPrefix(viewer, Message.MESSAGE__ERROR, "無法開啟背包");
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
        try {
            openPage(player, page);
        } catch (Exception e) {
            // openPage 若拋出例外，確保 PAGE_SWITCHING 被清除；
            // 否則後續任何關閉背包的操作都會被誤判為翻頁，永遠不存檔
            PAGE_SWITCHING.remove(player.getUniqueId());
            throw e;
        }
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
     * 判斷該內容格位是否不可操作：超出可用格數，或是讀不出來的格位
     *
     * @param player  觀看者
     * @param gui     當前 GUI
     * @param rawSlot 點擊的原始格位
     * @return true 表示已鎖定，不可操作
     */
    public static boolean isLockedContentSlot(Player player, CustomGui gui, int rawSlot) {
        if (rawSlot < 0 || rawSlot >= PAGE_CONTENT_SIZE) return false;
        int globalSlot = gui.getPage() * PAGE_CONTENT_SIZE + rawSlot;
        if (globalSlot >= gui.getMaxSlot()) return true;

        BagSession session = getSession(player);
        return session != null && session.brokenSlots().contains(globalSlot);
    }

    /**
     * 是否為唯讀查看（管理員看不在這台的玩家）
     */
    public static boolean isReadOnly(Player player) {
        BagSession session = getSession(player);
        return session != null && session.readOnly();
    }

    public static boolean isPortableContainer(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        String typeName = item.getType().name();
        return typeName.equals("SHULKER_BOX")
                || typeName.endsWith("_SHULKER_BOX")
                || typeName.equals("BUNDLE")
                || typeName.endsWith("_BUNDLE");
    }

    public static int moveItemToBag(Player player, CustomGui gui, ItemStack source) {
        BagSession session = getSession(player);
        if (session == null || session.readOnly() || gui == null || source == null || source.getType().isAir()) return 0;

        savePage(session, gui);

        int originalAmount = source.getAmount();
        ItemStack moving = source.clone();

        for (int slot = 0; slot < session.maxSlot() && moving.getAmount() > 0; slot++) {
            ItemStack existing = session.items().get(slot);
            if (existing == null || existing.getType().isAir() || !existing.isSimilar(moving)) continue;

            int maxStackSize = existing.getMaxStackSize();
            int moveAmount = Math.min(maxStackSize - existing.getAmount(), moving.getAmount());
            if (moveAmount <= 0) continue;

            existing.setAmount(existing.getAmount() + moveAmount);
            moving.setAmount(moving.getAmount() - moveAmount);
        }

        for (int slot = 0; slot < session.maxSlot() && moving.getAmount() > 0; slot++) {
            if (session.brokenSlots().contains(slot)) continue;
            ItemStack existing = session.items().get(slot);
            if (existing != null && !existing.getType().isAir()) continue;

            int moveAmount = Math.min(moving.getMaxStackSize(), moving.getAmount());
            ItemStack placed = moving.clone();
            placed.setAmount(moveAmount);
            session.items().put(slot, placed);
            moving.setAmount(moving.getAmount() - moveAmount);
        }

        fillContent(gui.getInventory(), session);
        return originalAmount - moving.getAmount();
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
        savePage(session, gui);
    }

    private static void savePage(BagSession session, CustomGui gui) {
        Inventory inventory = gui.getInventory();
        int pageOffset = gui.getPage() * PAGE_CONTENT_SIZE;
        for (int slot = 0; slot < PAGE_CONTENT_SIZE; slot++) {
            int globalSlot = pageOffset + slot;
            if (globalSlot >= session.maxSlot()) break;
            if (session.brokenSlots().contains(globalSlot)) continue;

            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                session.items().remove(globalSlot);
                continue;
            }
            session.items().put(globalSlot, item.clone());
        }
    }

    /**
     * 關閉背包：儲存本頁後交給 BagStorage 在背景寫回資料庫
     * 若為翻頁觸發的關閉則只暫存不寫 DB
     *
     * @param player 玩家
     * @param gui    當前 GUI
     */
    public static void close(Player player, CustomGui gui) {
        BagSession session = getSession(player);
        if (session == null) return;

        savePage(session, gui);
        if (PAGE_SWITCHING.remove(player.getUniqueId())) return;

        endSession(player.getUniqueId(), session);
    }

    /**
     * 結束觀看者目前的 session（存檔並關掉畫面）；沒有開著就不做事
     */
    private static void finishSession(Player viewer) {
        BagSession session = getSession(viewer);
        if (session == null) return;

        CustomGui gui = CustomGui.isCustomGui(viewer.getOpenInventory().getTopInventory(), GuiType.BAG_MENU);
        if (gui != null) savePage(session, gui);
        PAGE_SWITCHING.remove(viewer.getUniqueId());
        endSession(viewer.getUniqueId(), session);
        // session 已移除，觸發的 InventoryCloseEvent 會直接 return，不會再存一次
        viewer.closeInventory();
    }

    private static void endSession(UUID viewerId, BagSession session) {
        SESSIONS.remove(viewerId);
        OWNERS.remove(session.ownerUuid(), viewerId);
        if (!session.readOnly()) BagStorage.save(session.snapshot(), viewerId);
    }

    /**
     * 外掛停用：取出所有開著的背包，關掉玩家的畫面，交給 BagStorage 等排隊的存檔做完後直接寫入
     * <p>
     * 伺服器關閉時外掛比玩家先停用，之後玩家畫面關閉的事件不會再進來，
     * 不在這裡存掉的話，放進去的會消失、拿出來的會複製
     */
    public static void shutdown() {
        List<BagSession.Snapshot> snapshots = new ArrayList<>();
        List<Player> viewers = new ArrayList<>();
        for (Map.Entry<UUID, BagSession> entry : new ArrayList<>(SESSIONS.entrySet())) {
            BagSession session = entry.getValue();
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                CustomGui gui = CustomGui.isCustomGui(viewer.getOpenInventory().getTopInventory(), GuiType.BAG_MENU);
                if (gui != null) savePage(session, gui);
                viewers.add(viewer);
            }
            if (!session.readOnly()) snapshots.add(session.snapshot());
        }
        SESSIONS.clear();
        OWNERS.clear();
        LOADING.clear();
        PAGE_SWITCHING.clear();
        viewers.forEach(Player::closeInventory);
        BagStorage.shutdown(snapshots);
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
     * 判斷指定 UUID 的玩家是否正在使用背包（有活躍的 BagSession）
     *
     * @param uuid 玩家 UUID
     * @return true 表示玩家目前正在使用背包
     */
    public static boolean hasSession(UUID uuid) {
        return SESSIONS.containsKey(uuid);
    }

    /**
     * 將 Session 中的物品填入 GUI 的內容區（前 45 格）
     * 超出可用格數的格位填入預設佔位物品，讀不出來的格位填入損壞提示
     */
    private static void fillContent(Inventory inventory, BagSession session) {
        int pageOffset = session.page() * PAGE_CONTENT_SIZE;
        for (int slot = 0; slot < PAGE_CONTENT_SIZE; slot++) {
            int globalSlot = pageOffset + slot;
            if (globalSlot >= session.maxSlot()) {
                inventory.setItem(slot, ItemManage.getDefaultItem());
                continue;
            }
            if (session.brokenSlots().contains(globalSlot)) {
                inventory.setItem(slot, ItemManage.getBrokenItem());
                continue;
            }
            ItemStack item = session.items().get(globalSlot);
            inventory.setItem(slot, item == null ? null : item.clone());
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
     * 依現有背包資料推算 maxSlot（對齊至 PAGE_CONTENT_SIZE 的倍數）
     * 用於管理員查看不在這台的玩家，避免存入超出玩家實際權限的格位
     *
     * @param bag 讀進來的背包
     * @return 推算出的 maxSlot，最低為 PAGE_CONTENT_SIZE
     */
    private static int inferMaxSlot(BagStorage.LoadedBag bag) {
        int maxSolder = -1;
        for (int slot : bag.items().keySet()) maxSolder = Math.max(maxSolder, slot);
        for (int slot : bag.brokenRows()) maxSolder = Math.max(maxSolder, slot);
        if (maxSolder < 0) return PAGE_CONTENT_SIZE;
        return ((maxSolder / PAGE_CONTENT_SIZE) + 1) * PAGE_CONTENT_SIZE;
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
     * 取得設定檔中所有組別裡最大的格數
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
}
