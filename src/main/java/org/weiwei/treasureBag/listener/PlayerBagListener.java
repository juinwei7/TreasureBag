package org.weiwei.treasureBag.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.weiwei.treasureBag.customGui.CustomGui;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.service.PlayerBagService;
import org.weiwei.treasureBag.util.Message;

public class PlayerBagListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        int rawSlot = event.getRawSlot();
        if (event.isShiftClick() && rawSlot >= event.getView().getTopInventory().getSize()) {
            event.setCancelled(true);
            moveShiftClickedItem(player, gui, event);
            return;
        }

        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;

        if (isPuttingPortableContainer(event)) {
            event.setCancelled(true);
            Message.sendPrefix(player, Message.MESSAGE__BAG_CONTAINER_FORBIDDEN);
            return;
        }

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
        if (!(event.getWhoClicked() instanceof Player player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        boolean dragToBag = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= event.getView().getTopInventory().getSize()) continue;
            dragToBag = true;
            if (PlayerBagService.isControlSlot(rawSlot) || PlayerBagService.isLockedContentSlot(gui, rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }

        if (dragToBag && PlayerBagService.isPortableContainer(event.getOldCursor())) {
            event.setCancelled(true);
            Message.sendPrefix(player, Message.MESSAGE__BAG_CONTAINER_FORBIDDEN);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        PlayerBagService.close(player, gui);
    }

    private boolean isPuttingPortableContainer(InventoryClickEvent event) {
        if (PlayerBagService.isPortableContainer(event.getCursor())) return true;

        if (event.getClick() == ClickType.NUMBER_KEY) {
            int hotbarButton = event.getHotbarButton();
            if (hotbarButton < 0) return false;

            ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbarButton);
            return PlayerBagService.isPortableContainer(hotbarItem);
        }

        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return PlayerBagService.isPortableContainer(event.getWhoClicked().getInventory().getItemInOffHand());
        }

        return false;
    }

    private void moveShiftClickedItem(Player player, CustomGui gui, InventoryClickEvent event) {
        ItemStack currentItem = event.getCurrentItem();
        if (currentItem == null || currentItem.getType().isAir()) return;

        if (PlayerBagService.isPortableContainer(currentItem)) {
            Message.sendPrefix(player, Message.MESSAGE__BAG_CONTAINER_FORBIDDEN);
            return;
        }

        int movedAmount = PlayerBagService.moveItemToBag(player, gui, currentItem);
        if (movedAmount <= 0) return;

        int remainingAmount = currentItem.getAmount() - movedAmount;
        if (remainingAmount <= 0) {
            event.setCurrentItem(null);
            return;
        }

        ItemStack remainingItem = currentItem.clone();
        remainingItem.setAmount(remainingAmount);
        event.setCurrentItem(remainingItem);
    }
}
