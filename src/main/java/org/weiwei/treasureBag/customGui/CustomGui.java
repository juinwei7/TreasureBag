package org.weiwei.treasureBag.customGui;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.weiwei.treasureBag.customGui.event.GuiClickInterface;

import java.util.List;
import java.util.UUID;

/**
 * ################################################
 * #                                              #
 * #             GUI 介面類型的列舉類型              #
 * #                                              #
 * ################################################
 */

@Getter
public class CustomGui implements InventoryHolder {


    @Getter
    private final Inventory inventory; // GUI 介面
    private final Player player; // 玩家
    private final GuiType guiType; // GUI 類型

    @Setter
    private UUID tagerUuid;

    @Setter
    private int page;

    @Setter
    private int maxSlot;



    public CustomGui(GuiType type, Player player, String title) {
        this.guiType = type;
        this.player = player;
        if (title == null || title.isEmpty()) title = type.getDefTitle();
        this.inventory = Bukkit.createInventory(this, type.getSize(), title);
    }


    public boolean isGuiType(GuiType type) {
        return this.guiType == type;
    }

    public void open(GuiClickInterface guiClickInterface) {
        guiClickInterface.setDefaultItem(this);
        guiClickInterface.setOtherItem(this);
        player.openInventory(inventory);
    }

    /// 檢查是否為指定類型的 CustomGui
    public static CustomGui isCustomGui(Inventory inventory, GuiType guiType) {
        if (inventory == null || !(inventory.getHolder() instanceof CustomGui customGui)) return null;
        if (customGui.getGuiType() != guiType) return null;
        return customGui;
    }

    /// 檢查是否為指定類型的 CustomGui，並且在指定的類型列表中
    public static CustomGui isCustomGui(Inventory inventory, List<GuiType> guiTypeList) {
        if (inventory == null || !(inventory.getHolder() instanceof CustomGui customGui)) return null;
        if (guiTypeList == null || guiTypeList.isEmpty()) return customGui;
        if (!guiTypeList.contains(customGui.getGuiType())) return null;
        return customGui;
    }



}
