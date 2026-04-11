package org.weiwei.treasureBag.customGui.event;


import org.weiwei.treasureBag.customGui.CustomGui;

/**
 * ####################################################
 * #                                                  #
 * #                GUI 點擊事件處理器                  #
 * #                                                  #
 * ####################################################
 */

public interface GuiClickInterface {

    /**
     * 設置其他物品
     */
    void setOtherItem(CustomGui altarGui);

    /**
     * 設置默認物品
     */
    void setDefaultItem(CustomGui altarGui);

    /**
     * 點擊刷新 GUI
     */
    void refresh(CustomGui altarGui);
}
