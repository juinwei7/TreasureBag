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
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.OreRepository;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 礦物倉庫（主執行緒）：挖方塊自動收集、介面上的存取、管理員調整
 * <p>
 * 從 ExtraStorage 搬過來，資料改放背包的中央資料庫，每個分流共用同一個倉庫。
 * 算的是件數不是格子：每人預設 {@link #DEFAULT_SPACE} 件（Config.yml ORE.SPACE，/bag oreadmin space 個別調整）。
 * 只收沒有任何特殊資料的原版物品（改名、附魔、損耗、自製物品都不收），避免自製物品存進去、拿出來變成原版的。
 * <p>
 * 數量的正確性靠資料庫的加減（見 {@link OreRepository}），這台的 {@link OreAccount} 只是畫面與容量判斷用的快取；
 * 每次打開畫面都重讀一次，換分流時上一台還沒送完的數量也會補上。
 */
public final class OreService {

    private static final long FLUSH_PERIOD_TICKS = 20L * 5;
    private static final long FULL_NOTICE_MS = 30_000;
    private static final long ACTION_COOLDOWN_MS = 200;
    private static final long DEFAULT_SPACE = 100_000;
    private static final int DEFAULT_MAX_KINDS = 135;
    /** Config.yml 沒寫 ORE.DEFAULT_LIST 時新玩家的清單：沿用 ExtraStorage 的白名單，補上青金石 */
    private static final List<String> DEFAULT_LIST = List.of(
            "COAL_BLOCK", "LAPIS_BLOCK", "REDSTONE_BLOCK", "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK",
            "COAL", "REDSTONE", "IRON_ORE", "GOLD_ORE", "DIAMOND", "EMERALD", "LAPIS_LAZULI",
            "RAW_IRON", "RAW_COPPER", "RAW_GOLD", "RAW_IRON_BLOCK", "RAW_COPPER_BLOCK", "RAW_GOLD_BLOCK");
    private static final List<String> DEFAULT_BLACKLIST = List.of("BEDROCK");

    /** 這台在線玩家的倉庫 */
    private static final Map<UUID, OreAccount> ACCOUNTS = new HashMap<>();
    /** 觀看者 → 開著的倉庫畫面 */
    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static BukkitTask flushTask;

    private OreService() {
    }

    /**
     * 開著的倉庫畫面
     */
    private static final class View {
        final OreAccount account;
        final boolean readOnly;
        final Inventory inventory;
        int page;

        View(OreAccount account, boolean readOnly, Inventory inventory) {
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
    public static void start() {
        OreStorage.recoverFiles();
        flushTask = Bukkit.getScheduler().runTaskTimer(Main.getInst(), () -> {
            ACCOUNTS.values().forEach(OreService::flush);
            OreStorage.retryUnsaved();
        }, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
        Bukkit.getOnlinePlayers().forEach(OreService::join);
    }

    /**
     * 外掛停用：在 PlayerBagService.shutdown（等佇列做完、關掉 IO 執行緒）之後呼叫，還沒送出的直接寫入
     */
    public static void shutdown() {
        if (flushTask != null) flushTask.cancel();
        for (UUID viewer : new ArrayList<>(VIEWS.keySet())) {
            VIEWS.remove(viewer);
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) player.closeInventory();
        }
        Map<UUID, Map<String, Long>> pending = new HashMap<>();
        ACCOUNTS.forEach((owner, account) -> {
            Map<String, Long> deltas = account.drainPending();
            if (!deltas.isEmpty()) pending.put(owner, deltas);
        });
        ACCOUNTS.clear();
        LAST_ACTION.clear();
        OreStorage.shutdown(pending);
    }

    public static void join(Player player) {
        OreAccount account = new OreAccount(player.getUniqueId());
        ACCOUNTS.put(player.getUniqueId(), account);
        refresh(account);
    }

    public static void quit(Player player) {
        UUID id = player.getUniqueId();
        VIEWS.remove(id);
        LAST_ACTION.remove(id);
        OreAccount account = ACCOUNTS.remove(id);
        if (account != null) flush(account);
    }

    /**
     * 匯入舊資料後，在線玩家重讀一次
     */
    public static void reloadOnline() {
        ACCOUNTS.values().forEach(OreService::refresh);
    }

    // ---------- 開畫面 ----------

    public static void open(Player player) {
        UUID id = player.getUniqueId();
        OreAccount account = ACCOUNTS.computeIfAbsent(id, OreAccount::new);
        whenLoaded(account, ok -> {
            Player now = Bukkit.getPlayer(id);
            if (now == null) return;
            if (!ok) {
                Message.sendPrefix(now, Message.MESSAGE__ORE_LOAD_FAILED);
                return;
            }
            show(now, account, now.getName(), false);
        });
    }

    /**
     * 管理員查看別人的倉庫（唯讀；要調整用 /bag oreadmin）
     */
    public static void openOther(Player viewer, String targetName) {
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
            OreAccount account = ACCOUNTS.containsKey(target.uuid()) ? ACCOUNTS.get(target.uuid()) : new OreAccount(target.uuid());
            whenLoaded(account, ok -> {
                Player now = Bukkit.getPlayer(viewerId);
                if (now == null) return;
                if (!ok) {
                    Message.sendPrefix(now, Message.MESSAGE__ORE_LOAD_FAILED);
                    return;
                }
                show(now, account, target.name(), true);
            });
        }));
    }

    private static void show(Player player, OreAccount account, String ownerName, boolean readOnly) {
        boolean isOwner = player.getUniqueId().equals(account.owner);
        CustomGui gui = new CustomGui(GuiType.ORE_MENU, player, OreMenu.title(player, ownerName, isOwner));
        View view = new View(account, readOnly, gui.getInventory());
        OreMenu.fill(view.inventory, player, account, 0, readOnly);
        // 先開畫面再登記：開新畫面會觸發舊畫面的關閉事件，那時候不能把新的登記清掉
        player.openInventory(view.inventory);
        VIEWS.put(player.getUniqueId(), view);
    }

    private static void render(Player viewer, View view) {
        view.page = Math.max(0, Math.min(view.page, OreMenu.maxPage(view.account) - 1));
        OreMenu.fill(view.inventory, viewer, view.account, view.page, view.readOnly);
    }

    private static void refreshViews(OreAccount account) {
        VIEWS.forEach((viewer, view) -> {
            if (view.account != account) return;
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) render(player, view);
        });
    }

    public static void close(Player player, Inventory inventory) {
        View view = VIEWS.get(player.getUniqueId());
        if (view != null && view.inventory == inventory) VIEWS.remove(player.getUniqueId());
    }

    // ---------- 讀取 ----------

    private static void whenLoaded(OreAccount account, Consumer<Boolean> then) {
        account.waiting.add(then);
        refresh(account);
    }

    /**
     * 重讀資料庫；讀取中就不再排，讀完一起處理 waiting
     */
    private static void refresh(OreAccount account) {
        if (account.afterReload != null) return;
        flush(account);
        account.afterReload = new EnumMap<>(Material.class);
        UUID owner = account.owner;
        OreStorage.load(owner, defaultList()).whenComplete((result, error) -> BagStorage.runSync(() -> {
            Map<Material, Long> sent = account.afterReload;
            account.afterReload = null;
            List<Consumer<Boolean>> waiting = new ArrayList<>(account.waiting);
            account.waiting.clear();
            if (error != null) {
                BagStorage.logSevere("無法讀取礦物倉庫: " + owner, error);
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
     * 挖方塊掉出來的東西：身上放不下的部分（Config.yml ORE.ONLY_WHEN_FULL 關掉就是全部）收進倉庫
     */
    public static void collect(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;
        OreAccount account = ACCOUNTS.get(player.getUniqueId());
        if (account == null || !account.loaded || !account.enabled || event.getItems().isEmpty()) return;

        InventoryRoom room = onlyWhenFull() ? new InventoryRoom(player.getInventory()) : null;
        long free = account.free();
        Map<Material, Long> stored = new EnumMap<>(Material.class);
        boolean full = false;
        for (Iterator<Item> iterator = event.getItems().iterator(); iterator.hasNext(); ) {
            Item drop = iterator.next();
            ItemStack stack = drop.getItemStack();
            Material material = stack.getType();
            OreAccount.Entry entry = account.entries.get(material);
            if (entry == null || !entry.auto || !isPlain(stack)) continue;

            int amount = stack.getAmount();
            int overflow = room == null ? amount : amount - room.fit(material, amount);
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
            entry.amount += store;
            account.pending.merge(material, (long) store, Long::sum);
            stored.merge(material, (long) store, Long::sum);
        }

        if (!stored.isEmpty()) {
            player.sendActionBar(storedBar(stored));
        } else if (full) {
            long now = System.currentTimeMillis();
            if (now - account.lastFullNotice >= FULL_NOTICE_MS) {
                account.lastFullNotice = now;
                player.sendActionBar(Component.text("礦物倉庫滿了（" + String.format("%,d", account.total())
                        + "/" + String.format("%,d", account.space()) + "），挖到的東西會掉在地上", NamedTextColor.RED));
            }
        }
    }

    private static Component storedBar(Map<Material, Long> stored) {
        TextComponent.Builder bar = Component.text();
        boolean first = true;
        for (Map.Entry<Material, Long> entry : stored.entrySet()) {
            if (!first) bar.append(Component.text("、", NamedTextColor.GRAY));
            bar.append(Component.text("+" + entry.getValue() + " ", NamedTextColor.GREEN))
                    .append(Component.translatable(entry.getKey().translationKey(), NamedTextColor.WHITE));
            first = false;
        }
        return bar.append(Component.text(" 收進礦物倉庫 /bag ore", NamedTextColor.GRAY)).build();
    }

    /**
     * 自動收集還沒送出的部分送進資料庫
     */
    private static void flush(OreAccount account) {
        if (account.pending.isEmpty()) return;
        Map<Material, Long> moved = new EnumMap<>(account.pending);
        Map<String, Long> deltas = account.drainPending();
        account.sent(moved);
        OreStorage.add(new OreRepository.Change(account.owner, null, null, OreRepository.SOURCE_DEPOSIT, deltas, true, false));
    }

    // ---------- 介面操作 ----------

    /**
     * 倉庫畫面上的點擊（listener 已確認是倉庫畫面）：一律取消，照點的位置處理
     */
    public static void click(Player player, InventoryClickEvent event) {
        event.setCancelled(true);
        View view = VIEWS.get(player.getUniqueId());
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

        if (rawSlot < OreMenu.PAGE_SIZE) {
            if (view.readOnly) return;
            Material material = OreMenu.materialAt(view.account, view.page, rawSlot);
            if (material == null) return;
            switch (event.getClick()) {
                case LEFT -> withdraw(player, view, material, false);
                case SHIFT_LEFT -> withdraw(player, view, material, true);
                case RIGHT -> depositKind(player, view, material);
                case SHIFT_RIGHT -> toggleAuto(player, view, material);
                case DROP, CONTROL_DROP -> removeEntry(player, view, material);
                default -> {
                }
            }
            return;
        }

        if (!event.getClick().isMouseClick()) return;
        if (rawSlot == OreMenu.ORE_TAB_SLOT) {
            int maxPage = OreMenu.maxPage(view.account);
            if (maxPage <= 1) return;
            view.page = (view.page + (event.isRightClick() ? maxPage - 1 : 1)) % maxPage;
            render(player, view);
            return;
        }
        if (view.readOnly) return;
        if (rawSlot == OreMenu.DEPOSIT_SLOT) {
            depositAll(player, view);
        } else if (rawSlot == OreMenu.TOGGLE_SLOT) {
            toggleEnabled(player, view);
        } else if (rawSlot >= BagMenu.FIRST_TAB_SLOT && rawSlot < BagMenu.FIRST_TAB_SLOT + BagMenu.TAB_COUNT) {
            int page = rawSlot - BagMenu.FIRST_TAB_SLOT;
            if (page < BagMenu.maxPage(PlayerBagService.getMaxSlot(player))) {
                // 不在點擊事件裡換畫面，排到下一個 tick
                Bukkit.getScheduler().runTask(Main.getInst(), () -> PlayerBagService.openAt(player, page));
            }
        }
    }

    private static void withdraw(Player player, View view, Material material, boolean fill) {
        OreAccount account = view.account;
        OreAccount.Entry entry = account.entries.get(material);
        if (entry == null || entry.amount <= 0 || account.withdrawing) return;

        int room = room(player.getInventory(), material);
        if (room <= 0) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_NO_ROOM);
            return;
        }
        long want = Math.min(entry.amount, fill ? room : Math.min(room, material.getMaxStackSize()));

        // 自動收集還沒送出的先送，資料庫才扣得到
        flush(account);
        account.withdrawing = true;
        UUID viewerId = player.getUniqueId();
        String viewerName = player.getName();
        OreStorage.withdraw(account.owner, material.name(), want, viewerId, viewerName)
                .whenComplete((withdrawal, error) -> BagStorage.runSync(() -> {
                    account.withdrawing = false;
                    Player now = Bukkit.getPlayer(viewerId);
                    if (error != null) {
                        BagStorage.logSevere("無法從礦物倉庫取出: " + viewerName + " " + material, error);
                        if (now != null) Message.sendPrefix(now, Message.MESSAGE__ERROR, "無法取出");
                        return;
                    }
                    if (withdrawal == null) {
                        // 這台記的比資料庫多（例如別台剛取走），重讀一次
                        refresh(account);
                        return;
                    }
                    if (!OreStorage.claim(withdrawal)) return;

                    OreAccount.Entry current = account.entries.get(material);
                    if (current != null) current.amount = Math.max(0, current.amount - withdrawal.amount);
                    long left = now == null ? withdrawal.amount : give(now, material, withdrawal.amount);
                    if (left > 0) giveBack(account, viewerId, viewerName, material, left);
                    if (now != null) {
                        long given = withdrawal.amount - left;
                        if (given > 0) {
                            now.playSound(now.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.0f);
                            Message.sendPrefix(now, Message.MESSAGE__ORE_WITHDRAWN, given);
                        }
                        if (left > 0) Message.sendPrefix(now, Message.MESSAGE__ORE_NO_ROOM);
                    }
                    refreshViews(account);
                }));
    }

    /**
     * 取出的東西身上放不下（取出期間背包被塞滿、人下線）：退回倉庫
     */
    private static void giveBack(OreAccount account, UUID viewer, String viewerName, Material material, long amount) {
        account.add(material, amount, true);
        account.sent(Map.of(material, amount));
        OreStorage.add(new OreRepository.Change(account.owner, viewer, viewerName, OreRepository.SOURCE_RETURN,
                Map.of(material.name(), amount), true, true));
    }

    private static long give(Player player, Material material, long amount) {
        long left = amount;
        int max = material.getMaxStackSize();
        while (left > 0) {
            int stack = (int) Math.min(max, left);
            int notAdded = player.getInventory().addItem(new ItemStack(material, stack)).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            left -= stack - notAdded;
            if (notAdded > 0) break;
        }
        return left;
    }

    /**
     * 右鍵：身上（含快捷列）所有這種物品存進去
     */
    private static void depositKind(Player player, View view, Material material) {
        Map<Material, Long> moved = takeFromInventory(player, view.account, 0, 36,
                item -> item.getType() == material && isPlain(item));
        finishDeposit(player, view, moved);
    }

    /**
     * 一鍵存入：身上第 10～36 格（不含快捷列）清單裡有的種類全部存進去
     */
    private static void depositAll(Player player, View view) {
        OreAccount account = view.account;
        Map<Material, Long> moved = takeFromInventory(player, account, 9, 36,
                item -> account.entries.containsKey(item.getType()) && canStore(item.getType()) && isPlain(item));
        finishDeposit(player, view, moved);
    }

    /**
     * 點身上某一格：整疊存進去，新種類加進清單（自動收集開著）
     */
    private static void depositSlot(Player player, View view, int slot) {
        if (slot < 0 || slot >= 36) return;
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || item.getType().isAir()) return;

        OreAccount account = view.account;
        Material material = item.getType();
        if (!canStore(material)) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_FORBIDDEN);
            return;
        }
        if (!isPlain(item)) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_NOT_PLAIN);
            return;
        }
        if (!account.entries.containsKey(material) && account.visible().size() >= maxKinds()) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_TOO_MANY_KINDS, maxKinds());
            return;
        }
        finishDeposit(player, view, takeFromInventory(player, account, slot, slot + 1, stack -> true));
    }

    /**
     * 從身上 [from, to) 格拿走符合條件的物品，最多拿到倉庫裝滿；回傳每種拿了多少
     */
    private static Map<Material, Long> takeFromInventory(Player player, OreAccount account, int from, int to,
                                                         Predicate<ItemStack> filter) {
        PlayerInventory inventory = player.getInventory();
        long free = account.free();
        Map<Material, Long> moved = new EnumMap<>(Material.class);
        for (int slot = from; slot < to && free > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir() || !filter.test(item)) continue;
            int amount = (int) Math.min(item.getAmount(), free);
            free -= amount;
            moved.merge(item.getType(), (long) amount, Long::sum);
            if (amount >= item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - amount);
                inventory.setItem(slot, item);
            }
        }
        return moved;
    }

    private static void finishDeposit(Player player, View view, Map<Material, Long> moved) {
        OreAccount account = view.account;
        if (moved.isEmpty()) {
            if (account.free() <= 0) {
                Message.sendPrefix(player, Message.MESSAGE__ORE_FULL, account.total(), account.space());
            } else {
                Message.sendPrefix(player, Message.MESSAGE__ORE_DEPOSIT_NONE);
            }
            return;
        }

        moved.forEach((material, amount) -> account.add(material, amount, true));
        account.sent(moved);
        Map<String, Long> deltas = new HashMap<>();
        moved.forEach((material, amount) -> deltas.put(material.name(), amount));
        OreStorage.add(new OreRepository.Change(account.owner, player.getUniqueId(), player.getName(),
                OreRepository.SOURCE_DEPOSIT, deltas, true, true));

        long total = moved.values().stream().mapToLong(Long::longValue).sum();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        Message.sendPrefix(player, Message.MESSAGE__ORE_DEPOSITED, total);
        if (account.free() <= 0) Message.sendPrefix(player, Message.MESSAGE__ORE_FULL, account.total(), account.space());
        refreshViews(account);
    }

    private static void toggleAuto(Player player, View view, Material material) {
        OreAccount account = view.account;
        OreAccount.Entry entry = account.entries.get(material);
        if (entry == null) return;
        entry.auto = !entry.auto;
        boolean auto = entry.auto;
        OreStorage.setAutoCollect(account.owner, material.name(), auto).exceptionally(error -> {
            BagStorage.logSevere("無法更新礦物倉庫自動收集: " + account.owner + " " + material, error);
            return null;
        });
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        refreshViews(account);
    }

    /**
     * Q：數量是 0 的種類從清單移除（資料庫裡也要是 0 才刪）
     */
    private static void removeEntry(Player player, View view, Material material) {
        OreAccount account = view.account;
        OreAccount.Entry entry = account.entries.get(material);
        if (entry == null) return;
        if (entry.amount > 0) {
            Message.sendPrefix(player, Message.MESSAGE__ORE_NOT_EMPTY);
            return;
        }
        UUID viewerId = player.getUniqueId();
        OreStorage.removeEmpty(account.owner, material.name()).whenComplete((removed, error) -> BagStorage.runSync(() -> {
            Player now = Bukkit.getPlayer(viewerId);
            if (error != null) {
                BagStorage.logSevere("無法移除礦物倉庫物品: " + account.owner + " " + material, error);
                if (now != null) Message.sendPrefix(now, Message.MESSAGE__ERROR, "無法移除");
                return;
            }
            if (!removed) {
                if (now != null) Message.sendPrefix(now, Message.MESSAGE__ORE_NOT_EMPTY);
                refresh(account);
                return;
            }
            OreAccount.Entry current = account.entries.get(material);
            if (current != null && current.amount == 0) account.entries.remove(material);
            if (now != null) Message.sendPrefix(now, Message.MESSAGE__ORE_REMOVED);
            refreshViews(account);
        }));
    }

    private static void toggleEnabled(Player player, View view) {
        OreAccount account = view.account;
        account.enabled = !account.enabled;
        boolean enabled = account.enabled;
        OreStorage.setEnabled(account.owner, enabled).exceptionally(error -> {
            BagStorage.logSevere("無法更新礦物倉庫開關: " + account.owner, error);
            return null;
        });
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        refreshViews(account);
    }

    private static boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = LAST_ACTION.get(player.getUniqueId());
        if (last != null && now - last < ACTION_COOLDOWN_MS) return false;
        LAST_ACTION.put(player.getUniqueId(), now);
        return true;
    }

    // ---------- 管理員 ----------

    public static void adminAdd(CommandSender sender, String targetName, Material material, long amount) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            if (!checkTarget(sender, targetName, target, error)) return;
            OreAccount account = ACCOUNTS.get(target.uuid());
            if (account != null) {
                account.add(material, amount, false);
                account.sent(Map.of(material, amount));
                refreshViews(account);
            }
            OreStorage.add(new OreRepository.Change(target.uuid(), viewer, sender.getName(), OreRepository.SOURCE_ADMIN,
                    Map.of(material.name(), amount), false, true));
            Message.sendPrefix(sender, Message.MESSAGE__ORE_ADMIN_ADDED, target.name(), material.name(), amount);
        }));
    }

    public static void adminTake(CommandSender sender, String targetName, Material material, long amount) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            if (!checkTarget(sender, targetName, target, error)) return;
            OreAccount account = ACCOUNTS.get(target.uuid());
            if (account != null) flush(account);
            OreStorage.take(target.uuid(), material.name(), amount, viewer, sender.getName())
                    .whenComplete((taken, takeError) -> BagStorage.runSync(() -> {
                        if (takeError != null) {
                            BagStorage.logSevere("無法扣除礦物倉庫: " + target.name() + " " + material, takeError);
                            Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法扣除");
                            return;
                        }
                        if (account != null) {
                            OreAccount.Entry entry = account.entries.get(material);
                            if (entry != null) entry.amount = Math.max(0, entry.amount - taken);
                            refreshViews(account);
                        }
                        Message.sendPrefix(sender, Message.MESSAGE__ORE_ADMIN_TAKEN, target.name(), material.name(), taken);
                    }));
        }));
    }

    /**
     * @param space null 表示改回預設
     */
    public static void adminSpace(CommandSender sender, String targetName, Long space) {
        resolve(targetName).whenComplete((target, error) -> BagStorage.runSync(() -> {
            if (!checkTarget(sender, targetName, target, error)) return;
            OreStorage.setSpace(target.uuid(), space).whenComplete((ignored, setError) -> BagStorage.runSync(() -> {
                if (setError != null) {
                    BagStorage.logSevere("無法設定礦物倉庫容量: " + target.name(), setError);
                    Message.sendPrefix(sender, Message.MESSAGE__ERROR, "無法設定容量");
                    return;
                }
                OreAccount account = ACCOUNTS.get(target.uuid());
                if (account != null) {
                    account.space = space;
                    refreshViews(account);
                }
                Message.sendPrefix(sender, Message.MESSAGE__ORE_ADMIN_SPACE, target.name(),
                        space == null ? "預設 " + String.format("%,d", defaultSpace()) : String.format("%,d", space));
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

    static long defaultSpace() {
        return ConfigManager.getConfig() != null ? ConfigManager.getConfig().getLong("ORE.SPACE", DEFAULT_SPACE) : DEFAULT_SPACE;
    }

    private static int maxKinds() {
        return ConfigManager.getConfig() != null ? ConfigManager.getConfig().getInt("ORE.MAX_KINDS", DEFAULT_MAX_KINDS) : DEFAULT_MAX_KINDS;
    }

    private static boolean onlyWhenFull() {
        return ConfigManager.getConfig() == null || ConfigManager.getConfig().getBoolean("ORE.ONLY_WHEN_FULL", true);
    }

    private static List<String> defaultList() {
        List<String> configured = ConfigManager.getConfig() != null ? ConfigManager.getConfig().getStringList("ORE.DEFAULT_LIST") : List.of();
        List<String> list = new ArrayList<>();
        for (String name : configured.isEmpty() ? DEFAULT_LIST : configured) {
            Material material = Material.matchMaterial(name);
            if (material != null && canStore(material)) list.add(material.name());
        }
        return list;
    }

    public static boolean canStore(Material material) {
        if (material == null || material.isAir() || !material.isItem()) return false;
        List<String> blacklist = ConfigManager.getConfig() != null && ConfigManager.getConfig().isList("ORE.BLACKLIST")
                ? ConfigManager.getConfig().getStringList("ORE.BLACKLIST") : DEFAULT_BLACKLIST;
        return blacklist.stream().noneMatch(name -> name.equalsIgnoreCase(material.name()));
    }

    /**
     * 跟全新的同種物品一模一樣（沒改名、沒附魔、沒損耗、沒有自製物品的資料）
     */
    static boolean isPlain(ItemStack item) {
        return item != null && !item.getType().isAir() && item.isSimilar(new ItemStack(item.getType()));
    }

    /**
     * 身上（不含裝備欄、副手）還放得下多少這種物品
     */
    private static int room(PlayerInventory inventory, Material material) {
        int max = material.getMaxStackSize();
        ItemStack plain = new ItemStack(material);
        int room = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) room += max;
            else if (item.isSimilar(plain)) room += Math.max(0, max - item.getAmount());
        }
        return room;
    }

    /**
     * 一次挖掘掉出好幾堆時，算身上還放得下多少：空格會被前面的堆用掉，要一起扣
     */
    private static final class InventoryRoom {
        private final ItemStack[] contents;
        private final Map<Material, Integer> partial = new EnumMap<>(Material.class);
        private int empty;

        InventoryRoom(PlayerInventory inventory) {
            contents = inventory.getStorageContents();
            for (ItemStack item : contents) {
                if (item == null || item.getType().isAir()) empty++;
            }
        }

        /**
         * 放得下的件數（這部分照原版掉在地上讓玩家撿）
         */
        int fit(Material material, int amount) {
            int max = material.getMaxStackSize();
            Integer space = partial.get(material);
            if (space == null) {
                space = 0;
                ItemStack plain = new ItemStack(material);
                for (ItemStack item : contents) {
                    if (item != null && item.isSimilar(plain)) space += Math.max(0, max - item.getAmount());
                }
            }
            int fromPartial = Math.min(space, amount);
            int rest = amount - fromPartial;
            int slots = Math.min(empty, (rest + max - 1) / max);
            int fromEmpty = Math.min(rest, slots * max);
            empty -= slots;
            partial.put(material, space - fromPartial + slots * max - fromEmpty);
            return fromPartial + fromEmpty;
        }
    }
}
