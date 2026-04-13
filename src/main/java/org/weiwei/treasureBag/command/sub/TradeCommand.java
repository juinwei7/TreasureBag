package org.weiwei.treasureBag.command.sub;

import at.pcgamingfreaks.Minepacks.Bukkit.API.MinepacksPlugin;
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
            Bukkit.getScheduler().runTaskLater(Main.getInst(), () ->
                    processNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail), 1L);
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

        // getBackpack 非同步載入，callback 回主執行緒
        minepacks.getBackpack(offlinePlayer, backpack -> {
            if (backpack == null) {
                Main.getInst().getLogger().warning("[Trade] 找不到 " + uuid + " 的背包，跳過。");
                missingBackpack.incrementAndGet();
                // 延遲 1 tick，避免 null 路徑同步快速遞迴把 Minepacks pool 打爆
                Bukkit.getScheduler().runTaskLater(Main.getInst(), () ->
                        processNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail), 1L);
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
                Bukkit.getScheduler().runTaskLater(Main.getInst(), () ->
                        processNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail), 1L);
                return;
            }

            String playerName = offlinePlayer.getName();

            // DB 操作放 async
            Bukkit.getScheduler().runTaskAsynchronously(Main.getInst(), () -> {
                try {
                    PlayerInfo playerInfo = repository.getOrCreatePlayerInfo(uuid, playerName);
                    if (playerInfo == null) {
                        Main.getInst().getLogger().warning("[Trade] 無法取得 " + uuid + " 的資料，跳過。");
                        fail.incrementAndGet();
                        // 修正 bug：原本只 return 會導致後續玩家卡住
                        Bukkit.getScheduler().runTaskLater(Main.getInst(), () ->
                                processNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail), 1L);
                        return;
                    }

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
                    // 回主執行緒，延遲 1 tick 再繼續下一筆
                    Bukkit.getScheduler().runTaskLater(Main.getInst(), () ->
                            processNext(sender, queue, repository, minepacks, total, success, missingBackpack, emptyBackpack, fail), 1L);
                }
            });
        });
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
        return List.of();
    }
}
