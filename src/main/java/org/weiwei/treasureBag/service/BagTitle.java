package org.weiwei.treasureBag.service;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.weiwei.treasureBag.Main;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 背包介面的材質標題
 * <p>
 * 有裝材質包：背景字 U+E0A0（材質包 gui/menu_bag.png）＋標題字型；沒裝：純文字標題。
 * 有沒有裝看登入服 hu_login_gate 寫在 Redis 的旗標（鍵 hu_custom:pack:&lt;uuid&gt;，值「最終狀態:檔名」，7 天過期），
 * 借 Hu_custom 的 RedisBase.get 讀，跟 hu_partner、HuMenu 同一套。
 * 只有「確定沒裝好」才算沒裝；查不到（過期、Redis 出錯、沒有 Hu_custom）一律當有裝。
 */
public final class BagTitle implements Listener {

    private static final String KEY_PREFIX = "hu_custom:pack:";
    private static final String LOADED = "SUCCESSFULLY_LOADED:";

    /** 標題字型（思源黑體），只收常用字；玩家自訂的頁名可能有沒收的字，所以只拿來寫固定的「隨身背包」 */
    private static final Key TITLE_FONT = Key.key("huanlan", "menu/title");
    private static final TextColor TITLE_RGB = TextColor.color(0xE8ECF2);
    private static final TextColor SUB_RGB = TextColor.color(0xAEB8C8);
    /** 往左 20 → 背景字 U+E0A0（前進 189）→ 往回 169，文字起點回到 x=8 */
    private static final String BACKGROUND = "";
    private static final String TITLE = "隨身背包";

    /** 確定沒裝好材質包的玩家 */
    private static final Map<UUID, Boolean> MISSING = new ConcurrentHashMap<>();
    private static Method redisGet;
    private static boolean resolved;

    /**
     * 註冊監聽並幫已在線上的玩家查一次（外掛熱載入時用得到）
     */
    public static void setup(Main plugin) {
        Bukkit.getPluginManager().registerEvents(new BagTitle(), plugin);
        Bukkit.getOnlinePlayers().forEach(BagTitle::check);
    }

    static boolean hasResourcePack(Player player) {
        return !MISSING.getOrDefault(player.getUniqueId(), false);
    }

    /**
     * 有材質包的標題：背景＋「隨身背包」（標題字型）＋附加文字（預設字型，可以是任何字）
     *
     * @param suffix 接在標題後面的文字（頁名、別人的名字），可為 null
     */
    static Component textured(String suffix) {
        var title = Component.text()
                // 一定要白色，不然點陣背景會被預設標題色染暗
                .append(Component.text(BACKGROUND).color(NamedTextColor.WHITE))
                .append(Component.text(TITLE).font(TITLE_FONT).color(TITLE_RGB));
        if (suffix != null && !suffix.isEmpty()) title.append(Component.text(" " + suffix).color(SUB_RGB));
        return title.build();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        MISSING.remove(event.getPlayer().getUniqueId());
    }

    private static void check(Player player) {
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInst(), () -> {
            Boolean noPack = query(id);
            if (noPack != null && noPack) MISSING.put(id, true);
        });
    }

    /** true＝確定沒裝好、false＝有裝、null＝查不到 */
    private static Boolean query(UUID player) {
        Method get = redisGet();
        if (get == null) return null;
        try {
            Object value = get.invoke(null, KEY_PREFIX + player);
            return value == null ? null : !value.toString().startsWith(LOADED);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static synchronized Method redisGet() {
        if (resolved) return redisGet;
        resolved = true;
        Plugin custom = Bukkit.getPluginManager().getPlugin("Hu_custom");
        if (custom == null) {
            Main.getInst().getLogger().info("沒有 Hu_custom：不查材質包狀態，一律當有裝");
            return null;
        }
        try {
            Class<?> redis = Class.forName("me.hu_custom.dataBase.RedisBase", true, custom.getClass().getClassLoader());
            redisGet = redis.getMethod("get", String.class);
        } catch (ReflectiveOperationException | LinkageError exception) {
            Main.getInst().getLogger().warning("讀不到 Hu_custom 的 RedisBase.get（" + exception + "）：不查材質包狀態，一律當有裝");
        }
        return redisGet;
    }
}
