package org.weiwei.treasureBag.util;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.weiwei.treasureBag.Main;


public class PlaceholderUtil {

    public void setup() {
        new PAPIHooker().register();
    }

    public static class PAPIHooker extends PlaceholderExpansion {

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "treasurebag";
        }

        @Override
        public String getAuthor() {
            return String.join(", ", Main.getInst().getDescription().getAuthors());
        }

        @Override
        public String getVersion() {
            return Main.getInst().getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null || params == null) return ChatColor.RED + "N/A";

            String[] parts = params.split("_");
            String key = parts[0].toLowerCase();

            switch (key) {
                case "cooldown":
                    return giftKeyCooldown(player, parts.length > 1 ? parts[1] : "default");
                case "can":
                    return "N/A"; // Placeholder for future use
                default:
                    return ChatColor.RED + "N/A";
            }
        }


        private String giftKeyCooldown(Player player, String key) {
            return null;
        }
    }
}
