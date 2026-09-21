package org.weiwei.treasureBag.command.sub;

import at.pcgamingfreaks.Minepacks.Bukkit.API.Backpack;
import at.pcgamingfreaks.Minepacks.Bukkit.API.Callback;
import at.pcgamingfreaks.Minepacks.Bukkit.API.MinepacksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.weiwei.treasureBag.Main;
import org.weiwei.treasureBag.command.SubCommand;
import org.weiwei.treasureBag.data.PlayerBagRepository;
import org.weiwei.treasureBag.entity.PlayerBag;
import org.weiwei.treasureBag.entity.PlayerInfo;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /treasurebag trade：從 Minepacks 插件把玩家背包資料一次性轉移到 TreasureBag。
 * <p>
 * 逐一（串行）處理資料庫中的每位玩家，避免大量玩家同時觸發 Minepacks 的非同步載入。
 * 讀取玩家清單與寫入 TreasureBag 都放在非同步執行緒，Minepacks 的 getBackpack 回呼在主執行緒。
 */
public class TradeCommand extends SubCommand {

    public TradeCommand() {
        super("trade", Message.MESSAGE__ERROR);
        setPermissionRequired();
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        MinepacksPlugin minepacks = getMinepacks();
        if (minepacks == null) {
            sender.sendMessage("§cMinepacks 插件未找到，無法進行轉移！");
            return;
        }

        // 二次確認：轉移是批次寫入所有玩家背包、難以復原的操作，
        // 必須帶 confirm 參數（或點擊提示）才會真正執行
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sendConfirmPrompt(sender);
            return;
        }

        sender.sendMessage("§7正在從資料庫讀取玩家列表...");

        // 先 async 讀 DB，避免卡主執行緒
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInst(), () -> {
            List<String> uuidStrings = loadTradeUUIDs();

            Bukkit.getScheduler().runTask(Main.getInst(), () -> {
                if (uuidStrings.isEmpty()) {
                    sender.sendMessage("§e資料庫中沒有玩家資料，無須轉移。");
                    return;
                }

                int total = uuidStrings.size();
                sender.sendMessage("§a開始背包資料轉移，共 §f" + total + " §a筆玩家資料...");

                Queue<String> queue = new ArrayDeque<>(uuidStrings);
                PlayerBagRepository repository = new PlayerBagRepository();
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger missingBackpackCount = new AtomicInteger(0);
                AtomicInteger emptyBackpackCount = new AtomicInteger(0);
                AtomicInteger failCount = new AtomicInteger(0);

                processNext(sender, queue, repository, minepacks,
                        total, successCount, missingBackpackCount, emptyBackpackCount, failCount);
            });
        });
    }

    /** 逐一取出 Queue 中的 UUID，串行處理，完成後才處理下一筆 */
    private void processNext(CommandSender sender, Queue<String> queue,
                             PlayerBagRepository repository, MinepacksPlugin minepacks,
                             int total, AtomicInteger success, AtomicInteger missingBackpack,
                             AtomicInteger emptyBackpack, AtomicInteger fail) {
        if (queue.isEmpty()) {
            sender.sendMessage("§a背包轉移完成！§f成功: " + success.get()
                    + " §7無 Minepacks 背包: " + missingBackpack.get()
                    + " §7空背包: " + emptyBackpack.get()
                    + " §c失敗: " + fail.get());
            return;
        }

        int done = total - queue.size();
        if (done > 0 && done % 500 == 0) {
            sender.sendMessage("§7進度: §f" + done + " §7/ §f" + total);
        }

        String uuidStr = queue.poll();
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr.trim());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c無效的 UUID: §f" + uuidStr);
            fail.incrementAndGet();
            // 延遲 1 tick，防止同 tick 內同步遞迴
            scheduleNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail);
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

        // getBackpack 非同步載入，callback 回主執行緒。
        // onResult / onFail 都必須推進佇列，否則單筆失敗就會讓整個轉移卡死。
        minepacks.getBackpack(offlinePlayer, new Callback<>() {
            @Override
            public void onResult(Backpack backpack) {
                if (backpack == null) {
                    Main.getInst().getLogger().warning("[Trade] 找不到 " + uuid + " 的背包，跳過。");
                    missingBackpack.incrementAndGet();
                    scheduleNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail);
                    return;
                }

                List<ItemStack> itemsToTransfer = new ArrayList<>();
                for (ItemStack item : backpack.getInventory().getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        itemsToTransfer.add(item.clone());
                    }
                }

                if (itemsToTransfer.isEmpty()) {
                    emptyBackpack.incrementAndGet();
                    scheduleNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail);
                    return;
                }

                String playerName = offlinePlayer.getName();

                // DB 操作放 async
                Bukkit.getScheduler().runTaskAsynchronously(Main.getInst(), () -> {
                    try {
                        PlayerInfo playerInfo = repository.getOrCreatePlayerInfo(uuid, playerName);

                        List<PlayerBag> existingBags = repository.loadPlayerBags(playerInfo);
                        Map<Integer, ItemStack> slotMap = new HashMap<>();
                        for (PlayerBag bag : existingBags) {
                            ItemStack item = bag.getItem();
                            if (item != null && !item.getType().isAir()) {
                                slotMap.put(bag.getSolder().intValue(), item);
                            }
                        }

                        int nextSlot = slotMap.keySet().stream()
                                .mapToInt(Integer::intValue).max().orElse(-1) + 1;
                        for (ItemStack item : itemsToTransfer) {
                            while (slotMap.containsKey(nextSlot)) nextSlot++;
                            slotMap.put(nextSlot, item.clone());
                            nextSlot++;
                        }

                        int totalMaxSlot = slotMap.keySet().stream()
                                .mapToInt(Integer::intValue).max().orElse(0) + 1;

                        List<PlayerBag> allBags = new ArrayList<>();
                        for (Map.Entry<Integer, ItemStack> entry : slotMap.entrySet()) {
                            PlayerBag bag = new PlayerBag();
                            bag.setPlayerUUID(uuid);
                            bag.setSolder((long) entry.getKey());
                            bag.setItem(entry.getValue());
                            allBags.add(bag);
                        }

                        repository.saveAllowedPlayerBags(playerInfo, allBags, totalMaxSlot);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        Main.getInst().getLogger().severe("[Trade] 轉移 " + uuid + " 時發生錯誤！");
                        e.printStackTrace();
                        fail.incrementAndGet();
                    } finally {
                        scheduleNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail);
                    }
                });
            }

            @Override
            public void onFail() {
                Main.getInst().getLogger().warning("[Trade] 讀取 " + uuid + " 背包失敗，跳過。");
                fail.incrementAndGet();
                scheduleNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail);
            }
        });
    }

    /** 顯示二次確認提示：說明後果並提供可點擊 / 可輸入的確認方式 */
    private void sendConfirmPrompt(CommandSender sender) {
        sender.sendMessage("§e[背包轉移] §f即將從 Minepacks 匯入所有玩家的背包物品到 TreasureBag。");
        sender.sendMessage("§7物品會附加到既有背包後方（不覆蓋），此操作難以復原，建議在玩家較少時執行。");
        sender.sendMessage(Component.text("§a[點擊此處確認執行]")
                .clickEvent(ClickEvent.runCommand("/treasurebag trade confirm"))
                .hoverEvent(HoverEvent.showText(Component.text("執行 /treasurebag trade confirm 開始轉移")))
                .append(Component.text("　§7或輸入 §f/treasurebag trade confirm")));
    }

    /** 延遲 1 tick 回主執行緒處理下一筆，避免同步遞迴把 Minepacks / DB pool 打爆 */
    private void scheduleNext(CommandSender sender, Queue<String> queue,
                              PlayerBagRepository repository, MinepacksPlugin minepacks,
                              int total, AtomicInteger success, AtomicInteger missingBackpack,
                              AtomicInteger emptyBackpack, AtomicInteger fail) {
        Bukkit.getScheduler().runTaskLater(Main.getInst(), () ->
                processNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail), 1L);
    }

    private List<String> loadTradeUUIDs() {
        PlayerBagRepository repository = new PlayerBagRepository();
        List<PlayerInfo> playerInfos = repository.loadPlayerInfos();
        List<String> uuids = new ArrayList<>();
        for (PlayerInfo info : playerInfos) {
            if (info.getPlayerUUID() != null) {
                uuids.add(info.getPlayerUUID().toString());
            }
        }
        return uuids;
    }

    public static MinepacksPlugin getMinepacks() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Minepacks");
        if (!(plugin instanceof MinepacksPlugin)) return null;
        return (MinepacksPlugin) plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && "confirm".startsWith(args[0].toLowerCase())) {
            return List.of("confirm");
        }
        return List.of();
    }
}
