package org.weiwei.treasureBag.entity;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.inventory.ItemStack;
import org.weiwei.treasureBag.util.ItemSerialize;

import java.util.UUID;

@Getter
@Setter
public class PlayerBag {

    private UUID playerUUID;
    private Long solder;
    private String serialize;
    private String itemName;


    /**
     * 轉換成物品
     */
    public ItemStack getItem() {
        return ItemSerialize.itemFromBase64(serialize);
    }

    /**
     * 設定序列化物品
     */
    public void setItem(ItemStack item) {
        this.serialize = ItemSerialize.itemToBase64(item);
        this.itemName = item.getType().name();
    }


}
