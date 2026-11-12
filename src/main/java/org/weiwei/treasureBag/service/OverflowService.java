package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.BagChanges;
import org.weiwei.treasureBag.entity.PlayerInfo;
import org.weiwei.treasureBag.util.ItemManage;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 超出格取出（主執行緒）：背包格數變少（VIP 到期、降階）時，一般開啟不開背包、也不把東西丟地上，
 * 改開這個介面，讓玩家把超出範圍的格子一格一格拿到身上，拿完才能正常開背包
 * <p>
 * 介面開著時佔用 {@link PlayerBagService} 的擁有者鎖（讀取前就登記了），關掉才放：
 * 同一個背包在這台同時只能有一個人操作，管理員查看也互擋。
 * 介面上的點擊（含身上、shift、數字鍵、雙擊收集）與拖曳一律取消，物品只靠程式移動。
 * <p>
 * 取出順序：先在資料庫刪掉那一格（跟存取紀錄同一筆交易），成功才給物品；失敗就不給，物品還在資料庫。
 * 刪完人已下線就寫回原格；身上剛好被塞滿的部分掉在腳邊（只有本人撿得起來）並記 log。
 * 同一時間只處理一件，處理中的點擊（包含連點同一格）一律忽略，所以「放得下」的檢查不會被兩件同時用掉。
 */
public final class OverflowService {

    /** 只認滑鼠左右鍵（含 shift）；數字鍵、丟棄、雙擊收集、中鍵都不算拿 */
    private static final Set<ClickType> TAKE_CLICKS = EnumSet.of(
            ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT);

    /** 觀看者 → 開著的超出格取出介面 */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** 資料庫刪除已經送出、還沒交出（或寫回）的取出；停用時從這裡找回來 */
    private static final Set<Take> TAKES = new HashSet<>();

    private OverflowService() {
    }

    /**
     * 開著的超出格取出介面
     */
    private static final class Session {
        final PlayerInfo owner;
        final int maxSlot;
        /** 還沒拿的超出格：全域格位 → 物品（照格位排） */
        final TreeMap<Integer, ItemStack> items;
        /** 讀進來時資料庫的原始字串（刪除、寫回用） */
        final Map<Integer, String> baseline;
        final Inventory inventory;
        int page;
        /** 正在處理的那一件，null 表示沒有 */
        Take taking;

        Session(PlayerInfo owner, int maxSlot, TreeMap<Integer, ItemStack> items, Map<Integer, String> baseline,
                Inventory inventory) {
            this.owner = owner;
            this.maxSlot = maxSlot;
            this.items = items;
            this.baseline = baseline;
            this.inventory = inventory;
        }
    }

    /**
     * 一次取出（一整格）
     */
    private static final class Take {
        final Session session;
        final UUID viewer;
        final String viewerName;
        final int slot;
        final ItemStack item;
        final String value;
        /** IO 執行緒寫、停用交接讀：資料庫那邊做完了沒、刪掉了沒（先寫 deleted 再寫 done） */
        volatile boolean deleted;
        volatile boolean done;

        Take(Session session, UUID viewer, String viewerName, int slot, ItemStack item, String value) {
            this.session = session;
            this.viewer = viewer;
            this.viewerName = viewerName;
            this.slot = slot;
            this.item = item;
            this.value = value;
        }
    }

    // ---------- 開、關 ----------

    /**
     * 打開超出格取出介面（PlayerBagService 讀完背包、發現有超出格時呼叫；擁有者鎖已經登記）
     *
     * @param overflow 超出格：全域格位 → 物品（只有讀得出來的，讀不出來的留在資料庫不動）
     * @param baseline 讀進來時資料庫的原始字串
     */
    static void open(Player viewer, PlayerInfo owner, int maxSlot, Map<Integer, ItemStack> overflow,
                     Map<Integer, String> baseline) {
        UUID viewerId = viewer.getUniqueId();
        Map<Integer, String> values = new HashMap<>();
        overflow.keySet().forEach(slot -> values.put(slot, baseline.get(slot)));

        CustomGui gui = new CustomGui(GuiType.OVERFLOW_MENU, viewer, OverflowMenu.title());
        Session session = new Session(owner, maxSlot, new TreeMap<>(overflow), values, gui.getInventory());
        render(session);
        // 先開畫面再登記：開新畫面會觸發舊畫面的關閉事件
        viewer.openInventory(session.inventory);
        if (viewer.getOpenInventory().getTopInventory() != session.inventory) {
            // 畫面被別的外掛擋下來：不登記，鎖也放掉，免得之後沒有關閉事件來放
            PlayerBagService.releaseOwner(owner.getPlayerUUID(), viewerId);
            return;
        }
        SESSIONS.put(viewerId, session);
        Message.sendPrefix(viewer, Message.MESSAGE__BAG_OVERFLOW_NOTICE, overflow.size());
    }

    /**
     * 介面關掉（listener 已確認是超出格取出介面）：放掉擁有者鎖；處理中的那件照常交出
     */
    public static void close(Player player, Inventory inventory) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.inventory != inventory) return;
        end(player.getUniqueId(), session);
    }

    /**
     * 結束觀看者開著的超出格取出介面（放掉鎖、關畫面）；沒有開著就不做事
     * 開背包的共同入口會先呼叫，讀取會排在處理中的那件後面，讀到的一定是刪完的資料
     */
    static void finish(Player viewer) {
        Session session = SESSIONS.get(viewer.getUniqueId());
        if (session == null) return;
        end(viewer.getUniqueId(), session);
        // 已經移除，觸發的 InventoryCloseEvent 會直接 return
        if (viewer.getOpenInventory().getTopInventory() == session.inventory) viewer.closeInventory();
    }

    private static void end(UUID viewerId, Session session) {
        SESSIONS.remove(viewerId);
        PlayerBagService.releaseOwner(session.owner.getPlayerUUID(), viewerId);
    }

    private static void render(Session session) {
        session.page = OverflowLayout.clampPage(session.page, session.items.size());
        OverflowMenu.fill(session.inventory, new ArrayList<>(session.items.keySet()), session.items,
                session.page, session.maxSlot);
    }

    // ---------- 點擊 ----------

    /**
     * 介面上的點擊（listener 已確認是超出格取出介面）：一律取消，照點的位置處理
     */
    public static void click(Player player, InventoryClickEvent event) {
        event.setCancelled(true);
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory) return;

        int rawSlot = event.getRawSlot();
        // 點身上的物品、點到畫面外：只取消，不處理
        if (rawSlot < 0 || rawSlot >= session.inventory.getSize()) return;
        if (!TAKE_CLICKS.contains(event.getClick())) return;

        if (rawSlot < OverflowLayout.PAGE_SIZE) {
            take(player, session, rawSlot);
            return;
        }
        switch (rawSlot) {
            case OverflowMenu.PREV_SLOT -> turn(session, -1);
            case OverflowMenu.NEXT_SLOT -> turn(session, 1);
            case OverflowMenu.BACK_SLOT -> back(player, session);
            default -> {
            }
        }
    }

    private static void turn(Session session, int delta) {
        int page = OverflowLayout.clampPage(session.page + delta, session.items.size());
        if (page == session.page) return;
        session.page = page;
        render(session);
    }

    /**
     * 回到背包：超出格全部拿完才可以（拿完不自動切換，避免連點時畫面突然換成背包、點到背包裡的東西）
     */
    private static void back(Player player, Session session) {
        if (session.taking != null) return;
        if (!session.items.isEmpty()) {
            Message.sendPrefix(player, Message.MESSAGE__BAG_OVERFLOW_BACK_BLOCKED, session.items.size());
            return;
        }
        // 不在點擊事件裡換畫面，排到下一個 tick；開背包會先結束這個介面（放掉鎖）再重新讀取
        UUID viewerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(Main.getInst(), () -> {
            Player now = Bukkit.getPlayer(viewerId);
            if (now != null) PlayerBagService.open(now);
        });
    }

    /**
     * 點一件超出格物品：先檢查身上放得下，再刪資料庫，刪成功才給
     */
    private static void take(Player player, Session session, int rawSlot) {
        // 上一件還在處理（含連點同一格）：這次點擊忽略
        if (session.taking != null) return;
        List<Integer> slots = new ArrayList<>(session.items.keySet());
        int index = OverflowLayout.indexAt(session.page, rawSlot, slots.size());
        if (index < 0) return;
        int slot = slots.get(index);
        ItemStack item = session.items.get(slot);

        // 放不下就不動資料庫
        if (!fits(player.getInventory(), item)) {
            Message.sendPrefix(player, Message.MESSAGE__BAG_OVERFLOW_NO_ROOM);
            return;
        }

        UUID viewerId = player.getUniqueId();
        Take take = new Take(session, viewerId, player.getName(), slot, item, session.baseline.get(slot));
        session.taking = take;
        TAKES.add(take);

        // 刪除與存取紀錄同一筆交易；排在這位擁有者的佇列裡，之後重開背包一定讀到刪完的資料
        PlayerInfo owner = session.owner;
        BagChanges changes = new BagChanges(owner.getPlayerUUID(), owner.getPlayerName())
                .by(viewerId, player.getName(), BagChanges.SOURCE_OVERFLOW);
        changes.put(new BagChanges.SlotChange(slot, take.value, null, null));
        changes.addLog(new BagChanges.LogEntry(item.getType().name(), ItemManage.nameOf(item), -item.getAmount()));
        BagStorage.apply(changes).whenComplete((ignored, error) -> {
            take.deleted = error == null;
            take.done = true;
            BagStorage.runSync(() -> settle(take, error));
        });
    }

    /**
     * 資料庫那邊做完（回到主執行緒）：刪成功就交出，人不在就寫回；刪失敗就什麼都不給
     */
    private static void settle(Take take, Throwable error) {
        // 只在主執行緒碰 TAKES：已經被停用交接處理過的不會再給一次
        if (!TAKES.remove(take)) return;
        Session session = take.session;
        if (session.taking == take) session.taking = null;
        Player viewer = Bukkit.getPlayer(take.viewer);

        if (error != null) {
            BagStorage.logSevere("無法取出超出格物品: " + session.owner.getPlayerName() + " 格位 " + take.slot, error);
            if (viewer != null) Message.sendPrefix(viewer, Message.MESSAGE__ERROR, "無法取出，物品還在背包裡");
            return;
        }

        session.items.remove(take.slot);
        session.baseline.remove(take.slot);
        if (viewer == null) {
            // 刪完人已經下線：寫回原格位，下次開背包會再出現在這個介面
            BagStorage.restore(returnChanges(take));
            return;
        }

        deliver(viewer, take);
        // 介面還開著才重畫（關掉了就只交物品）
        if (SESSIONS.get(take.viewer) == session) {
            render(session);
            if (session.items.isEmpty()) Message.sendPrefix(viewer, Message.MESSAGE__BAG_OVERFLOW_DONE);
        }
    }

    /**
     * 交物品：放不下的部分（處理期間身上剛好被塞滿）掉在腳邊，只有本人撿得起來，並記 log
     */
    private static void deliver(Player viewer, Take take) {
        int dropped = 0;
        for (ItemStack rest : viewer.getInventory().addItem(take.item.clone()).values()) {
            Item drop = viewer.getWorld().dropItem(viewer.getLocation(), rest);
            drop.setOwner(viewer.getUniqueId());
            dropped += rest.getAmount();
        }
        if (dropped > 0) {
            BagStorage.logWarning("超出格取出時身上放不下，掉在腳邊: " + viewer.getName() + " 格位 " + take.slot
                    + " " + take.item.getType() + " x" + dropped + " " + ItemManage.nameOf(take.item));
            Message.sendPrefix(viewer, Message.MESSAGE__BAG_OVERFLOW_DROPPED, dropped);
            return;
        }
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.0f);
        viewer.sendActionBar(Component.text(Message.getMsg(Message.MESSAGE__BAG_OVERFLOW_TAKEN, take.session.items.size())));
    }

    /**
     * 把取出的那一格寫回原格位（刪掉後原本是空格，所以 expected 是 null），存取紀錄記一筆寫回
     */
    private static BagChanges returnChanges(Take take) {
        PlayerInfo owner = take.session.owner;
        String name = ItemManage.nameOf(take.item);
        BagChanges changes = new BagChanges(owner.getPlayerUUID(), owner.getPlayerName())
                .by(take.viewer, take.viewerName, BagChanges.SOURCE_OVERFLOW_RETURN);
        changes.put(new BagChanges.SlotChange(take.slot, null, take.value, name));
        changes.addLog(new BagChanges.LogEntry(take.item.getType().name(), name, take.item.getAmount()));
        return changes;
    }

    /**
     * 整組放不放得進身上（主物品欄 36 格，不含裝備、副手；跟 addItem 放的位置一樣）
     */
    private static boolean fits(PlayerInventory inventory, ItemStack item) {
        int stackRoom = 0;
        int empty = 0;
        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.getType().isAir()) {
                empty++;
                continue;
            }
            if (content.isSimilar(item)) stackRoom += Math.max(0, content.getMaxStackSize() - content.getAmount());
        }
        return OverflowLayout.fits(item.getAmount(), item.getMaxStackSize(), stackRoom, empty);
    }

    // ---------- 停用 ----------

    /**
     * 外掛停用（PlayerBagService.shutdown 開頭）：清掉介面再關畫面（關閉事件看到沒有 session 就不處理）；
     * 擁有者鎖由 PlayerBagService 一起清。處理中的取出留在 {@link #TAKES}，等 IO 執行緒結束後交接
     */
    static void shutdown() {
        List<Player> viewers = new ArrayList<>();
        for (UUID viewerId : SESSIONS.keySet()) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) viewers.add(viewer);
        }
        SESSIONS.clear();
        viewers.forEach(Player::closeInventory);
    }

    /**
     * 外掛停用、IO 執行緒都結束後（BagStorage.shutdown 之後）：處理還沒交出的取出。
     * 停用中回主執行緒的工作排不進去，不在這裡處理的話，資料庫刪掉的那件就沒人給了
     * <ul>
     *     <li>資料庫刪掉了：人在線上直接給；不在就用嚴格模式寫回原格（寫不進去存成 unsaved 檔，下次啟動補寫）</li>
     *     <li>刪除失敗：物品還在資料庫，不用處理</li>
     *     <li>等太久還沒做完、不知道刪了沒：不給也不寫，另存 conflict 檔交給管理員比對</li>
     * </ul>
     */
    static void settleAfterShutdown() {
        for (Take take : new ArrayList<>(TAKES)) {
            if (!take.done) {
                BagStorage.dumpForReview(returnChanges(take), "停用時超出格取出還沒做完，不確定資料庫有沒有刪掉");
                continue;
            }
            if (!take.deleted) continue;

            take.session.items.remove(take.slot);
            Player viewer = Bukkit.getPlayer(take.viewer);
            if (viewer != null) {
                deliver(viewer, take);
            } else {
                BagStorage.writeNow(returnChanges(take));
            }
        }
        TAKES.clear();
    }
}
