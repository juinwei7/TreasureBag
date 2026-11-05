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

    /**
     * MONITOR：其他外掛都改完掉落物（加倍、換成自製物品）、也確定沒被取消才收。
     * 在更早的階段收的話，後面的外掛取消掉落時倉庫已經記了數量，等於憑空多出物品
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
