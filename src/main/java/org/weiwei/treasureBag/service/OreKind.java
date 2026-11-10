package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.VaultRepository;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 礦物倉庫（/bag ore）：原版物品，鍵是材質名稱
 * <p>
 * 從 ExtraStorage 搬過來。只收沒有任何特殊資料的原版物品（改名、附魔、損耗、自製物品都不收），
 * 避免自製物品存進去、拿出來變成原版的。挖方塊時清單裡有開自動收集的、身上放不下的部分才收（Config.yml ORE）。
 */
final class OreKind extends VaultKind {

    /** Config.yml 沒寫 ORE.SPACE 時的容量（件數），鍵是背包權限 treasure-bag.<鍵>，玩家有好幾個取最大 */
    private static final Map<String, Long> DEFAULT_SPACE = Map.of(
            "DEFAULT", 2_000L, "VIP", 4_000L, "VIP2", 8_000L, "VIP3", 16_000L, "VIP4", 32_000L);
    private static final int DEFAULT_MAX_KINDS = 135;
    /** Config.yml 沒寫 ORE.DEFAULT_LIST 時新玩家的清單：沿用 ExtraStorage 的白名單，補上青金石 */
    private static final List<String> DEFAULT_LIST = List.of(
            "COAL_BLOCK", "LAPIS_BLOCK", "REDSTONE_BLOCK", "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK",
            "COAL", "REDSTONE", "IRON_ORE", "GOLD_ORE", "DIAMOND", "EMERALD", "LAPIS_LAZULI",
            "RAW_IRON", "RAW_COPPER", "RAW_GOLD", "RAW_IRON_BLOCK", "RAW_COPPER_BLOCK", "RAW_GOLD_BLOCK");
    private static final List<String> DEFAULT_BLACKLIST = List.of("BEDROCK");

    OreKind() {
        super(builder());
    }

    private static Builder builder() {
        Builder builder = new Builder();
        builder.section = "ORE";
        builder.title = BagTitle.ORE_TITLE;
        builder.label = "礦物倉庫";
        builder.command = "ore";
        builder.guiType = GuiType.ORE_MENU;
        builder.tabSlot = BagMenu.ORE_TAB_SLOT;
        builder.tabCmd = 881;
        builder.toggleCmd = 870;
        builder.repository = VaultRepository.ORE;
        builder.unsavedFolder = "unsaved-ore";
        builder.recoverMarker = "recover:";
        builder.loadFailed = Message.MESSAGE__ORE_LOAD_FAILED;
        builder.full = Message.MESSAGE__ORE_FULL;
        builder.withdrawn = Message.MESSAGE__ORE_WITHDRAWN;
        builder.deposited = Message.MESSAGE__ORE_DEPOSITED;
        builder.removed = Message.MESSAGE__ORE_REMOVED;
        builder.unknownItem = Message.MESSAGE__ORE_UNKNOWN_ITEM;
        builder.adminAdded = Message.MESSAGE__ORE_ADMIN_ADDED;
        builder.adminTaken = Message.MESSAGE__ORE_ADMIN_TAKEN;
        builder.adminSpace = Message.MESSAGE__ORE_ADMIN_SPACE;
        builder.adminSpaceDefault = Message.MESSAGE__ORE_ADMIN_SPACE_DEFAULT;
        builder.adminUsage = Message.MESSAGE__ORE_ADMIN_USAGE;
        return builder;
    }

    // ---------- 物品 ----------

    @Override
    String key(ItemStack item) {
        return item != null && canStore(item.getType()) && isPlain(item) ? item.getType().name() : null;
    }

    @Override
    Message rejectMessage(ItemStack item) {
        return canStore(item.getType()) ? Message.MESSAGE__ORE_NOT_PLAIN : Message.MESSAGE__ORE_FORBIDDEN;
    }

    /** 原版還有這個材質才算（改版拿掉的舊資料不列、不佔容量） */
    @Override
    boolean valid(String key) {
        return Material.getMaterial(key) != null;
    }

    @Override
    ItemStack create(String key, int amount) {
        Material material = Material.getMaterial(key);
        return material == null || !material.isItem() ? null : new ItemStack(material, amount);
    }

    @Override
    ItemStack icon(String key) {
        return create(key, 1);
    }

    @Override
    Component name(String key) {
        Material material = Material.getMaterial(key);
        return material == null ? Component.text(key) : Component.translatable(material.translationKey());
    }

    /** 原版物品順序；不是物品的種類（舊資料殘留）不列 */
    @Override
    List<String> order(Collection<String> keys) {
        return keys.stream()
                .map(Material::getMaterial)
                .filter(Objects::nonNull)
                .filter(Material::isItem)
                .sorted(Comparator.comparingInt(Material::ordinal))
                .map(Material::name)
                .toList();
    }

    @Override
    public String parseKey(String input) {
        Material material = Material.matchMaterial(input);
        return material != null && canStore(material) ? material.name() : null;
    }

    @Override
    public List<String> suggest(String input) {
        String upper = input.toUpperCase();
        return Arrays.stream(Material.values())
                .filter(material -> material.isItem() && material.name().startsWith(upper))
                .map(Material::name)
                .limit(30)
                .toList();
    }

    // ---------- 規則 ----------

    @Override
    boolean collectNewKinds() {
        return false;
    }

    @Override
    List<String> defaultList() {
        List<String> configured = ConfigManager.getConfig() != null ? ConfigManager.getConfig().getStringList("ORE.DEFAULT_LIST") : List.of();
        List<String> list = new ArrayList<>();
        for (String name : configured.isEmpty() ? DEFAULT_LIST : configured) {
            Material material = Material.matchMaterial(name);
            if (material != null && canStore(material)) list.add(material.name());
        }
        return list;
    }

    @Override
    int maxKinds() {
        return ConfigManager.getConfig() != null ? ConfigManager.getConfig().getInt("ORE.MAX_KINDS", DEFAULT_MAX_KINDS) : DEFAULT_MAX_KINDS;
    }

    @Override
    boolean defaultOnlyWhenFull() {
        return true;
    }

    @Override
    Map<String, Long> defaultSpace() {
        return DEFAULT_SPACE;
    }

    static boolean canStore(Material material) {
        if (material == null || material.isAir() || !material.isItem()) return false;
        List<String> blacklist = ConfigManager.getConfig() != null && ConfigManager.getConfig().isList("ORE.BLACKLIST")
                ? ConfigManager.getConfig().getStringList("ORE.BLACKLIST") : DEFAULT_BLACKLIST;
        return blacklist.stream().noneMatch(name -> name.equalsIgnoreCase(material.name()));
    }

    /**
     * 跟全新的同種物品一模一樣（沒改名、沒附魔、沒損耗、沒有自製物品的資料）
     */
    static boolean isPlain(ItemStack item) {
        return item != null && !item.getType().isAir() && item.isSimilar(new ItemStack(item.getType()));
    }

    // ---------- 畫面文字 ----------

    @Override
    List<String> tabLore() {
        return List.of("§7挖方塊時身上放不下的東西會收進來", "§7每個分流都是同一個倉庫");
    }

    @Override
    List<String> helpLore() {
        return List.of("§7容量看 VIP 階級，升級可以放更多", "§7點身上的物品就能把它加進清單", "§7每個分流都能存取同一個倉庫");
    }

    @Override
    List<String> toggleLore() {
        return List.of("§7挖方塊時身上放不下的東西，", "§7清單裡有開自動收集的會收進倉庫");
    }

    @Override
    List<String> depositLore() {
        return List.of("§7身上的物品（不含快捷列）", "§7清單裡有的種類全部存進來");
    }

    @Override
    String fullBar(long total, long space) {
        return "礦物倉庫滿了（" + String.format("%,d", total) + "/" + String.format("%,d", space) + "），挖到的東西會掉在地上";
    }
}
