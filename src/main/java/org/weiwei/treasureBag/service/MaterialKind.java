package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.VaultRepository;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 副本素材倉庫（/bag material）：MythicMobs 物品，鍵是物品代號（PDC mythicmobs:type）
 * <p>
 * 只收 Config.yml MATERIAL.ITEMS 列的代號。存的是代號＋件數，取出時用 MythicMobs 照代號重新產生，
 * 所以拿出來的一定是最新版（說明改過的舊素材存進去再拿出來就是新的）。
 * 撿起地上的素材時整堆收進來（身上有空也收），清單裡還沒有的種類第一次收就加進清單；
 * 容量照 VIP 階級，一般玩家是 0（不能用；VIP 到期的還能取出）。
 */
final class MaterialKind extends VaultKind {

    /** Config.yml 沒寫 MATERIAL.SPACE 時的容量：一般玩家不能用 */
    private static final Map<String, Long> DEFAULT_SPACE = Map.of(
            "DEFAULT", 0L, "VIP", 400L, "VIP2", 800L, "VIP3", 1_600L, "VIP4", 3_200L);
    /** Config.yml 沒寫 MATERIAL.ITEMS 時收的素材：主線副本Ⅰ～ⅤⅠⅠ的「副本兌換」與「副本掉落」，畫面照這個順序 */
    private static final List<String> DEFAULT_ITEMS = List.of(
            "st綠紋翡翠", "st獵翳石骨", "st紋麟角", "st嗜血珠", "st萬年木材", "st魔帝鳴骨",
            "冰st靈獸永恆寒冰", "冰st雪神冰霜利爪", "冰st冰霜鍛鐵", "冰st寒冰骨骼", "冰st凍原冰晶", "冰st貝西晶體", "冰st萬年寒冰", "冰st凜冬之心",
            "天st彩光羽翼", "天st天地符文", "天st謎姬之絲", "天st赤米汀球", "天st爾希米聖水", "天st光凌天寒石", "天st聖輝天石", "天st淨光聖羽",
            "月st疾邯蝕磜", "月st玥鷙川杖", "月st駭爾石", "月st憂曜靡奧", "月st通翳钕鹉", "月st赫汝寒撤歧", "月st星敟盶垶梓", "月st赫隕晶石", "月st月藝燄心",
            "璃st璃焰炎火", "璃st霍斯濂圖", "璃st鎵邷金屬", "璃st澳拉辛骼", "璃st淵琳之靜", "璃st克魯穆之眼", "璃st撒托斯之心", "璃st境源晶", "璃st禁忌異章",
            "賽st電能晶體", "賽st機械之眼", "賽st詐欺撲克", "賽st幻象之遁", "賽st微型電漿光纖", "賽st魔幻量子撲克", "賽st源起之核",
            "熔st燼鐵礦", "熔st熔渣結晶", "熔st熔核");

    /** 收的代號（照設定檔順序）；撿東西每 tick 都會查，所以先算好 */
    private Set<String> items = new LinkedHashSet<>(DEFAULT_ITEMS);

    MaterialKind() {
        super(builder());
    }

    private static Builder builder() {
        Builder builder = new Builder();
        builder.section = "MATERIAL";
        builder.title = "副本素材";
        builder.label = "副本素材倉庫";
        builder.command = "material";
        builder.guiType = GuiType.MATERIAL_MENU;
        builder.tabSlot = BagMenu.MATERIAL_TAB_SLOT;
        builder.tabCmd = 883;
        builder.toggleCmd = 871;
        builder.repository = VaultRepository.MATERIAL;
        builder.unsavedFolder = "unsaved-material";
        builder.recoverMarker = "material-recover:";
        builder.loadFailed = Message.MESSAGE__MATERIAL_LOAD_FAILED;
        builder.full = Message.MESSAGE__MATERIAL_FULL;
        builder.withdrawn = Message.MESSAGE__MATERIAL_WITHDRAWN;
        builder.deposited = Message.MESSAGE__MATERIAL_DEPOSITED;
        builder.removed = Message.MESSAGE__MATERIAL_REMOVED;
        builder.noSpace = Message.MESSAGE__MATERIAL_VIP_ONLY;
        builder.unknownItem = Message.MESSAGE__MATERIAL_UNKNOWN_ITEM;
        builder.adminAdded = Message.MESSAGE__MATERIAL_ADMIN_ADDED;
        builder.adminTaken = Message.MESSAGE__MATERIAL_ADMIN_TAKEN;
        builder.adminSpace = Message.MESSAGE__MATERIAL_ADMIN_SPACE;
        builder.adminSpaceDefault = Message.MESSAGE__MATERIAL_ADMIN_SPACE_DEFAULT;
        builder.adminUsage = Message.MESSAGE__MATERIAL_ADMIN_USAGE;
        return builder;
    }

    @Override
    void reload() {
        List<String> configured = ConfigManager.getConfig() != null ? ConfigManager.getConfig().getStringList("MATERIAL.ITEMS") : List.of();
        items = new LinkedHashSet<>(configured.isEmpty() ? DEFAULT_ITEMS : configured);
        MythicItems.clearIcons();
    }

    // ---------- 物品 ----------

    @Override
    String key(ItemStack item) {
        String type = MythicItems.type(item);
        return type != null && items.contains(type) ? type : null;
    }

    @Override
    Message rejectMessage(ItemStack item) {
        return Message.MESSAGE__MATERIAL_FORBIDDEN;
    }

    /** 名單拿掉的代號照樣算：玩家存的東西不能因為改設定就看不到 */
    @Override
    boolean valid(String key) {
        return true;
    }

    @Override
    ItemStack create(String key, int amount) {
        return MythicItems.create(key, amount);
    }

    @Override
    ItemStack icon(String key) {
        return MythicItems.icon(key);
    }

    @Override
    Component name(String key) {
        ItemStack icon = icon(key);
        ItemMeta meta = icon != null ? icon.getItemMeta() : null;
        if (meta == null) return Component.text(key);
        if (meta.hasDisplayName()) return meta.displayName();
        if (meta.hasItemName()) return meta.itemName();
        return Component.translatable(icon.translationKey());
    }

    /** 照設定檔順序；名單拿掉的接在最後面 */
    @Override
    List<String> order(Collection<String> keys) {
        List<String> list = new ArrayList<>();
        for (String id : items) {
            if (keys.contains(id)) list.add(id);
        }
        keys.stream().filter(key -> !items.contains(key)).sorted().forEach(list::add);
        return list;
    }

    @Override
    public String parseKey(String input) {
        return items.contains(input) ? input : null;
    }

    /** 代號照打的算（沒有存量的話扣除本來就扣不到東西） */
    @Override
    public String parseStoredKey(String input) {
        String key = parseKey(input);
        return key != null ? key : input.isBlank() ? null : input;
    }

    @Override
    public List<String> suggest(String input) {
        return items.stream().filter(id -> id.startsWith(input)).limit(30).toList();
    }

    // ---------- 規則 ----------

    @Override
    boolean collectNewKinds() {
        return true;
    }

    @Override
    List<String> defaultList() {
        return List.of();
    }

    /** 種類由名單決定，不另外限制 */
    @Override
    int maxKinds() {
        return Integer.MAX_VALUE;
    }

    @Override
    boolean defaultOnlyWhenFull() {
        return false;
    }

    @Override
    Map<String, Long> defaultSpace() {
        return DEFAULT_SPACE;
    }

    // ---------- 畫面文字 ----------

    @Override
    List<String> tabLore() {
        return List.of("§7撿到的副本素材會直接收進來", "§7每個分流都是同一個倉庫");
    }

    @Override
    List<String> helpLore() {
        return List.of("§7容量看 VIP 階級（VIP 以上才能用）", "§7鍛造、兌換要先取出來放在身上", "§7每個分流都能存取同一個倉庫");
    }

    @Override
    List<String> toggleLore() {
        return List.of("§7撿到副本素材時直接收進倉庫", "§7單一種可以在格子上 Shift＋右鍵關掉");
    }

    @Override
    List<String> depositLore() {
        return List.of("§7身上的副本素材（不含快捷列）", "§7全部存進來");
    }

    @Override
    String fullBar(long total, long space) {
        return "副本素材倉庫滿了（" + String.format("%,d", total) + "/" + String.format("%,d", space) + "），撿到的素材會放在身上";
    }

    @Override
    Message collectHint() {
        return Message.MESSAGE__MATERIAL_HINT;
    }
}
