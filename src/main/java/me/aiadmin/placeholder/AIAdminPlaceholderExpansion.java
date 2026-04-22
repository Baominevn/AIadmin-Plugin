package me.aiadmin.placeholder;

import me.aiadmin.AIAdmin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public class AIAdminPlaceholderExpansion extends PlaceholderExpansion {

    private final AIAdmin plugin;

    public AIAdminPlaceholderExpansion(AIAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "aiadmin";
    }

    @Override
    public String getAuthor() {
        return "Baominevn";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isBlank()) {
            return "";
        }
        if (plugin.getStatsManager() == null) {
            return "0";
        }

        String playerName = player == null ? "" : player.getName();
        switch (params.toLowerCase()) {
            case "sustime":
                if (playerName == null || playerName.isBlank()) {
                    return "0";
                }
                return String.valueOf(plugin.getStatsManager().getSuspicionCount(playerName));
            case "report":
                if (playerName == null || playerName.isBlank()) {
                    return "0";
                }
                return String.valueOf(plugin.getStatsManager().getReportCount(playerName));
            case "kick":
                if (playerName == null || playerName.isBlank()) {
                    return "0";
                }
                return String.valueOf(plugin.getStatsManager().getKickCount(playerName));
            case "termban":
                if (playerName == null || playerName.isBlank()) {
                    return "0";
                }
                return String.valueOf(plugin.getStatsManager().getTempBanCount(playerName));
            case "bots":
                return String.valueOf(plugin.getBotManager() == null ? 0 : plugin.getBotManager().getBotCount());
            case "check":
                return String.valueOf(plugin.getStatsManager().getTotalChecks());
            case "check_last24hour":
                return String.valueOf(plugin.getStatsManager().getChecksLast24Hours());
            default:
                return null;
        }
    }
}
