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
        int topSize = event.getView().getTopInventory().getSize();
        boolean readOnly = PlayerBagService.isReadOnly(player);
        if (event.isShiftClick() && rawSlot >= topSize) {
            event.setCancelled(true);
            if (!readOnly) moveShiftClickedItem(player, gui, event);
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) {
            // 唯讀時連雙擊收集也擋，避免從背包裡把同種物品吸到游標上
            if (readOnly && event.getClick() == ClickType.DOUBLE_CLICK) event.setCancelled(true);
            return;
        }

        // 控制列（整理、一鍵存入、頁籤、資訊）：一律取消，交給 service 判斷按了什麼
        if (PlayerBagService.isControlSlot(rawSlot)) {
            event.setCancelled(true);
            // 只認滑鼠點擊，數字鍵、丟棄鍵按到控制列不觸發按鈕
            if (event.getClick().isMouseClick()) PlayerBagService.clickControl(player, gui, rawSlot, event.isRightClick());
            return;
        }

        if (readOnly) {
            event.setCancelled(true);
            return;
        }

        if (isPuttingPortableContainer(event)) {
            event.setCancelled(true);
            Message.sendPrefix(player, Message.MESSAGE__BAG_CONTAINER_FORBIDDEN);
            return;
        }

        if (PlayerBagService.isLockedContentSlot(player, gui, rawSlot)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        CustomGui gui = CustomGui.isCustomGui(event.getView().getTopInventory(), GuiType.BAG_MENU);
        if (gui == null) return;

        boolean readOnly = PlayerBagService.isReadOnly(player);
        boolean dragToBag = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= event.getView().getTopInventory().getSize()) continue;
            dragToBag = true;
            if (readOnly || PlayerBagService.isControlSlot(rawSlot) || PlayerBagService.isLockedContentSlot(player, gui, rawSlot)) {
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
