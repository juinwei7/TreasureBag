package org.weiwei.treasureBag.service;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.weiwei.treasureBag.customGui.GuiType;
import org.weiwei.treasureBag.data.VaultRepository;
import org.weiwei.treasureBag.util.ConfigManager;
import org.weiwei.treasureBag.util.Message;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 倉庫種類：礦物倉庫（{@link OreKind}，原版物品，挖方塊身上放不下才收）、副本素材（{@link MaterialKind}，MythicMobs 物品，撿起來就收）
 * <p>
 * 兩種倉庫的帳本、資料庫讀寫、介面操作都是同一套（{@link VaultService}），這裡只放不一樣的地方：
 * 收哪些物品、物品怎麼產生、用哪張表、設定檔哪一段、畫面與訊息文字。
 * 鍵（資料庫的 material 欄）是原版材質名稱（RAW_IRON）或 MythicMobs 物品代號（st獵翳石骨），數量另外記，取出時照鍵產生物品。
 */
public abstract class VaultKind {

    public static final VaultKind ORE = new OreKind();
    public static final VaultKind MATERIAL = new MaterialKind();
    public static final List<VaultKind> ALL = List.of(ORE, MATERIAL);

    /** Config.yml 的段落（ORE、MATERIAL） */
    final String section;
    /** 有材質包時的標題（標題字型只收常用字，這幾個字要在裡面） */
    public final String title;
    /** 文字裡的名稱（礦物倉庫、副本素材倉庫） */
    public final String label;
    /** 打開的子指令（/bag ore、/bag material） */
    public final String command;
    final GuiType guiType;
    /** 這個倉庫的分頁在控制列第幾格（背包與兩種倉庫畫面都一樣） */
    final int tabSlot;
    /** 分頁圖示 paper 的 CMD；目前在這一頁是 +1 */
    final int tabCmd;
    /** 自動收集開關 lime_dye／gray_dye 的 CMD */
    final int toggleCmd;
    final VaultRepository repository;
    /** 暫存檔資料夾（不能放背包的 unsaved/，背包的補寫會把那裡的 yml 都當背包資料） */
    final String unsavedFolder;
    /** 開機補寫暫存檔時記進 ore_import 的記號開頭（兩種倉庫的檔名可能一樣，記號要分開） */
    final String recoverMarker;

    final Message loadFailed;
    final Message full;
    final Message withdrawn;
    final Message deposited;
    final Message removed;
    /** 容量是 0（沒有權限）時的訊息；null 照 {@link #full} */
    final Message noSpace;
    public final Message unknownItem;
    final Message adminAdded;
    final Message adminTaken;
    final Message adminSpace;
    final Message adminSpaceDefault;
    public final Message adminUsage;

    VaultKind(Builder builder) {
        this.section = builder.section;
        this.title = builder.title;
        this.label = builder.label;
        this.command = builder.command;
        this.guiType = builder.guiType;
        this.tabSlot = builder.tabSlot;
        this.tabCmd = builder.tabCmd;
        this.toggleCmd = builder.toggleCmd;
        this.repository = builder.repository;
        this.unsavedFolder = builder.unsavedFolder;
        this.recoverMarker = builder.recoverMarker;
        this.loadFailed = builder.loadFailed;
        this.full = builder.full;
        this.withdrawn = builder.withdrawn;
        this.deposited = builder.deposited;
        this.removed = builder.removed;
        this.noSpace = builder.noSpace;
        this.unknownItem = builder.unknownItem;
        this.adminAdded = builder.adminAdded;
        this.adminTaken = builder.adminTaken;
        this.adminSpace = builder.adminSpace;
        this.adminSpaceDefault = builder.adminSpaceDefault;
        this.adminUsage = builder.adminUsage;
    }

    // ---------- 物品 ----------

    /**
     * 這個物品能不能存進來：能存回傳鍵，不能回傳 null
     */
    abstract String key(ItemStack item);

    /**
     * 點身上的物品存不進去時的訊息（{@link #key} 回傳 null 的物品）
     */
    abstract Message rejectMessage(ItemStack item);

    /**
     * 資料庫讀出來的鍵還算不算數；不算的不放進帳本（不列、不佔容量）
     */
    abstract boolean valid(String key);

    /**
     * 取出時產生物品；產生不了回傳 null
     */
    abstract ItemStack create(String key, int amount);

    /**
     * 畫面上的圖示（數量 1）；產生不了回傳 null
     */
    abstract ItemStack icon(String key);

    /**
     * 自動收集時動作列上的名稱
     */
    abstract Component name(String key);

    /**
     * 畫面上要列出的種類與順序（不列的不出現在回傳裡）
     */
    abstract List<String> order(Collection<String> keys);

    /**
     * 管理員指令輸入的物品 → 鍵；認不得回傳 null
     */
    public abstract String parseKey(String input);

    public abstract List<String> suggest(String input);

    /**
     * 管理員扣除用：除了 {@link #parseKey} 認得的，也要能指到倉庫裡已經有、但現在不收的鍵
     * （例：名單拿掉、MythicMobs 也刪了的素材，玩家取不出來，只能由管理員扣掉）；認不得回傳 null
     */
    public String parseStoredKey(String input) {
        return parseKey(input);
    }

    // ---------- 規則 ----------

    /**
     * true：清單裡還沒有的種類撿到也收（第一次收就加進清單、自動收集開著）；false：只收清單裡有開的
     */
    abstract boolean collectNewKinds();

    /**
     * 第一次用的玩家一開始的清單
     */
    abstract List<String> defaultList();

    /**
     * 清單最多幾種
     */
    abstract int maxKinds();

    /**
     * Config.yml 沒寫 ONLY_WHEN_FULL 時：true＝身上放不下的才收
     */
    abstract boolean defaultOnlyWhenFull();

    /**
     * Config.yml 沒寫 SPACE 時的容量表（鍵對應權限 treasure-bag.&lt;鍵&gt;）
     */
    abstract Map<String, Long> defaultSpace();

    /**
     * 設定檔重新載入（啟動、/treasurebag reload）
     */
    void reload() {
    }

    // ---------- 畫面文字 ----------

    /**
     * 分頁按鈕（不在這一頁時）的說明
     */
    abstract List<String> tabLore();

    /**
     * 在這一頁時分頁按鈕說明最後的用法
     */
    abstract List<String> helpLore();

    abstract List<String> toggleLore();

    abstract List<String> depositLore();

    /**
     * 自動收集時倉庫滿了（動作列）
     */
    abstract String fullBar(long total, long space);

    /**
     * 每次進服第一次自動收集時在聊天欄說一次；null 不說
     */
    Message collectHint() {
        return null;
    }

    // ---------- 設定 ----------

    boolean onlyWhenFull() {
        var config = ConfigManager.getConfig();
        return config == null ? defaultOnlyWhenFull() : config.getBoolean(section + ".ONLY_WHEN_FULL", defaultOnlyWhenFull());
    }

    Map<String, Long> spaceTable() {
        var config = ConfigManager.getConfig();
        String path = section + ".SPACE";
        if (config == null || !config.isConfigurationSection(path)) return defaultSpace();
        Map<String, Long> table = new HashMap<>();
        for (String key : config.getConfigurationSection(path).getKeys(false)) {
            table.put(key, config.getLong(path + "." + key));
        }
        return table.isEmpty() ? defaultSpace() : table;
    }

    public static void reloadAll() {
        ALL.forEach(VaultKind::reload);
    }

    /**
     * /bag log 的來源說明；不是倉庫的來源回傳 null
     */
    public static String logLabel(String source) {
        if (source == null) return null;
        if (VaultRepository.SOURCE_IMPORT.equals(source)) return ORE.label + "・舊資料匯入";
        for (VaultKind kind : ALL) {
            VaultRepository repository = kind.repository;
            if (source.equals(repository.sourceAdmin)) return kind.label + "・管理員";
            if (source.equals(repository.sourceReturn)) return kind.label + "・放不下退回";
            if (source.equals(repository.sourceDeposit)) return kind.label;
        }
        return null;
    }

    /**
     * 子類別建構用（欄位很多，用名字設比較不會排錯）
     */
    static final class Builder {
        String section;
        String title;
        String label;
        String command;
        GuiType guiType;
        int tabSlot;
        int tabCmd;
        int toggleCmd;
        VaultRepository repository;
        String unsavedFolder;
        String recoverMarker;
        Message loadFailed;
        Message full;
        Message withdrawn;
        Message deposited;
        Message removed;
        Message noSpace;
        Message unknownItem;
        Message adminAdded;
        Message adminTaken;
        Message adminSpace;
        Message adminSpaceDefault;
        Message adminUsage;
    }
}
