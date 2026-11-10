package org.weiwei.treasureBag.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.service.VaultService;

/**
 * 倉庫：礦物挖方塊自動收集、副本素材撿起來自動收集、進出伺服器讀寫、倉庫畫面的點擊
 */
public class VaultListener implements Listener {

    /**
     * MONITOR：其他外掛都改完掉落物（加倍、換成自製物品）、也確定沒被取消才收。
     * 在更早的階段收的話，後面的外掛取消掉落時倉庫已經記了數量，等於憑空多出物品
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        VaultService.ORE.collect(event);
    }

    /**
     * 撿起副本素材（身上放得下）。收的時候要取消撿起事件，所以用 HIGHEST 不用 MONITOR；
     * ignoreCancelled：別的外掛擋下的撿起（保護掉落物之類）不碰。
     * 放不下一部分時這裡看到的地上物品件數已經被改成「放得下的數量」，整堆收會吞掉放不下的那些，
     * 所以只處理整堆放得下的；放不下的在 {@link #onAttemptPickup} 處理
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getRemaining() > 0) return;
        if (event.getEntity() instanceof Player player) VaultService.MATERIAL.pickup(player, event.getItem(), event, 0);
    }

    /**
     * 撿起副本素材（身上放不下，全部或一部分）：在 EntityPickupItemEvent 之前、件數還沒被改；
     * 整堆都放不下時原版只觸發這個（背包滿時每 tick 都會來）
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        if (event.getRemaining() > 0) VaultService.MATERIAL.pickup(event.getPlayer(), event.getItem(), event, event.getRemaining());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        VaultService.ALL.forEach(service -> service.join(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        VaultService.ALL.forEach(service -> service.quit(event.getPlayer()));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        VaultService service = serviceOf(event.getView().getTopInventory());
        if (service != null) service.click(player, event);
    }

    /** 倉庫畫面的格子都是顯示用的，拖曳一律擋 */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (serviceOf(event.getView().getTopInventory()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        VaultService service = serviceOf(event.getInventory());
        if (service != null) service.close(player, event.getInventory());
    }

    private static VaultService serviceOf(Inventory inventory) {
        return inventory.getHolder() instanceof CustomGui gui ? VaultService.of(gui.getGuiType()) : null;
    }
}
