package org.weiwei.treasureBag.customGui;

import lombok.Getter;
import org.weiwei.treasureBag.util.Message;

/**
 * ################################################
 * #                                              #
 * #             GUI 介面類型的列舉類型              #
 * #                                              #
 * ################################################
 */

@Getter
public enum GuiType {


    // 查看禮包
    VIEW_MENU(Message.MENU_TITLE__VIEW_MENU, 54),

    // 查看特定禮包
    ADMIN_MENU(Message.MENU_TITLE__ADMIN_MENU, 54),

    ;


    final String defTitle; // 禮包名稱
    final int size;

    GuiType(Message message, int size) {
        this.defTitle = Message.getMsg(message);
        this.size = size;
    }


}
