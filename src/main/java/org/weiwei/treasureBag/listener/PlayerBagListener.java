package org.weiwei.treasureBag.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.service.PlayerBagService;

public class PlayerBagListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        int rawSlot = event.getRawSlot();
        if (event.isShiftClick() && rawSlot >= event.getView().getTopInventory().getSize()) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;

        if (PlayerBagService.isControlSlot(rawSlot) || PlayerBagService.isLockedContentSlot(gui, rawSlot)) {
            event.setCancelled(true);
        }

        if (rawSlot == PlayerBagService.PREVIOUS_PAGE_SLOT) {
            PlayerBagService.switchPage(player, gui, gui.getPage() - 1);
            return;
        }

        if (rawSlot == PlayerBagService.NEXT_PAGE_SLOT) {
            PlayerBagService.switchPage(player, gui, gui.getPage() + 1);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= event.getView().getTopInventory().getSize()) continue;
            if (PlayerBagService.isControlSlot(rawSlot) || PlayerBagService.isLockedContentSlot(gui, rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        PlayerBagService.close(player, gui);
    }
}
