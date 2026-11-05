package org.weiwei.treasureBag.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.service.OreService;

/**
 * 礦物倉庫：挖方塊自動收集、進出伺服器讀寫、倉庫畫面的點擊
 */
public class OreListener implements Listener {

    /** 等其他外掛改完掉落物（例如加倍、換成自製物品）再收，取消掉的挖掘不收 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        OreService.collect(event);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        OreService.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        OreService.quit(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.ORE_MENU) == null) return;
        OreService.click(player, event);
    }

    /** 倉庫畫面的格子都是顯示用的，拖曳一律擋 */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.ORE_MENU) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (CustomGui.isCustomGui(event.getInventory(), GuiType.ORE_MENU) == null) return;
        OreService.close(player, event.getInventory());
    }
}
