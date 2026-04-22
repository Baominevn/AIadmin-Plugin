package me.aiadmin.system;

import me.aiadmin.AIAdmin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StatsManager {

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private static class PlayerStats {
        private String lastName;
        private int suspicionCount;
        private int reportCount;
        private int kickCount;
        private int tempBanCount;
    }

    private final AIAdmin plugin;
    private final Object lock = new Object();
    private final Map<String, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private final Deque<Long> recentChecks = new ArrayDeque<>();

    private File file;
    private FileConfiguration config;
    private long totalChecks;

    public StatsManager(AIAdmin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        synchronized (lock) {
            file = new File(plugin.getDataFolder(), "stats.yml");
            config = YamlConfiguration.loadConfiguration(file);
            playerStats.clear();
            totalChecks = Math.max(0L, config.getLong("stats.total_checks", 0L));
            recentChecks.clear();

            List<String> checkEntries = config.getStringList("stats.recent_check_timestamps");
            long now = System.currentTimeMillis();
            for (String raw : checkEntries) {
                try {
                    long value = Long.parseLong(raw);
                    if (now - value <= DAY_MILLIS) {
                        recentChecks.addLast(value);
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            if (config.getConfigurationSection("players") == null) {
                return;
            }
            for (String key : config.getConfigurationSection("players").getKeys(false)) {
                PlayerStats stats = new PlayerStats();
                stats.lastName = config.getString("players." + key + ".last_name", key);
                stats.suspicionCount = Math.max(0, config.getInt("players." + key + ".suspicion_count", 0));
                stats.reportCount = Math.max(0, config.getInt("players." + key + ".report_count", 0));
                stats.kickCount = Math.max(0, config.getInt("players." + key + ".kick_count", 0));
                stats.tempBanCount = Math.max(0, config.getInt("players." + key + ".tempban_count", 0));
                playerStats.put(key.toLowerCase(Locale.ROOT), stats);
            }
        }
    }

    public void recordSuspicion(String playerName) {
        mutatePlayer(playerName, stats -> stats.suspicionCount++);
    }

    public void recordReport(String playerName) {
        mutatePlayer(playerName, stats -> stats.reportCount++);
    }

    public void recordKick(String playerName) {
        mutatePlayer(playerName, stats -> stats.kickCount++);
    }

    public void recordTempBan(String playerName) {
        mutatePlayer(playerName, stats -> stats.tempBanCount++);
    }

    public void recordCheck(String playerName) {
        synchronized (lock) {
            totalChecks++;
            recentChecks.addLast(System.currentTimeMillis());
            trimRecentChecks();
            ensurePlayerStats(playerName);
            saveLocked();
        }
    }

    public int getSuspicionCount(String playerName) {
        synchronized (lock) {
            return getPlayerStats(playerName).suspicionCount;
        }
    }

    public int getReportCount(String playerName) {
        synchronized (lock) {
            return getPlayerStats(playerName).reportCount;
        }
    }

    public int getKickCount(String playerName) {
        synchronized (lock) {
            return getPlayerStats(playerName).kickCount;
        }
    }

    public int getTempBanCount(String playerName) {
        synchronized (lock) {
            return getPlayerStats(playerName).tempBanCount;
        }
    }

    public long getTotalChecks() {
        synchronized (lock) {
            return totalChecks;
        }
    }

    public int getChecksLast24Hours() {
        synchronized (lock) {
            trimRecentChecks();
            return recentChecks.size();
        }
    }

    private void mutatePlayer(String playerName, java.util.function.Consumer<PlayerStats> consumer) {
        synchronized (lock) {
            PlayerStats stats = ensurePlayerStats(playerName);
            consumer.accept(stats);
            saveLocked();
        }
    }

    private PlayerStats ensurePlayerStats(String playerName) {
        String safeName = playerName == null || playerName.isBlank() ? "unknown" : playerName.trim();
        String key = safeName.toLowerCase(Locale.ROOT);
        PlayerStats stats = playerStats.computeIfAbsent(key, ignored -> new PlayerStats());
        stats.lastName = safeName;
        return stats;
    }

    private PlayerStats getPlayerStats(String playerName) {
        return ensurePlayerStats(playerName);
    }

    private void trimRecentChecks() {
        long now = System.currentTimeMillis();
        while (!recentChecks.isEmpty() && now - recentChecks.peekFirst() > DAY_MILLIS) {
            recentChecks.removeFirst();
        }
    }

    private void saveLocked() {
        if (config == null || file == null) {
            return;
        }
        config.set("stats.total_checks", totalChecks);
        List<String> checks = new ArrayList<>();
        for (Long timestamp : recentChecks) {
            checks.add(String.valueOf(timestamp));
        }
        config.set("stats.recent_check_timestamps", checks);

        config.set("players", null);
        for (Map.Entry<String, PlayerStats> entry : playerStats.entrySet()) {
            String base = "players." + entry.getKey();
            PlayerStats stats = entry.getValue();
            config.set(base + ".last_name", stats.lastName);
            config.set(base + ".suspicion_count", stats.suspicionCount);
            config.set(base + ".report_count", stats.reportCount);
            config.set(base + ".kick_count", stats.kickCount);
            config.set(base + ".tempban_count", stats.tempBanCount);
        }

        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Không thể lưu stats.yml: " + ex.getMessage());
        }
    }
}
