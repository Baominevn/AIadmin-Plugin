package me.aiadmin.system;

import me.aiadmin.AIAdmin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LearningManager {

    private static class ObservationEntry {
        String playerName;
        int samples;
        int hackSignals;
        int legitSignals;
        int hackWeight;
        int legitWeight;
        double avgMove;
        double avgCps;
        long lastSeenEpoch;
        String lastSignal;
    }

    private final AIAdmin plugin;
    private final Object lock = new Object();
    private final Map<String, ObservationEntry> observations = new HashMap<>();

    private File learningFile;
    private FileConfiguration learningConfig;

    private boolean enabled;
    private int maxEntries;
    private int saveEveryUpdates;
    private int pendingUpdates;

    public LearningManager(AIAdmin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        learningFile = plugin.ensureActiveLocaleFile("learning.yml", "learning.yml");
        learningConfig = plugin.loadActiveConfiguration("learning.yml", "learning.yml");

        enabled = learningConfig.getBoolean("learning.enabled", true);
        maxEntries = Math.max(50, learningConfig.getInt("learning.max_entries", 500));
        saveEveryUpdates = Math.max(1, learningConfig.getInt("learning.save_every_updates", 8));

        synchronized (lock) {
            observations.clear();
            ConfigurationSection section = learningConfig.getConfigurationSection("learning.observation");
            if (section == null) {
                return;
            }
            for (String key : section.getKeys(false)) {
                ConfigurationSection node = section.getConfigurationSection(key);
                if (node == null) {
                    continue;
                }
                ObservationEntry entry = new ObservationEntry();
                entry.playerName = node.getString("player_name", key);
                entry.samples = Math.max(0, node.getInt("samples", 0));
                entry.hackSignals = Math.max(0, node.getInt("hack_signals", 0));
                entry.legitSignals = Math.max(0, node.getInt("legit_signals", 0));
                entry.hackWeight = Math.max(0, node.getInt("hack_weight", 0));
                entry.legitWeight = Math.max(0, node.getInt("legit_weight", 0));
                entry.avgMove = Math.max(0D, node.getDouble("avg_move", 0D));
                entry.avgCps = Math.max(0D, node.getDouble("avg_cps", 0D));
                entry.lastSeenEpoch = node.getLong("last_seen_epoch", Instant.now().getEpochSecond());
                entry.lastSignal = node.getString("last_signal", "none");
                observations.put(key.toLowerCase(Locale.ROOT), entry);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getStatusSummary() {
        synchronized (lock) {
            return "enabled=" + enabled + ", mode=observation, entries=" + observations.size();
        }
    }

    public void recordHackSignal(String playerName, String signalType, double value, int weight) {
        recordSignal(playerName, signalType, value, Math.max(1, weight), true);
    }

    public void recordLegitSignal(String playerName, String signalType, double value, int weight) {
        recordSignal(playerName, signalType, value, Math.max(1, weight), false);
    }

    public int applyAdaptivePoints(String playerName, int basePoints) {
        int safeBase = Math.max(1, basePoints);
        double multiplier = getSuspicionMultiplier(playerName);
        return Math.max(1, (int) Math.round(safeBase * multiplier));
    }

    public double getSuspicionMultiplier(String playerName) {
        if (!enabled || playerName == null || playerName.isBlank()) {
            return 1.0D;
        }
        synchronized (lock) {
            ObservationEntry entry = observations.get(playerName.toLowerCase(Locale.ROOT));
            if (entry == null || entry.samples < 3) {
                return 1.0D;
            }
            int bias = entry.hackWeight - entry.legitWeight;
            double raw = 1.0D + (bias / 180.0D);
            return clamp(raw, 0.75D, 1.25D);
        }
    }

    public String getObservationSummary(String playerName) {
        if (!enabled || playerName == null || playerName.isBlank()) {
            return "learning=off";
        }
        synchronized (lock) {
            ObservationEntry entry = observations.get(playerName.toLowerCase(Locale.ROOT));
            if (entry == null) {
                return "learning=empty";
            }
            int bias = entry.hackWeight - entry.legitWeight;
            return "samples=" + entry.samples
                    + ", bias=" + bias
                    + ", move=" + round(entry.avgMove)
                    + ", cps=" + round(entry.avgCps)
                    + ", last=" + entry.lastSignal;
        }
    }

    public void shutdown() {
        synchronized (lock) {
            saveLocked();
            pendingUpdates = 0;
        }
    }

    private void recordSignal(String playerName, String signalType, double value, int weight, boolean hackSignal) {
        if (!enabled || playerName == null || playerName.isBlank()) {
            return;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        ObservationEntry snapshot;
        synchronized (lock) {
            ObservationEntry entry = observations.computeIfAbsent(key, ignored -> {
                ObservationEntry created = new ObservationEntry();
                created.playerName = playerName;
                created.lastSeenEpoch = Instant.now().getEpochSecond();
                created.lastSignal = "none";
                return created;
            });

            entry.samples++;
            entry.lastSeenEpoch = Instant.now().getEpochSecond();
            entry.lastSignal = signalType == null || signalType.isBlank() ? "unknown" : signalType;

            if (hackSignal) {
                entry.hackSignals++;
                entry.hackWeight += weight;
            } else {
                entry.legitSignals++;
                entry.legitWeight += weight;
            }

            String normalizedSignal = entry.lastSignal.toLowerCase(Locale.ROOT);
            if (normalizedSignal.contains("move") || normalizedSignal.contains("speed") || normalizedSignal.contains("fly")) {
                entry.avgMove = smooth(entry.avgMove, value);
            }
            if (normalizedSignal.contains("cps") || normalizedSignal.contains("click")) {
                entry.avgCps = smooth(entry.avgCps, value);
            }

            trimIfNeeded();
            pendingUpdates++;
            if (pendingUpdates >= saveEveryUpdates) {
                saveLocked();
                pendingUpdates = 0;
            }
            snapshot = copy(entry);
        }

        if (plugin.getDatabaseManager() != null && plugin.getDatabaseManager().isEnabled()) {
            int score = snapshot.hackWeight - snapshot.legitWeight;
            String prompt = "obs:" + snapshot.playerName + ":" + snapshot.lastSignal;
            String reply = getObservationSummary(snapshot.playerName);
            plugin.getDatabaseManager().upsertLearningEntry(snapshot.playerName.toLowerCase(Locale.ROOT), prompt, reply, snapshot.samples, score);
        }
    }

    private ObservationEntry copy(ObservationEntry source) {
        ObservationEntry copy = new ObservationEntry();
        copy.playerName = source.playerName;
        copy.samples = source.samples;
        copy.hackSignals = source.hackSignals;
        copy.legitSignals = source.legitSignals;
        copy.hackWeight = source.hackWeight;
        copy.legitWeight = source.legitWeight;
        copy.avgMove = source.avgMove;
        copy.avgCps = source.avgCps;
        copy.lastSeenEpoch = source.lastSeenEpoch;
        copy.lastSignal = source.lastSignal;
        return copy;
    }

    private void trimIfNeeded() {
        if (observations.size() <= maxEntries) {
            return;
        }
        List<Map.Entry<String, ObservationEntry>> entries = new ArrayList<>(observations.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().lastSeenEpoch));
        while (observations.size() > maxEntries && !entries.isEmpty()) {
            Map.Entry<String, ObservationEntry> oldest = entries.remove(0);
            observations.remove(oldest.getKey());
        }
    }

    private void saveLocked() {
        if (learningConfig == null || learningFile == null) {
            return;
        }

        learningConfig.set("learning.observation", null);
        Map<String, Object> root = new HashMap<>();
        for (Map.Entry<String, ObservationEntry> item : observations.entrySet()) {
            ObservationEntry entry = item.getValue();
            Map<String, Object> node = new HashMap<>();
            node.put("player_name", entry.playerName);
            node.put("samples", entry.samples);
            node.put("hack_signals", entry.hackSignals);
            node.put("legit_signals", entry.legitSignals);
            node.put("hack_weight", entry.hackWeight);
            node.put("legit_weight", entry.legitWeight);
            node.put("avg_move", entry.avgMove);
            node.put("avg_cps", entry.avgCps);
            node.put("last_seen_epoch", entry.lastSeenEpoch);
            node.put("last_signal", entry.lastSignal);
            root.put(item.getKey(), node);
        }
        learningConfig.set("learning.observation", root);

        try {
            learningConfig.save(learningFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Không thể lưu learning.yml: " + ex.getMessage());
        }
    }

    private double smooth(double current, double incoming) {
        if (incoming <= 0D) {
            return current;
        }
        if (current <= 0D) {
            return incoming;
        }
        return (current * 0.8D) + (incoming * 0.2D);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String round(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
