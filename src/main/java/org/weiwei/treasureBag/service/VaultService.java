package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.VaultRepository;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 倉庫（主執行緒）：自動收集、介面上的存取、管理員調整；礦物倉庫與副本素材倉庫各一份（{@link #ORE}、{@link #MATERIAL}），
 * 不一樣的地方在 {@link VaultKind}
 * <p>
 * 資料放背包的中央資料庫，每個分流共用同一個倉庫。算的是件數不是格子：容量照 VIP 階級
 * （跟背包同一組權限 treasure-bag.&lt;階級&gt;，見 Config.yml 的 ORE.SPACE、MATERIAL.SPACE），
 * /bag oreadmin（materialadmin）space 個別設定優先。存量超過容量的只能取出，取到容量以下才能再存（自動收集也停）。
 * <p>
 * 數量的正確性靠資料庫的加減（見 {@link VaultRepository}），這台的 {@link VaultAccount} 只是畫面與容量判斷用的快取；
 * 每次打開畫面都重讀一次，換分流時上一台還沒送完的數量也會補上。
 */
public final class VaultService {

    public static final VaultService ORE = new VaultService(VaultKind.ORE);
    public static final VaultService MATERIAL = new VaultService(VaultKind.MATERIAL);
    public static final List<VaultService> ALL = List.of(ORE, MATERIAL);

    private static final long FLUSH_PERIOD_TICKS = 20L * 5;
    private static final long FULL_NOTICE_MS = 30_000;
    private static final long ACTION_COOLDOWN_MS = 200;

    public final VaultKind kind;
    private final VaultStorage storage;
    /** 這台在線玩家的倉庫 */
    private final Map<UUID, VaultAccount> accounts = new HashMap<>();
    /** 觀看者 → 開著的倉庫畫面 */
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    /** 這次開機已經說明過自動收集的玩家 */
    private final Set<UUID> hinted = new HashSet<>();
    private BukkitTask flushTask;

    private VaultService(VaultKind kind) {
        this.kind = kind;
        this.storage = new VaultStorage(kind);
    }

    public static VaultService of(VaultKind kind) {
        return kind == VaultKind.ORE ? ORE : MATERIAL;
    }

    /**
     * 畫面種類對應的倉庫；不是倉庫畫面回傳 null
     */
    public static VaultService of(GuiType type) {
        for (VaultService service : ALL) {
            if (service.kind.guiType == type) return service;
        }
        return null;
    }

    /**
     * 開著的倉庫畫面
     */
    private static final class View {
        final VaultAccount account;
        final boolean readOnly;
        final Inventory inventory;
        int page;

        View(VaultAccount account, boolean readOnly, Inventory inventory) {
            this.account = account;
            this.readOnly = readOnly;
            this.inventory = inventory;
        }
    }

    private record Target(UUID uuid, String name) {
    }

    // ---------- 生命週期 ----------

    /**
     * 啟動（BagStorage.start 與舊資料匯入之後）：補寫上次沒存進去的、定時送出自動收集、幫在線玩家讀倉庫
     */
    public void start() {
        storage.start();
        storage.recoverFiles();
        flushTask = Bukkit.getScheduler().runTaskTimer(Main.getInst(), () -> {
            accounts.values().forEach(this::flush);
            storage.retryUnsaved();
        }, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
        Bukkit.getOnlinePlayers().forEach(this::join);
    }

    /**
     * 外掛停用：在 PlayerBagService.shutdown（等佇列做完、關掉 IO 執行緒）之後呼叫，還沒送出的直接寫入
     */
    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
        for (UUID viewer : new ArrayList<>(views.keySet())) {
            views.remove(viewer);
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) player.closeInventory();
        }
        Map<UUID, Map<String, Long>> pending = new HashMap<>();
        accounts.forEach((owner, account) -> {
            Map<String, Long> deltas = account.drainPending();
            if (!deltas.isEmpty()) pending.put(owner, deltas);
        });
        accounts.clear();
        lastAction.clear();
        storage.shutdown(pending);
    }

    public void join(Player player) {
        VaultAccount account = new VaultAccount(kind, player.getUniqueId());
        account.tierSpace = tierSpace(player);
        accounts.put(player.getUniqueId(), account);
        refresh(account);
    }

    public void quit(Player player) {
        UUID id = player.getUniqueId();
        views.remove(id);
        lastAction.remove(id);
        VaultAccount account = accounts.remove(id);
        if (account != null) flush(account);
    }

    /**
     * 匯入舊資料後，在線玩家重讀一次
     */
    public void reloadOnline() {
        accounts.values().forEach(this::refresh);
    }

    /**
     * 主控台 /bag oreadmin import：背景匯入 ExtraStorage，完成後回主執行緒通知並重讀在線玩家的礦物倉庫
     */
    public static void importOre(CommandSender sender) {
        CompletableFuture.supplyAsync(OreImporter::run).whenComplete((result, error) -> BagStorage.runSync(() -> {
            if (error != null) {
                BagStorage.logSevere("匯入 ExtraStorage 失敗", error);
                Message.sendPrefix(sender, Message.MESSAGE__ERROR, "匯入失敗");
                return;
            }
            Message.sendPrefix(sender, Message.MESSAGE__ORE_IMPORT_RESULT, result != null ? result : "沒有 ExtraStorage 資料檔");
            ORE.reloadOnline();
        }));
    }

    // ---------- 開畫面 ----------

    public void open(Player player) {
        UUID id = player.getUniqueId();
        VaultAccount account = accounts.computeIfAbsent(id, uuid -> new VaultAccount(kind, uuid));
        // 每次打開重算一次，升級 VIP 不用重登
        account.tierSpace = tierSpace(player);
        whenLoaded(account, ok -> {
            Player now = Bukkit.getPlayer(id);
            if (now == null) return;
            if (!ok) {
                Message.sendPrefix(now, kind.loadFailed);
                return;
            }
            if (!usable(account)) {
                Message.sendPrefix(now, kind.noSpace);
                return;
            }
            show(now, account, now.getName(), false);
        });
    }

    /**
     * 能不能打開：有容量，或還有東西可以取出（VIP 到期）；還沒讀到就先當可以，打開時再判斷
     */
    public boolean usable(Player player) {
        if (kind.noSpace == null) return true;
        VaultAccount account = accounts.get(player.getUniqueId());
        if (account == null || !account.loaded) return true;
        account.tierSpace = tierSpace(player);
        return usable(account);
    }

    private boolean usable(VaultAccount account) {
        return kind.noSpace == null || account.space() > 0 || account.total() > 0;
    }

    /**
     * 管理員查看別人的倉庫（唯讀；要調整用 /bag oreadmin、/bag materialadmin）
     */
    public void openOther(Player viewer, String targetName) {
        UUID viewerId = viewer.getUniqueId();
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            Player viewerNow = Bukkit.getPlayer(viewerId);
            if (viewerNow == null) return;
            if (error != null || target == null) {
                if (error != null) BagStorage.logSevere("無法查詢玩家: " + targetName, error);
                Message.sendPrefix(viewerNow, Message.MESSAGE__NO_PLAYER);
                return;
            }
            if (target.uuid().equals(viewerId)) {
                open(viewerNow);
                return;
            }

            // 對方在這台就看這台的快取（含還沒送出的），不在就另外讀一份
            VaultAccount account = accounts.containsKey(target.uuid()) ? accounts.get(target.uuid()) : new VaultAccount(kind, target.uuid());
            whenLoaded(account, ok -> {
                Player now = Bukkit.getPlayer(viewerId);
                if (now == null) return;
                if (!ok) {
                    Message.sendPrefix(now, kind.loadFailed);
                    return;
                }
                show(now, account, target.name(), true);
            });
        }));
    }

    private void show(Player player, VaultAccount account, String ownerName, boolean readOnly) {
        boolean isOwner = player.getUniqueId().equals(account.owner);
        CustomGui gui = new CustomGui(kind.guiType, player, VaultMenu.title(kind, player, ownerName, isOwner));
        View view = new View(account, readOnly, gui.getInventory());
        VaultMenu.fill(view.inventory, player, account, 0, readOnly);
        // 先開畫面再登記：開新畫面會觸發舊畫面的關閉事件，那時候不能把新的登記清掉
        player.openInventory(view.inventory);
        views.put(player.getUniqueId(), view);
    }

    private void render(Player viewer, View view) {
        view.page = Math.max(0, Math.min(view.page, VaultMenu.maxPage(view.account) - 1));
        VaultMenu.fill(view.inventory, viewer, view.account, view.page, view.readOnly);
    }

    private void refreshViews(VaultAccount account) {
        views.forEach((viewer, view) -> {
            if (view.account != account) return;
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) render(player, view);
        });
    }

    public void close(Player player, Inventory inventory) {
        View view = views.get(player.getUniqueId());
        if (view != null && view.inventory == inventory) views.remove(player.getUniqueId());
    }

    // ---------- 讀取 ----------

    private void whenLoaded(VaultAccount account, Consumer<Boolean> then) {
        account.waiting.add(then);
        refresh(account);
    }

    /**
     * 重讀資料庫；讀取中就不再排，讀完一起處理 waiting
     */
    private void refresh(VaultAccount account) {
        if (account.afterReload != null) return;
        flush(account);
        account.afterReload = new HashMap<>();
        UUID owner = account.owner;
        storage.load(owner, kind.defaultList()).whenComplete((result, error) -> BagStorage.runSync(() -> {
            Map<String, Long> sent = account.afterReload;
            account.afterReload = null;
            List<Consumer<Boolean>> waiting = new ArrayList<>(account.waiting);
            account.waiting.clear();
            if (error != null) {
                BagStorage.logSevere("無法讀取" + kind.label + ": " + owner, error);
                waiting.forEach(callback -> callback.accept(false));
                return;
            }
            account.apply(result, sent);
            waiting.forEach(callback -> callback.accept(true));
            refreshViews(account);
        }));
    }

    // ---------- 自動收集 ----------

    /**
     * 挖方塊掉出來的東西（礦物倉庫）：身上放不下的部分（Config.yml ORE.ONLY_WHEN_FULL 關掉就是全部）收進倉庫
     */
    public void collect(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;
        VaultAccount account = accounts.get(player.getUniqueId());
        if (account == null || !account.loaded || !account.enabled || event.getItems().isEmpty()) return;
        // 大部分挖掘沒有要收的東西，先看一眼，沒有就不用算身上空間
        if (event.getItems().stream().noneMatch(drop -> collectable(account, drop.getItemStack()))) return;
        account.tierSpace = tierSpace(player);

        InventoryRoom room = kind.onlyWhenFull() ? new InventoryRoom(player.getInventory()) : null;
        if (room != null) {
            // 不收的東西照原版會被撿起來，先扣掉它們要佔的空間，不然會以為要收的放得下、結果留在地上
            for (Item drop : event.getItems()) {
                ItemStack stack = drop.getItemStack();
                if (!collectable(account, stack)) room.fit(stack, stack.getAmount());
            }
        }
        long free = account.free();
        Map<String, Long> stored = new LinkedHashMap<>();
        boolean full = false;
        for (Iterator<Item> iterator = event.getItems().iterator(); iterator.hasNext(); ) {
            Item drop = iterator.next();
            ItemStack stack = drop.getItemStack();
            if (!collectable(account, stack)) continue;
            String key = kind.key(stack);

            int amount = stack.getAmount();
            int overflow = room == null ? amount : amount - room.fit(stack, amount);
            if (overflow <= 0) continue;
            int store = (int) Math.min(overflow, free);
            if (store <= 0) {
                full = true;
                continue;
            }

            free -= store;
            if (store >= amount) {
                iterator.remove();
            } else {
                stack.setAmount(amount - store);
                drop.setItemStack(stack);
            }
            account.add(key, store, true);
            account.pending.merge(key, (long) store, Long::sum);
            stored.merge(key, (long) store, Long::sum);
        }

        if (!stored.isEmpty()) {
            player.sendActionBar(storedBar(stored));
        } else if (full) {
            notifyFull(player, account);
        }
    }

    /**
     * 撿起地上的東西（副本素材倉庫）：要收的整堆收進倉庫，事件取消、地上的物品移除
     * <p>
     * Config.yml MATERIAL.ONLY_WHEN_FULL 打開時只收身上放不下的那幾件（放得下的下一個 tick 照原版撿）。
     * 沒有容量（一般玩家）、倉庫滿了、自動收集關掉、指定給別人的掉落物，都照原版撿。
     *
     * @param remaining 身上放不下的件數：0＝整堆放得下（EntityPickupItemEvent）；
     *                  大於 0＝放不下（PlayerAttemptPickupItemEvent，背包滿時每 tick 都會來）
     */
    public void pickup(Player player, Item drop, Cancellable event, int remaining) {
        VaultAccount account = accounts.get(player.getUniqueId());
        if (account == null || !account.loaded || !account.enabled) return;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;
        ItemStack stack = drop.getItemStack();
        if (!collectable(account, stack) || !drop.isValid()) return;
        UUID owner = drop.getOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) return;
        int amount = stack.getAmount();
        int want = kind.onlyWhenFull() ? Math.min(remaining, amount) : amount;
        if (want <= 0) return;

        account.tierSpace = tierSpace(player);
        if (account.space() <= 0) return;
        int store = (int) Math.min(want, account.free());
        if (store <= 0) {
            notifyFull(player, account);
            return;
        }

        String key = kind.key(stack);
        event.setCancelled(true);
        if (store >= amount) {
            // 撿起的動畫與音效（客戶端收到這個封包才會播）
            player.playPickupItemAnimation(drop, amount);
            drop.remove();
        } else {
            stack.setAmount(amount - store);
            drop.setItemStack(stack);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.6f);
        }
        account.add(key, store, true);
        account.pending.merge(key, (long) store, Long::sum);
        player.sendActionBar(storedBar(Map.of(key, (long) store)));
        if (kind.collectHint() != null && hinted.add(player.getUniqueId())) Message.sendPrefix(player, kind.collectHint());
        refreshViews(account);
    }

    /**
     * 這堆物品要不要收：倉庫收這種東西，而且清單裡這種有開自動收集（清單沒有的看 {@link VaultKind#collectNewKinds}）
     */
    private boolean collectable(VaultAccount account, ItemStack stack) {
        String key = kind.key(stack);
        if (key == null) return false;
        VaultAccount.Entry entry = account.entries.get(key);
        return entry != null ? entry.auto : kind.collectNewKinds();
    }

    private void notifyFull(Player player, VaultAccount account) {
        long now = System.currentTimeMillis();
        if (now - account.lastFullNotice < FULL_NOTICE_MS) return;
        account.lastFullNotice = now;
        player.sendActionBar(Component.text(kind.fullBar(account.total(), account.space()), NamedTextColor.RED));
    }

    private Component storedBar(Map<String, Long> stored) {
        TextComponent.Builder bar = Component.text();
        boolean first = true;
        for (Map.Entry<String, Long> entry : stored.entrySet()) {
            if (!first) bar.append(Component.text("、", NamedTextColor.GRAY));
            bar.append(Component.text("+" + entry.getValue() + " ", NamedTextColor.GREEN))
                    .append(kind.name(entry.getKey()).colorIfAbsent(NamedTextColor.WHITE));
            first = false;
        }
        return bar.append(Component.text(" 收進" + kind.label + " /bag " + kind.command, NamedTextColor.GRAY)).build();
    }

    /**
     * 自動收集還沒送出的部分送進資料庫
     */
    private void flush(VaultAccount account) {
        if (account.pending.isEmpty()) return;
        Map<String, Long> deltas = account.drainPending();
        account.sent(deltas);
        storage.add(new VaultRepository.Change(account.owner, null, null, kind.repository.sourceDeposit, deltas, true, false));
    }

    // ---------- 介面操作 ----------

    /**
     * 倉庫畫面上的點擊（listener 已確認是這種倉庫的畫面）：一律取消，照點的位置處理
     */
    public void click(Player player, InventoryClickEvent event) {
        event.setCancelled(true);
        View view = views.get(player.getUniqueId());
        if (view == null || event.getView().getTopInventory() != view.inventory) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || !checkCooldown(player)) return;

        if (rawSlot >= view.inventory.getSize()) {
            // 點身上的物品：整疊存進倉庫（新種類順便加進清單）
            if (!view.readOnly && event.getClickedInventory() instanceof PlayerInventory) {
                depositSlot(player, view, event.getSlot());
            }
            return;
        }

        if (rawSlot < VaultMenu.PAGE_SIZE) {
            if (view.readOnly) return;
            String key = VaultMenu.keyAt(view.account, view.page, rawSlot);
            if (key == null) return;
            switch (event.getClick()) {
                case LEFT -> withdraw(player, view, key, false);
                case SHIFT_LEFT -> withdraw(player, view, key, true);
                case RIGHT -> depositKind(player, view, key);
                case SHIFT_RIGHT -> toggleAuto(player, view, key);
                case DROP, CONTROL_DROP -> removeEntry(player, view, key);
                default -> {
                }
            }
            return;
        }

        if (!event.getClick().isMouseClick()) return;
        if (rawSlot == kind.tabSlot) {
            int maxPage = VaultMenu.maxPage(view.account);
            if (maxPage <= 1) return;
            view.page = (view.page + (event.isRightClick() ? maxPage - 1 : 1)) % maxPage;
            render(player, view);
            return;
        }
        if (view.readOnly) return;
        if (rawSlot == VaultMenu.DEPOSIT_SLOT) {
            depositAll(player, view);
        } else if (rawSlot == VaultMenu.TOGGLE_SLOT) {
            toggleEnabled(player, view);
        } else if (rawSlot >= BagMenu.FIRST_TAB_SLOT && rawSlot < BagMenu.FIRST_TAB_SLOT + BagMenu.TAB_COUNT) {
            int page = rawSlot - BagMenu.FIRST_TAB_SLOT;
            if (page < BagMenu.maxPage(PlayerBagService.getMaxSlot(player))) {
                // 不在點擊事件裡換畫面，排到下一個 tick
                Bukkit.getScheduler().runTask(Main.getInst(), () -> PlayerBagService.openAt(player, page));
            }
        } else {
            for (VaultService other : ALL) {
                if (other != this && rawSlot == other.kind.tabSlot) other.openFromTab(player);
            }
        }
    }

    /**
     * 從背包或另一個倉庫的分頁按鈕打開（點擊事件裡呼叫，排到下一個 tick 才換畫面）
     */
    public void openFromTab(Player player) {
        if (!usable(player)) {
            Message.sendPrefix(player, kind.noSpace);
            return;
        }
        Bukkit.getScheduler().runTask(Main.getInst(), () -> open(player));
    }

    private void withdraw(Player player, View view, String key, boolean fill) {
        VaultAccount account = view.account;
        VaultAccount.Entry entry = account.entries.get(key);
        if (entry == null || entry.amount <= 0 || account.withdrawing) return;

        ItemStack sample = kind.create(key, 1);
        if (sample == null) {
            Message.sendPrefix(player, Message.MESSAGE__VAULT_ITEM_MISSING);
            return;
        }
        int room = room(player.getInventory(), sample);
        if (room <= 0) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_NO_ROOM);
            return;
        }
        long want = Math.min(entry.amount, fill ? room : Math.min(room, sample.getMaxStackSize()));

        // 自動收集還沒送出的先送，資料庫才扣得到
        flush(account);
        account.withdrawing = true;
        UUID viewerId = player.getUniqueId();
        String viewerName = player.getName();
        storage.withdraw(account.owner, key, want, viewerId, viewerName)
                .whenComplete((withdrawal, error) -> BagStorage.runSync(() -> {
                    account.withdrawing = false;
                    Player now = Bukkit.getPlayer(viewerId);
                    if (error != null) {
                        BagStorage.logSevere("無法從" + kind.label + "取出: " + viewerName + " " + key, error);
                        if (now != null) Message.sendPrefix(now, Message.MESSAGE__ERROR, "無法取出");
                        return;
                    }
                    if (withdrawal == null) {
                        // 這台記的比資料庫多（例如別台剛取走），重讀一次
                        refresh(account);
                        return;
                    }
                    if (!storage.claim(withdrawal)) return;

                    VaultAccount.Entry current = account.entries.get(key);
                    if (current != null) current.amount = Math.max(0, current.amount - withdrawal.amount);
                    long left = now == null ? withdrawal.amount : give(now, sample, withdrawal.amount);
                    if (left > 0) giveBack(account, viewerId, viewerName, key, left);
                    if (now != null) {
                        long given = withdrawal.amount - left;
                        if (given > 0) {
                            now.playSound(now.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.0f);
                            Message.sendPrefix(now, kind.withdrawn, given);
                        }
                        if (left > 0) Message.sendPrefix(now, Message.MESSAGE__ORE_NO_ROOM);
                    }
                    refreshViews(account);
                }));
    }

    /**
     * 取出的東西身上放不下（取出期間背包被塞滿、人下線）：退回倉庫
     */
    private void giveBack(VaultAccount account, UUID viewer, String viewerName, String key, long amount) {
        account.add(key, amount, true);
        account.sent(Map.of(key, amount));
        storage.add(new VaultRepository.Change(account.owner, viewer, viewerName, kind.repository.sourceReturn,
                Map.of(key, amount), true, true));
    }

    /**
     * 照樣本（數量 1）給物品，一次一組；回傳放不下的件數
     */
    private static long give(Player player, ItemStack sample, long amount) {
        long left = amount;
        int max = sample.getMaxStackSize();
        while (left > 0) {
            ItemStack stack = sample.clone();
            stack.setAmount((int) Math.min(max, left));
            int count = stack.getAmount();
            int notAdded = player.getInventory().addItem(stack).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            left -= count - notAdded;
            if (notAdded > 0) break;
        }
        return left;
    }

    /**
     * 右鍵：身上（含快捷列）所有這種物品存進去
     */
    private void depositKind(Player player, View view, String key) {
        Map<String, Long> moved = takeFromInventory(player, view.account, 0, 36, item -> key.equals(kind.key(item)));
        finishDeposit(player, view, moved);
    }

    /**
     * 一鍵存入：身上第 10～36 格（不含快捷列）清單裡有的種類（副本素材是全部素材）存進去
     */
    private void depositAll(Player player, View view) {
        VaultAccount account = view.account;
        Map<String, Long> moved = takeFromInventory(player, account, 9, 36, item -> {
            String key = kind.key(item);
            return key != null && (account.entries.containsKey(key) || kind.collectNewKinds());
        });
        finishDeposit(player, view, moved);
    }

    /**
     * 點身上某一格：整疊存進去，新種類加進清單（自動收集開著）
     */
    private void depositSlot(Player player, View view, int slot) {
        if (slot < 0 || slot >= 36) return;
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || item.getType().isAir()) return;

        VaultAccount account = view.account;
        String key = kind.key(item);
        if (key == null) {
            Message.sendPrefix(player, kind.rejectMessage(item));
            return;
        }
        if (!account.entries.containsKey(key) && account.visible().size() >= kind.maxKinds()) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_TOO_MANY_KINDS, kind.maxKinds());
            return;
        }
        finishDeposit(player, view, takeFromInventory(player, account, slot, slot + 1, stack -> true));
    }

    /**
     * 從身上 [from, to) 格拿走符合條件、倉庫收的物品，最多拿到倉庫裝滿；回傳每種拿了多少
     */
    private Map<String, Long> takeFromInventory(Player player, VaultAccount account, int from, int to,
                                                Predicate<ItemStack> filter) {
        PlayerInventory inventory = player.getInventory();
        long free = account.free();
        Map<String, Long> moved = new LinkedHashMap<>();
        for (int slot = from; slot < to && free > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir() || !filter.test(item)) continue;
            String key = kind.key(item);
            if (key == null) continue;
            int amount = (int) Math.min(item.getAmount(), free);
            free -= amount;
            moved.merge(key, (long) amount, Long::sum);
            if (amount >= item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - amount);
                inventory.setItem(slot, item);
            }
        }
        return moved;
    }

    private void finishDeposit(Player player, View view, Map<String, Long> moved) {
        VaultAccount account = view.account;
        if (moved.isEmpty()) {
            if (account.space() <= 0 && kind.noSpace != null) {
                Message.sendPrefix(player, kind.noSpace);
            } else if (account.free() <= 0) {
                Message.sendPrefix(player, kind.full, account.total(), account.space());
            } else {
                Message.sendPrefix(player, Message.MESSAGE__ORE_DEPOSIT_NONE);
            }
            return;
        }

        moved.forEach((key, amount) -> account.add(key, amount, true));
        account.sent(moved);
        storage.add(new VaultRepository.Change(account.owner, player.getUniqueId(), player.getName(),
                kind.repository.sourceDeposit, moved, true, true));

        long total = moved.values().stream().mapToLong(Long::longValue).sum();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        Message.sendPrefix(player, kind.deposited, total);
        if (account.free() <= 0) Message.sendPrefix(player, kind.full, account.total(), account.space());
        refreshViews(account);
    }

    private void toggleAuto(Player player, View view, String key) {
        VaultAccount account = view.account;
        VaultAccount.Entry entry = account.entries.get(key);
        if (entry == null) return;
        entry.auto = !entry.auto;
        boolean auto = entry.auto;
        storage.setAutoCollect(account.owner, key, auto).exceptionally(error -> {
            BagStorage.logSevere("無法更新" + kind.label + "自動收集: " + account.owner + " " + key, error);
            return null;
        });
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        refreshViews(account);
    }

    /**
     * Q：數量是 0 的種類從清單移除（資料庫裡也要是 0 才刪）
     */
    private void removeEntry(Player player, View view, String key) {
        VaultAccount account = view.account;
        VaultAccount.Entry entry = account.entries.get(key);
        if (entry == null) return;
        if (entry.amount > 0) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_NOT_EMPTY);
            return;
        }
        UUID viewerId = player.getUniqueId();
        storage.removeEmpty(account.owner, key).whenComplete((removed, error) -> BagStorage.runSync(() -> {
            Player now = Bukkit.getPlayer(viewerId);
            if (error != null) {
                BagStorage.logSevere("無法移除" + kind.label + "物品: " + account.owner + " " + key, error);
                if (now != null) Message.sendPrefix(now, Message.MESSAGE__ERROR, "無法移除");
                return;
            }
            if (!removed) {
                if (now != null) Message.sendPrefix(now, Message.MESSAGE__ORE_NOT_EMPTY);
                refresh(account);
                return;
            }
            VaultAccount.Entry current = account.entries.get(key);
            if (current != null && current.amount == 0) account.entries.remove(key);
            if (now != null) Message.sendPrefix(now, kind.removed);
            refreshViews(account);
        }));
    }

    private void toggleEnabled(Player player, View view) {
        VaultAccount account = view.account;
        account.enabled = !account.enabled;
        boolean enabled = account.enabled;
        storage.setEnabled(account.owner, enabled).exceptionally(error -> {
            BagStorage.logSevere("無法更新" + kind.label + "開關: " + account.owner, error);
            return null;
        });
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        refreshViews(account);
    }

    private boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastAction.get(player.getUniqueId());
        if (last != null && now - last < ACTION_COOLDOWN_MS) return false;
        lastAction.put(player.getUniqueId(), now);
        return true;
    }

    // ---------- 管理員 ----------

    /**
     * @param key 已經用 {@link VaultKind#parseKey} 轉好的鍵
     */
    public void adminAdd(CommandSender sender, String targetName, String key, long amount) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            if (!checkTarget(sender, targetName, target, error)) return;
            // 新種類：礦物倉庫補發的不自動收（玩家自己決定），副本素材本來就是撿到全收
            boolean autoForNew = kind.collectNewKinds();
            VaultAccount account = accounts.get(target.uuid());
            if (account != null) {
                account.add(key, amount, autoForNew);
                account.sent(Map.of(key, amount));
                refreshViews(account);
            }
            storage.add(new VaultRepository.Change(target.uuid(), viewer, sender.getName(), kind.repository.sourceAdmin,
                    Map.of(key, amount), autoForNew, true));
            Message.sendPrefix(sender, kind.adminAdded, target.name(), key, amount);
        }));
    }

    public void adminTake(CommandSender sender, String targetName, String key, long amount) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            if (!checkTarget(sender, targetName, target, error)) return;
            VaultAccount account = accounts.get(target.uuid());
            if (account != null) flush(account);
            storage.take(target.uuid(), key, amount, viewer, sender.getName())
                    .whenComplete((taken, takeError) -> BagStorage.runSync(() -> {
                        if (takeError != null) {
                            BagStorage.logSevere("無法扣除" + kind.label + ": " + target.name() + " " + key, takeError);
                            Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法扣除");
                            return;
                        }
                        if (account != null) {
                            VaultAccount.Entry entry = account.entries.get(key);
                            if (entry != null) entry.amount = Math.max(0, entry.amount - taken);
                            refreshViews(account);
                        }
                        Message.sendPrefix(sender, kind.adminTaken, target.name(), key, taken);
                    }));
        }));
    }

    /**
     * @param space null 表示改回預設
     */
    public void adminSpace(CommandSender sender, String targetName, Long space) {
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            if (!checkTarget(sender, targetName, target, error)) return;
            storage.setSpace(target.uuid(), space).whenComplete((ignored, setError) -> BagStorage.runSync(() -> {
                if (setError != null) {
                    BagStorage.logSevere("無法設定" + kind.label + "容量: " + target.name(), setError);
                    Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法設定容量");
                    return;
                }
                VaultAccount account = accounts.get(target.uuid());
                if (account != null) {
                    account.space = space;
                    refreshViews(account);
                }
                if (space == null) {
                    Message.sendPrefix(sender, kind.adminSpaceDefault, target.name());
                } else {
                    Message.sendPrefix(sender, kind.adminSpace, target.name(), String.format("%,d", space));
                }
            }));
        }));
    }

    private static boolean checkTarget(CommandSender sender, String targetName, Target target, Throwable error) {
        if (error != null) {
            BagStorage.logSevere("無法查詢玩家: " + targetName, error);
            Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法查詢玩家");
            return false;
        }
        if (target == null) {
            Message.sendPrefix(sender, Message.MESSAGE__NO_PLAYER);
            return false;
        }
        return true;
    }

    /**
     * 名稱找玩家：在線 → 背包的 player_info → 伺服器快取過的離線玩家
     */
    private static CompletableFuture<Target> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(new Target(online.getUniqueId(), online.getName()));
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return BagStorage.findByName(name).thenApply(info -> {
            if (info != null) return new Target(info.getPlayerUUID(), info.getPlayerName());
            if (cached != null) return new Target(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name);
            return null;
        });
    }

    // ---------- 設定與工具 ----------

    /**
     * 玩家照權限能放幾件：Config.yml &lt;ORE|MATERIAL&gt;.SPACE 的每個鍵對應 treasure-bag.&lt;鍵&gt;，有好幾個取最大，一個都沒有是 0
     */
    long tierSpace(Player player) {
        long best = 0;
        for (Map.Entry<String, Long> entry : kind.spaceTable().entrySet()) {
            if (player.hasPermission("treasure-bag." + entry.getKey())) best = Math.max(best, entry.getValue());
        }
        return best;
    }

    /**
     * 身上（不含裝備欄、副手）還放得下多少這種物品
     */
    private static int room(PlayerInventory inventory, ItemStack sample) {
        InventoryRoom room = new InventoryRoom(inventory);
        return room.fit(sample, room.capacity(sample.getMaxStackSize()));
    }

    /**
     * 一次挖掘掉出好幾堆時，算身上還放得下多少：空格會被前面的堆用掉，要一起扣
     */
    private static final class InventoryRoom {
        private final ItemStack[] contents;
        private final Map<Material, Integer> partial = new EnumMap<>(Material.class);
        /** 有特殊資料的物品各自剩多少疊上去的空間（只能疊到一模一樣的物品上，用 isSimilar 找） */
        private final List<Map.Entry<ItemStack, Integer>> special = new ArrayList<>();
        private int empty;

        InventoryRoom(PlayerInventory inventory) {
            contents = inventory.getStorageContents();
            for (ItemStack item : contents) {
                if (item == null || item.getType().isAir()) empty++;
            }
        }

        /**
         * 整個身上最多放得下幾件（每格 max 件）
         */
        int capacity(int max) {
            return contents.length * max;
        }

        /**
         * 放得下的件數（這部分照原版掉在地上讓玩家撿），同時扣掉用掉的空間
         * 「疊上去的空間」用掉多少要記住：原版物品照種類記，有特殊資料的照物品本身記
         */
        int fit(ItemStack stack, int amount) {
            int max = stack.getMaxStackSize();
            boolean plain = OreKind.isPlain(stack);
            Map.Entry<ItemStack, Integer> specialEntry = plain ? null
                    : special.stream().filter(entry -> entry.getKey().isSimilar(stack)).findFirst().orElse(null);
            Integer space = plain ? partial.get(stack.getType()) : specialEntry != null ? specialEntry.getValue() : null;
            if (space == null) {
                space = 0;
                for (ItemStack item : contents) {
                    if (item != null && item.isSimilar(stack)) space += Math.max(0, max - item.getAmount());
                }
            }
            int fromPartial = Math.min(space, amount);
            int rest = amount - fromPartial;
            int slots = Math.min(empty, (rest + max - 1) / max);
            int fromEmpty = Math.min(rest, slots * max);
            empty -= slots;
            int left = space - fromPartial + slots * max - fromEmpty;
            if (plain) {
                partial.put(stack.getType(), left);
            } else if (specialEntry != null) {
                specialEntry.setValue(left);
            } else {
                special.add(new java.util.AbstractMap.SimpleEntry<>(stack.clone(), left));
            }
            return fromPartial + fromEmpty;
        }
    }
}
