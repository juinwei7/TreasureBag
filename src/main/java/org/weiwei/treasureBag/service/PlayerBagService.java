package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.BagChanges;
import org.weiwei.treasureBag.data.BagLog;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.ItemManage;
import org.weiwei.treasureBag.util.Message;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * 背包的開啟、翻頁、關閉流程與介面上的功能（主執行緒）
 * <p>
 * 資料庫讀寫都交給 {@link BagStorage} 在 IO 執行緒跑：開背包時先排隊讀取，讀完回主執行緒建立 session；
 * 關背包時交出 snapshot，比對與寫入在背景完成。
 * <p>
 * 同一個背包在這台分流同時只會有一個 session（{@link #OWNERS}），
 * 管理員查看線上玩家、玩家自己開背包互相擋住，避免兩邊各存一次造成複製。
 */
public class PlayerBagService {

    public static final int PAGE_CONTENT_SIZE = BagMenu.PAGE_CONTENT_SIZE;

    /** 依現有資料推算格數（管理員查看不在這台的玩家） */
    private static final int INFER_MAX_SLOT = -1;
    /** 整理、一鍵存入的冷卻（毫秒） */
    private static final long ACTION_COOLDOWN_MS = 1000;
    /** 頁面名稱最多幾個字 */
    public static final int PAGE_NAME_MAX_LENGTH = 8;
    private static final int LOG_PAGE_SIZE = 10;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    /** 觀看者 → 開著的背包 */
    private static final Map<UUID, BagSession> SESSIONS = new HashMap<>();
    /** 背包擁有者 → 正在讀取或開著它的觀看者 */
    private static final Map<UUID, UUID> OWNERS = new HashMap<>();
    /** 正在讀取背包的觀看者（重複開啟直接忽略） */
    private static final Set<UUID> LOADING = new HashSet<>();
    private static final Set<UUID> PAGE_SWITCHING = new HashSet<>();
    /** 觀看者上次整理／一鍵存入的時間 */
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();

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
        open(player, 0);
    }

    /**
     * 開啟背包並跳到指定頁（0 起算；礦物倉庫畫面點頁籤用）
     */
    public static void openAt(Player player, int startPage) {
        open(player, startPage);
    }

    private static void open(Player player, int startPage) {
        request(player, player.getUniqueId(), player.getName(), getMaxSlot(player), Mode.NORMAL, false, startPage);
    }

    /**
     * 強制開啟背包：將超出權限格數的物品掉落到地上，再開啟背包
     */
    public static void forceOpen(Player player) {
        request(player, player.getUniqueId(), player.getName(), getMaxSlot(player), Mode.FORCE, false, 0);
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
            request(viewer, target.getUniqueId(), target.getName(), getMaxSlot(target), Mode.ADMIN, false, 0);
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
                request(viewerNow, targetNow.getUniqueId(), targetNow.getName(), getMaxSlot(targetNow), Mode.ADMIN, false, 0);
                return;
            }

            // 對方可能在其他分流開著背包，這邊改了會互相覆蓋，預設只給看
            if (!edit) Message.sendPrefix(viewerNow, Message.MESSAGE__BAG_READ_ONLY, playerInfo.getPlayerName());
            request(viewerNow, playerInfo.getPlayerUUID(), playerInfo.getPlayerName(), INFER_MAX_SLOT, Mode.ADMIN, !edit, 0);
        }));
    }

    /**
     * 開背包的共同入口：檢查鎖、排隊讀取，讀完回主執行緒處理
     */
    private static void request(Player viewer, UUID owner, String ownerName, int maxSlot, Mode mode,
                                boolean readOnly, int startPage) {
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
            onLoaded(viewerNow, bag, maxSlot, mode, readOnly, startPage);
        }));
    }

    private static void onLoaded(Player viewer, BagStorage.LoadedBag bag, int requestedMaxSlot, Mode mode,
                                 boolean readOnly, int startPage) {
        UUID viewerId = viewer.getUniqueId();
        UUID owner = bag.playerInfo().getPlayerUUID();
        int maxSlot = requestedMaxSlot == INFER_MAX_SLOT ? inferMaxSlot(bag) : requestedMaxSlot;

        Map<Integer, ItemStack> overflow = new HashMap<>();
        bag.items().forEach((slot, item) -> {
            if (slot >= maxSlot) overflow.put(slot, item);
        });

        if (overflow.isEmpty() || mode == Mode.ADMIN) {
            startSession(viewer, bag, maxSlot, readOnly, startPage);
            return;
        }

        if (mode == Mode.NORMAL) {
            OWNERS.remove(owner, viewerId);
            sendOverflowWarning(viewer, overflow.size());
            return;
        }

        // 強制開啟：先刪資料庫再掉落；刪除失敗就不掉，避免下次再掉一次（複製）。刪除與存取紀錄同一筆交易
        BagChanges drop = new BagChanges(owner, bag.playerInfo().getPlayerName())
                .by(viewerId, viewer.getName(), BagChanges.SOURCE_FORCE);
        overflow.forEach((slot, item) -> {
            drop.put(new BagChanges.SlotChange(slot, bag.baseline().get(slot), null, null));
            drop.addLog(new BagChanges.LogEntry(item.getType().name(), ItemManage.nameOf(item), -item.getAmount()));
        });
        BagStorage.apply(drop).whenComplete((ignored, error) -> BagStorage.runSync(() -> {
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
            startSession(viewerNow, bag, maxSlot, false, startPage);
        }));
    }

    private static void startSession(Player viewer, BagStorage.LoadedBag bag, int maxSlot, boolean readOnly, int startPage) {
        Map<Integer, ItemStack> items = new HashMap<>();
        bag.items().forEach((slot, item) -> {
            if (slot < maxSlot) items.put(slot, item);
        });
        Set<Integer> broken = new HashSet<>();
        bag.brokenRows().forEach(slot -> {
            if (slot < maxSlot) broken.add(slot);
        });

        SESSIONS.put(viewer.getUniqueId(), new BagSession(bag.playerInfo(), maxSlot, items, bag.baseline(),
                bag.baselineItems(), broken, bag.pageNames(), readOnly));
        openPage(viewer, startPage);
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

        int maxPage = BagMenu.maxPage(session.maxSlot());
        int fixedPage = Math.max(0, Math.min(page, maxPage - 1));
        session.setPage(fixedPage);

        boolean isOwner = player.getUniqueId().equals(session.ownerUuid());
        CustomGui gui = new CustomGui(GuiType.BAG_MENU, player,
                BagMenu.title(player, session, session.owner().getPlayerName(), isOwner));
        gui.setPage(fixedPage);
        gui.setMaxSlot(session.maxSlot());

        Inventory inventory = gui.getInventory();
        BagMenu.fillContent(inventory, session);
        BagMenu.fillControlRow(inventory, player, session, !session.readOnly(), isOwner);
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
     * 處理底部控制列的點擊（listener 已先取消事件）
     *
     * @param rightClick 是否為右鍵
     */
    public static void clickControl(Player player, CustomGui gui, int rawSlot, boolean rightClick) {
        BagSession session = getSession(player);
        if (session == null) return;

        for (VaultKind kind : VaultKind.ALL) {
            if (rawSlot != kind.tabSlot) continue;
            // 打開倉庫畫面時背包會被關掉，照一般關閉流程存檔；不在點擊事件裡換畫面，排到下一個 tick
            if (!session.readOnly() && player.getUniqueId().equals(session.ownerUuid())) {
                VaultService.of(kind).openFromTab(player);
            }
            return;
        }

        int tabPage = BagMenu.tabPage(session, rawSlot);
        if (tabPage >= 0) {
            boolean canRename = !session.readOnly() && player.getUniqueId().equals(session.ownerUuid());
            if (rightClick && canRename) {
                promptRename(player, tabPage);
                return;
            }
            if (tabPage != gui.getPage()) switchPage(player, gui, tabPage);
            return;
        }

        if (session.readOnly()) return;
        if (rawSlot == BagMenu.SORT_SLOT && checkCooldown(player)) sort(player, gui, session);
        if (rawSlot == BagMenu.DEPOSIT_SLOT && checkCooldown(player)) deposit(player, gui, session);
    }

    /**
     * 判斷該 rawSlot 是否為底部控制列
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

    /**
     * shift 點身上的物品：只放進目前這一頁，先疊到這頁同種的堆，再放這頁的空格
     * 跟原版箱子一樣，這頁放滿了就留在身上，不會跑到看不到的其他頁
     *
     * @return 實際移入的數量
     */
    public static int moveItemToBag(Player player, CustomGui gui, ItemStack source) {
        BagSession session = getSession(player);
        if (session == null || session.readOnly() || gui == null || source == null || source.getType().isAir()) return 0;

        savePage(session, gui);
        int start = gui.getPage() * PAGE_CONTENT_SIZE;
        int moved = insert(session, source, start, Math.min(start + PAGE_CONTENT_SIZE, session.maxSlot()));
        BagMenu.fillContent(gui.getInventory(), session);
        return moved;
    }

    /**
     * 把物品放進 session 的所有格位
     *
     * @return 實際放入的數量（source 本身不會被改動）
     */
    private static int insert(BagSession session, ItemStack source) {
        return insert(session, source, 0, session.maxSlot());
    }

    /**
     * 把物品放進 session 的 [start, end) 格位：先疊到同種的堆，再放空格（跳過讀不出來的格位）
     *
     * @return 實際放入的數量（source 本身不會被改動）
     */
    private static int insert(BagSession session, ItemStack source, int start, int end) {
        int originalAmount = source.getAmount();
        ItemStack moving = source.clone();

        for (int slot = start; slot < end && moving.getAmount() > 0; slot++) {
            ItemStack existing = session.items().get(slot);
            if (existing == null || existing.getType().isAir() || !existing.isSimilar(moving)) continue;

            int moveAmount = Math.min(existing.getMaxStackSize() - existing.getAmount(), moving.getAmount());
            if (moveAmount <= 0) continue;

            existing.setAmount(existing.getAmount() + moveAmount);
            moving.setAmount(moving.getAmount() - moveAmount);
        }

        for (int slot = start; slot < end && moving.getAmount() > 0; slot++) {
            if (session.brokenSlots().contains(slot)) continue;
            ItemStack existing = session.items().get(slot);
            if (existing != null && !existing.getType().isAir()) continue;

            int moveAmount = Math.min(moving.getMaxStackSize(), moving.getAmount());
            ItemStack placed = moving.clone();
            placed.setAmount(moveAmount);
            session.items().put(slot, placed);
            moving.setAmount(moving.getAmount() - moveAmount);
        }

        return originalAmount - moving.getAmount();
    }

    /**
     * 整理目前這一頁：同種物品疊在一起，依物品種類、名稱、數量排好，從這頁第一格開始放
     * 只動這一頁，其他頁的擺放是玩家自己分好的，不能被打亂；讀不出來的格位原地不動
     */
    private static void sort(Player player, CustomGui gui, BagSession session) {
        savePage(session, gui);

        int start = gui.getPage() * PAGE_CONTENT_SIZE;
        int end = Math.min(start + PAGE_CONTENT_SIZE, session.maxSlot());
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = start; slot < end; slot++) {
            ItemStack item = session.items().get(slot);
            if (item == null || item.getType().isAir()) continue;
            stack(stacks, item.clone());
        }
        stacks.sort(Comparator.<ItemStack>comparingInt(item -> item.getType().ordinal())
                .thenComparing(ItemManage::nameOf)
                .thenComparing(ItemStack::getAmount, Comparator.reverseOrder()));

        session.items().keySet().removeIf(slot -> slot >= start && slot < end);
        int slot = start;
        for (ItemStack item : stacks) {
            while (session.brokenSlots().contains(slot)) slot++;
            session.items().put(slot++, item);
        }

        BagMenu.fillContent(gui.getInventory(), session);
        BagMenu.fillControlRow(gui.getInventory(), player, session, true, player.getUniqueId().equals(session.ownerUuid()));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    /**
     * 疊進已有的同種堆，疊不下的另起一堆
     */
    private static void stack(List<ItemStack> stacks, ItemStack item) {
        for (ItemStack existing : stacks) {
            if (item.getAmount() <= 0) return;
            if (!existing.isSimilar(item)) continue;
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) continue;
            int move = Math.min(space, item.getAmount());
            existing.setAmount(existing.getAmount() + move);
            item.setAmount(item.getAmount() - move);
        }
        if (item.getAmount() > 0) stacks.add(item);
    }

    /**
     * 一鍵存入：身上（第 10～36 格，不含快捷列、裝備、副手）只要背包裡已有同種的，就全部存進去
     */
    private static void deposit(Player player, CustomGui gui, BagSession session) {
        savePage(session, gui);

        List<ItemStack> kinds = new ArrayList<>(session.items().values());
        PlayerInventory inventory = player.getInventory();
        int moved = 0;
        boolean matched = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir() || isPortableContainer(item)) continue;
            if (kinds.stream().noneMatch(kind -> kind.isSimilar(item))) continue;

            matched = true;
            int amount = insert(session, item);
            if (amount <= 0) continue;
            moved += amount;
            if (amount >= item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - amount);
                inventory.setItem(slot, item);
            }
        }

        BagMenu.fillContent(gui.getInventory(), session);
        BagMenu.fillControlRow(gui.getInventory(), player, session, true, player.getUniqueId().equals(session.ownerUuid()));
        if (moved > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.0f);
            Message.sendPrefix(player, Message.MESSAGE__BAG_DEPOSITED, moved);
        } else {
            Message.sendPrefix(player, matched ? Message.MESSAGE__BAG_FULL : Message.MESSAGE__BAG_DEPOSIT_NONE);
        }
    }

    private static boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = LAST_ACTION.get(player.getUniqueId());
        if (last != null && now - last < ACTION_COOLDOWN_MS) return false;
        LAST_ACTION.put(player.getUniqueId(), now);
        return true;
    }

    /**
     * 右鍵頁籤：關掉背包，在聊天框帶出 /bag name 指令讓玩家輸入名稱
     * 不攔截聊天訊息，避免名稱被聊天外掛廣播出去
     */
    private static void promptRename(Player player, int page) {
        // 不在 InventoryClickEvent 裡直接關畫面，排到下一個 tick
        Bukkit.getScheduler().runTask(Main.getInst(), () -> {
            finishSession(player);
            String command = "/bag name " + (page + 1) + " ";
            player.sendMessage(Component.text(Message.getMsg(Message.MESSAGE__PREFIX))
                    .append(Component.text(Message.getMsg(Message.MESSAGE__BAG_RENAME_HINT, page + 1, PAGE_NAME_MAX_LENGTH))
                            .clickEvent(ClickEvent.suggestCommand(command))
                            .hoverEvent(HoverEvent.showText(Component.text(command)))));
        });
    }

    /**
     * 幫自己的背包頁面取名字；name 為 null 或空白表示清除。完成後打開那一頁
     *
     * @param page 頁碼（1 起算，玩家看到的數字）
     */
    public static void renamePage(Player player, int page, String name) {
        int maxPage = getMaxPage(player);
        if (page < 1 || page > maxPage) {
            Message.sendPrefix(player, Message.MESSAGE__BAG_INVALID_PAGE, maxPage);
            return;
        }

        String cleaned = name == null ? "" : name.replaceAll("[§&][0-9a-fk-orA-FK-ORxX#]", "").trim();
        if (cleaned.codePointCount(0, cleaned.length()) > PAGE_NAME_MAX_LENGTH) {
            Message.sendPrefix(player, Message.MESSAGE__BAG_RENAME_TOO_LONG, PAGE_NAME_MAX_LENGTH);
            return;
        }
        String saved = cleaned.isEmpty() ? null : cleaned;

        UUID playerId = player.getUniqueId();
        BagStorage.savePageName(playerId, page - 1, saved).whenComplete((ignored, error) -> BagStorage.runSync(() -> {
            Player playerNow = Bukkit.getPlayer(playerId);
            if (playerNow == null) return;
            if (error != null) {
                BagStorage.logSevere("無法保存背包頁面名稱: " + playerNow.getName(), error);
                Message.sendPrefix(playerNow, Message.MESSAGE__ERROR, "無法保存頁面名稱");
                return;
            }
            if (saved == null) {
                Message.sendPrefix(playerNow, Message.MESSAGE__BAG_RENAME_CLEARED, page);
            } else {
                Message.sendPrefix(playerNow, Message.MESSAGE__BAG_RENAMED, page, saved);
            }
            open(playerNow, page - 1);
        }));
    }

    /**
     * 顯示某位玩家的背包存取紀錄（玩家或主控台都能用）
     *
     * @param page 頁碼（1 起算）
     */
    public static void showLogs(CommandSender sender, String targetName, int page) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showLogs(sender, online.getUniqueId(), online.getName(), page);
            return;
        }
        BagStorage.findByName(targetName).whenComplete((playerInfo, error) -> BagStorage.runSync(() -> {
            if (error != null) {
                BagStorage.logSevere("無法查詢玩家背包資料: " + targetName, error);
                Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法讀取紀錄");
                return;
            }
            if (playerInfo == null) {
                Message.sendPrefix(sender, Message.MESSAGE__NO_PLAYER);
                return;
            }
            showLogs(sender, playerInfo.getPlayerUUID(), playerInfo.getPlayerName(), page);
        }));
    }

    private static void showLogs(CommandSender sender, UUID owner, String ownerName, int page) {
        int fixedPage = Math.max(1, page);
        BagStorage.loadLogs(owner, (fixedPage - 1) * LOG_PAGE_SIZE, LOG_PAGE_SIZE)
                .whenComplete((logs, error) -> BagStorage.runSync(() -> {
                    if (error != null) {
                        BagStorage.logSevere("無法讀取背包存取紀錄: " + ownerName, error);
                        Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法讀取紀錄");
                        return;
                    }

                    Message.sendPrefix(sender, Message.MESSAGE__BAG_LOG_HEADER, ownerName, fixedPage);
                    if (logs.isEmpty()) {
                        Message.send(sender, Message.MESSAGE__BAG_LOG_EMPTY);
                        return;
                    }
                    for (BagLog log : logs) sender.sendMessage(formatLog(log, owner));
                    if (logs.size() == LOG_PAGE_SIZE) {
                        String next = "/bag log " + ownerName + " " + (fixedPage + 1);
                        sender.sendMessage(Component.text(Message.getMsg(Message.MESSAGE__BAG_LOG_NEXT))
                                .clickEvent(ClickEvent.runCommand(next))
                                .hoverEvent(HoverEvent.showText(Component.text(next))));
                    }
                }));
    }

    private static String formatLog(BagLog log, UUID owner) {
        StringBuilder line = new StringBuilder("§7")
                .append(LOG_TIME.format(Instant.ofEpochMilli(log.createdAt())))
                .append(" §8").append(log.server() != null ? log.server() : "?")
                .append(log.amount() > 0 ? " §a+" : " §c").append(log.amount())
                .append(" §f").append(log.itemName() != null ? log.itemName() : log.material());
        if (log.viewer() != null && !log.viewer().equals(owner)) {
            line.append(" §8（").append(log.viewerName()).append(" 操作）");
        }
        if (BagChanges.SOURCE_FORCE.equals(log.source())) line.append(" §8（強制開啟掉落）");
        String vault = VaultKind.logLabel(log.source());
        if (vault != null) line.append(" §8（").append(vault).append("）");
        return line.toString();
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

        endSession(player, session);
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
        endSession(viewer, session);
        // session 已移除，觸發的 InventoryCloseEvent 會直接 return，不會再存一次
        viewer.closeInventory();
    }

    private static void endSession(Player viewer, BagSession session) {
        UUID viewerId = viewer.getUniqueId();
        SESSIONS.remove(viewerId);
        OWNERS.remove(session.ownerUuid(), viewerId);
        LAST_ACTION.remove(viewerId);
        if (!session.readOnly()) BagStorage.save(session.snapshot(viewerId, viewer.getName()), viewerId);
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
            if (!session.readOnly()) {
                snapshots.add(session.snapshot(entry.getKey(), viewer != null ? viewer.getName() : null));
            }
        }
        SESSIONS.clear();
        OWNERS.clear();
        LOADING.clear();
        PAGE_SWITCHING.clear();
        LAST_ACTION.clear();
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
        return BagMenu.maxPage(getMaxSlot(player));
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
