package org.weiwei.treasureBag.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.UUID;

/**
 * 隨身背包
 */

@Getter
@Setter
public class PlayerInfo {

    private Long id;
    private UUID playerUUID;
    private String playerName;
    private Date createdAt;
    private Date lastUpdateAt;

}
