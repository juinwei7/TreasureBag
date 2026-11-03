package org.weiwei.treasureBag.data;

import java.util.UUID;

/**
 * 一筆背包存取紀錄
 *
 * @param viewer     操作的人（可能是查看別人背包的管理員）
 * @param viewerName 操作的人的名稱
 * @param server     分流名稱
 * @param source     來源（{@link BagChanges#SOURCE_CLOSE}、{@link BagChanges#SOURCE_FORCE}）
 * @param material   物品種類
 * @param itemName   物品名稱
 * @param amount     正數放入、負數取出
 * @param createdAt  毫秒時間戳
 */
public record BagLog(UUID viewer, String viewerName, String server, String source,
                     String material, String itemName, int amount, long createdAt) {
}
