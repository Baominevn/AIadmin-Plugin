package me.aiadmin.system;

import me.aiadmin.AIAdmin;
import me.aiadmin.ai.AIChat;
import me.aiadmin.system.SuspicionManager.BehaviorState;
import me.aiadmin.system.SuspicionManager.PlayerRiskProfile;
import me.aiadmin.system.SuspicionManager.RiskTier;
import me.aiadmin.system.SuspicionManager.SkillClass;
import me.aiadmin.system.SuspicionManager.ThreatLevel;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerScanner {

    public enum BanResult {
        SUCCESS,
        BLOCKED_OP,
        FAILED
    }

    public static final class ScanEntry {
        private final String playerName;
        private final int suspicion;
        private final int alerts;
        private final int hackConfidence;
        private final int proConfidence;
        private final int currentCps;
        private final int recentPeakCps;
        private final RiskTier riskTier;
        private final ThreatLevel threatLevel;
        private final SkillClass skillClass;
        private final BehaviorState behaviorState;
        private final String latestEvidence;
        private final String locationSummary;

        private ScanEntry(String playerName, int suspicion, int alerts, int hackConfidence, int proConfidence,
                          int currentCps, int recentPeakCps, RiskTier riskTier, ThreatLevel threatLevel,
                          SkillClass skillClass, BehaviorState behaviorState, String latestEvidence,
                          String locationSummary) {
            this.playerName = playerName;
            this.suspicion = suspicion;
            this.alerts = alerts;
            this.hackConfidence = hackConfidence;
            this.proConfidence = proConfidence;
            this.currentCps = currentCps;
            this.recentPeakCps = recentPeakCps;
            this.riskTier = riskTier;
            this.threatLevel = threatLevel;
            this.skillClass = skillClass;
            this.behaviorState = behaviorState;
            this.latestEvidence = latestEvidence;
            this.locationSummary = locationSummary;
        }

        public String getPlayerName() {
            return playerName;
        }

        public int getSuspicion() {
            return suspicion;
        }

        public int getAlerts() {
            return alerts;
        }

        public int getHackConfidence() {
            return hackConfidence;
        }

        public int getProConfidence() {
            return proConfidence;
        }

        public int getCurrentCps() {
            return currentCps;
        }

        public int getRecentPeakCps() {
            return recentPeakCps;
        }

        public RiskTier getRiskTier() {
            return riskTier;
        }

        public ThreatLevel getThreatLevel() {
            return threatLevel;
        }

        public SkillClass getSkillClass() {
            return skillClass;
        }

        public BehaviorState getBehaviorState() {
            return behaviorState;
        }

        public String getLatestEvidence() {
            return latestEvidence;
        }

        public String getLocationSummary() {
            return locationSummary;
        }
    }

    public static final class ScanSnapshot {
        private final boolean manual;
        private final long startedAtMillis;
        private final long finishedAtMillis;
        private final int onlinePlayers;
        private final int maxPlayersPerScan;
        private final int scannedPlayers;
        private final int lowCount;
        private final int mediumCount;
        private final int highCount;
        private final int observingCount;
        private final List<ScanEntry> entries;

        private ScanSnapshot(boolean manual, long startedAtMillis, long finishedAtMillis, int onlinePlayers,
                             int maxPlayersPerScan, int scannedPlayers, int lowCount, int mediumCount,
                             int highCount, int observingCount, List<ScanEntry> entries) {
            this.manual = manual;
            this.startedAtMillis = startedAtMillis;
            this.finishedAtMillis = finishedAtMillis;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayersPerScan = maxPlayersPerScan;
            this.scannedPlayers = scannedPlayers;
            this.lowCount = lowCount;
            this.mediumCount = mediumCount;
            this.highCount = highCount;
            this.observingCount = observingCount;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        public static ScanSnapshot empty(boolean manual, long timestampMillis) {
            return new ScanSnapshot(manual, timestampMillis, timestampMillis, 0, 0, 0, 0, 0, 0, 0, Collections.emptyList());
        }

        public boolean isManual() {
            return manual;
        }

        public long getStartedAtMillis() {
            return startedAtMillis;
        }

        public long getFinishedAtMillis() {
            return finishedAtMillis;
        }

        public int getOnlinePlayers() {
            return onlinePlayers;
        }

        public int getMaxPlayersPerScan() {
            return maxPlayersPerScan;
        }

        public int getScannedPlayers() {
            return scannedPlayers;
        }

        public int getLowCount() {
            return lowCount;
        }

        public int getMediumCount() {
            return mediumCount;
        }

        public int getHighCount() {
            return highCount;
        }

        public int getObservingCount() {
            return observingCount;
        }

        public List<ScanEntry> getEntries() {
            return entries;
        }

        public boolean hasEntries() {
            return !entries.isEmpty();
        }
    }

    private final AIAdmin plugin;
    private final SuspicionManager suspicionManager;
    private final AIChat aiChat;
    private final Set<String> observingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitRunnable> observationTasks = new ConcurrentHashMap<>();
    private final Map<String, ObservationState> observationStates = new ConcurrentHashMap<>();
    private final Map<String, Long> actionCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> heuristicCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> observationAmplifyCooldowns = new ConcurrentHashMap<>();
    private final Set<String> mutedAutoScanPlayers = ConcurrentHashMap.newKeySet();
    private int roundRobinCursor;
    private volatile int lagScanCap = -1;
    private volatile ScanSnapshot latestScanSnapshot = ScanSnapshot.empty(false, System.currentTimeMillis());
    private File mutedAutoScanFile;
    private BukkitRunnable autoScanTask;

    private static class ObservationState {
        private final int baselineSuspicion;
        private final int baselineHackConfidence;
        private final int baselineProConfidence;
        private final int baselineAlerts;
        private final int baselineAimSamples;
        private final int baselineMoveSamples;
        private final int baselineHighCpsSamples;
        private final int baselineChatSpamSamples;
        private final int baselineScaffoldSamples;
        private final int baselineHoverFlySamples;
        private final int baselineXraySamples;
        private int strongSignalRounds;
        private int severeSignalRounds;
        private boolean escalated;

        private ObservationState(PlayerRiskProfile profile) {
            this.baselineSuspicion = profile.getSuspicion();
            this.baselineHackConfidence = profile.getHackConfidence();
            this.baselineProConfidence = profile.getProConfidence();
            this.baselineAlerts = profile.getTotalAlerts();
            this.baselineAimSamples = profile.getSuspiciousAimSamples();
            this.baselineMoveSamples = profile.getSuspiciousMoveSamples();
            this.baselineHighCpsSamples = profile.getHighCpsSamples();
            this.baselineChatSpamSamples = profile.getChatSpamSamples();
            this.baselineScaffoldSamples = profile.getScaffoldSamples();
            this.baselineHoverFlySamples = profile.getHoverFlySamples();
            this.baselineXraySamples = profile.getXraySamples();
        }
    }

    public ServerScanner(AIAdmin plugin, SuspicionManager suspicionManager, AIChat aiChat) {
        this.plugin = plugin;
        this.suspicionManager = suspicionManager;
        this.aiChat = aiChat;
        reloadMutedAutoScanPlayers();
    }

    public void reloadRuntimeConfig() {
        reloadMutedAutoScanPlayers();
        startAutoScan();
    }

    public void startAutoScan() {
        if (autoScanTask != null) {
            autoScanTask.cancel();
        }
        long intervalTicks = Math.max(1, plugin.getConfig().getLong("scan.interval_minutes", 5L)) * 60L * 20L;
        autoScanTask = new BukkitRunnable() {
            @Override
            public void run() {
                scanServer(false);
            }
        };
        autoScanTask.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    public void setLagScanCap(int lagScanCap) {
        this.lagScanCap = lagScanCap <= 0 ? -1 : lagScanCap;
    }

    public void shutdown() {
        if (autoScanTask != null) {
            autoScanTask.cancel();
            autoScanTask = null;
        }
        for (BukkitRunnable task : observationTasks.values()) {
            task.cancel();
        }
        observationTasks.clear();
        observationStates.clear();
        observingPlayers.clear();
    }

    public int getLagScanCap() {
        return lagScanCap;
    }

    public ScanSnapshot getLatestScanSnapshot() {
        return latestScanSnapshot;
    }

    public int getObservationCount() {
        return observingPlayers.size();
    }

    public ScanSnapshot scanServer(boolean manual) {
        long startedAt = System.currentTimeMillis();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            latestScanSnapshot = ScanSnapshot.empty(manual, startedAt);
            return latestScanSnapshot;
        }

        int baseMax = Math.max(1, plugin.getConfig().getInt("scan.max_players_per_scan", 50));
        int maxPerScan = lagScanCap > 0 ? Math.min(baseMax, lagScanCap) : baseMax;
        boolean roundRobin = !manual && (plugin.getOptionConfig() == null
                || plugin.getOptionConfig().getBoolean("scan.round_robin_when_over_limit", true));
        if (players.size() > maxPerScan && roundRobin) {
            players.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
            int size = players.size();
            int start = Math.floorMod(roundRobinCursor, size);
            List<Player> selected = new ArrayList<>(maxPerScan);
            for (int i = 0; i < maxPerScan; i++) {
                selected.add(players.get((start + i) % size));
            }
            roundRobinCursor = (start + maxPerScan) % size;
            players = selected;
        } else {
            boolean prioritize = plugin.getOptionConfig() == null
                    || plugin.getOptionConfig().getBoolean("scan.prioritize_high_risk_first", true);
            if (prioritize) {
                players.sort((a, b) -> Integer.compare(
                        suspicionManager.getSuspicion(b.getName()),
                        suspicionManager.getSuspicion(a.getName())
                ));
            }
            if (players.size() > maxPerScan) {
                if (!prioritize) {
                    Collections.shuffle(players);
                }
                players = new ArrayList<>(players.subList(0, maxPerScan));
            }
        }

        List<ScanEntry> entries = new ArrayList<>();
        int scannedPlayers = 0;
        int lowCount = 0;
        int mediumCount = 0;
        int highCount = 0;
        int observingCount = 0;

        for (Player player : players) {
            if (!manual && isAutoScanMuted(player.getName())) {
                continue;
            }
            PlayerRiskProfile profile = evaluatePlayer(player, manual);
            scannedPlayers++;

            ScanEntry entry = buildScanEntry(profile);
            entries.add(entry);
            if (entry.getThreatLevel() == ThreatLevel.HIGH) {
                highCount++;
            } else if (entry.getThreatLevel() == ThreatLevel.MEDIUM) {
                mediumCount++;
            } else if (entry.getRiskTier().ordinal() >= RiskTier.WATCH.ordinal()) {
                lowCount++;
            }
            if (observingPlayers.contains(player.getName().toLowerCase(Locale.ROOT))) {
                observingCount++;
            }
        }

        entries.sort(Comparator.comparingInt(ScanEntry::getSuspicion).reversed()
                .thenComparing(ScanEntry::getPlayerName, String.CASE_INSENSITIVE_ORDER));

        ScanSnapshot snapshot = new ScanSnapshot(
                manual,
                startedAt,
                System.currentTimeMillis(),
                Bukkit.getOnlinePlayers().size(),
                maxPerScan,
                scannedPlayers,
                lowCount,
                mediumCount,
                highCount,
                observingCount,
                entries
        );
        latestScanSnapshot = snapshot;
        maybeOpenAutoScanDashboard(snapshot);
        return snapshot;
    }

    public boolean muteFromAutoScan(CommandSender requester, String playerName) {
        String normalized = normalizePlayerName(playerName);
        if (normalized.isBlank()) {
            return false;
        }

        boolean added = mutedAutoScanPlayers.add(normalized);
        saveMutedAutoScanPlayers();
        stopObserving(null, playerName, false);

        if (requester != null) {
            requester.sendMessage(color(plugin.tr(requester,
                    added
                            ? "&aĐã bỏ qua &f" + playerName + "&a khỏi quét tự động. Vẫn có thể check tay hoặc observe bình thường."
                            : "&e" + playerName + " đã có trong danh sách bỏ qua quét tự động rồi.",
                    added
                            ? "&aMuted &f" + playerName + "&a from auto-scan. Manual checks still work."
                            : "&e" + playerName + " is already muted from auto-scan.")));
        }
        return added;
    }

    public boolean isAutoScanMuted(String playerName) {
        return mutedAutoScanPlayers.contains(normalizePlayerName(playerName));
    }

    private void reloadMutedAutoScanPlayers() {
        mutedAutoScanFile = new File(plugin.getDataFolder(), "observe_mute.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(mutedAutoScanFile);
        mutedAutoScanPlayers.clear();
        for (String entry : configuration.getStringList("players")) {
            String normalized = normalizePlayerName(entry);
            if (!normalized.isBlank()) {
                mutedAutoScanPlayers.add(normalized);
            }
        }
    }

    private void saveMutedAutoScanPlayers() {
        try {
            if (mutedAutoScanFile == null) {
                mutedAutoScanFile = new File(plugin.getDataFolder(), "observe_mute.yml");
            }
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.set("info", "Danh sách người chơi bị bỏ qua khỏi quét tự động. Check tay và observe vẫn hoạt động bình thường.");
            List<String> players = new ArrayList<>(mutedAutoScanPlayers);
            Collections.sort(players);
            configuration.set("players", players);
            configuration.save(mutedAutoScanFile);
        } catch (Exception ex) {
            plugin.getLogger().warning("Không thể lưu observe_mute.yml: " + ex.getMessage());
        }
    }

    private String normalizePlayerName(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    public PlayerRiskProfile evaluatePlayer(Player player, boolean manual) {
        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(player.getName());
        suspicionManager.observeLiveState(player);
        suspicionManager.applyMovementHeuristics(player.getName());
        applyScanHeuristicBoost(profile);
        int score = profile.getSuspicion();
        RiskTier tier = suspicionManager.getRiskTier(score);
        SkillClass skillClass = suspicionManager.getSkillClass(profile);

        applyActionPipeline(player, profile, tier, skillClass);

        if (plugin.getBotManager() != null && plugin.getBotManager().shouldTriggerFor(tier)) {
            plugin.getBotManager().observeTarget(player, "risk-tier-" + tier.name());
        }
        return profile;
    }

    private ScanEntry buildScanEntry(PlayerRiskProfile profile) {
        if (profile == null) {
            return new ScanEntry("unknown", 0, 0, 0, 0, 0, 0,
                    RiskTier.CLEAR, ThreatLevel.LOW, SkillClass.BALANCED,
                    BehaviorState.IDLE, "none", "unknown");
        }
        boolean english = plugin.getActiveConfigProfile() == AIAdmin.ConfigProfile.ENGLISH;
        return new ScanEntry(
                profile.getName(),
                profile.getSuspicion(),
                profile.getTotalAlerts(),
                profile.getHackConfidence(),
                profile.getProConfidence(),
                profile.getCurrentCps(),
                profile.getPeakCps(),
                suspicionManager.getRiskTier(profile.getSuspicion()),
                suspicionManager.getThreatLevel(profile.getSuspicion()),
                suspicionManager.getSkillClass(profile),
                profile.getLastBehaviorState(),
                suspicionManager.getLatestEvidenceSummary(profile.getName(), english),
                profile.getLastSuspiciousLocationSummary(english)
        );
    }

    private void maybeOpenAutoScanDashboard(ScanSnapshot snapshot) {
        if (snapshot == null || snapshot.isManual() || !snapshot.hasEntries()) {
            return;
        }
        if (!getOptionBoolean("scan.open_dashboard_for_admins_on_auto_scan", true)) {
            return;
        }

        RiskTier minimumTier = parseTier(
                plugin.getConfig().getString("scan.auto_dashboard_min_tier", "ALERT"),
                RiskTier.ALERT
        );
        boolean hasRelevantEntry = false;
        for (ScanEntry entry : snapshot.getEntries()) {
            if (entry.getRiskTier().ordinal() >= minimumTier.ordinal()) {
                hasRelevantEntry = true;
                break;
            }
        }
        if (!hasRelevantEntry || plugin.getSuspicionDashboard() == null) {
            return;
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission("aiadmin.use")
                    && !online.hasPermission("aiadmin.staff")
                    && !online.hasPermission("aiadmin.admin")) {
                continue;
            }
            plugin.getSuspicionDashboard().openScanDashboard(online, snapshot);
        }
    }

    private void applyScanHeuristicBoost(PlayerRiskProfile profile) {
        String key = profile.getName().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long last = heuristicCooldowns.getOrDefault(key, 0L);
        if (now - last < 30000L) {
            return;
        }
        heuristicCooldowns.put(key, now);

        int bonus = 0;
        List<String> reasons = new ArrayList<>();

        if (profile.getHighCpsSamples() >= 3) {
            bonus += 2;
            reasons.add("high-cps");
        }
        if (profile.getPeakCps() >= 16 && profile.getHighCpsSamples() >= 1) {
            bonus += 2;
            reasons.add("peak-cps");
        }
        if (profile.getSuspiciousAimSamples() >= 3) {
            bonus += 2;
            reasons.add("aim-pattern");
        }
        if (profile.getSuspiciousMoveSamples() >= 4) {
            bonus += 1;
            reasons.add("move-pattern");
        }
        if (profile.getHoverFlySamples() >= 1) {
            bonus += 3;
            reasons.add("hover-fly");
        }
        if (profile.getScaffoldSamples() >= 1) {
            bonus += 2;
            reasons.add("scaffold");
        }
        if (profile.getXraySamples() >= 1) {
            bonus += 3;
            reasons.add("xray");
        }
        if (profile.getChatSpamSamples() >= 2) {
            bonus += 1;
            reasons.add("chat-spam");
        }
        if (profile.getHackConfidence() >= 70 && profile.getProConfidence() <= 40) {
            bonus += 3;
            reasons.add("hack-confidence");
        }
        if (profile.getTotalAlerts() >= 1) {
            bonus += 2;
            reasons.add("alert-history");
        }

        if (bonus <= 0 || !suspicionManager.hasStrongHackEvidence(profile)) {
            return;
        }
        suspicionManager.addSuspicion(profile.getName(), bonus, "scan-boost", String.join(",", reasons));
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "scan-boost", bonus, bonus);
        }
    }

    public BanResult banPlayerByName(String playerName, String reason) {
        if (isProtectedOperator(playerName)) {
            sendAiBanNotice("warning", "Skip ban because " + playerName + " is OP.");
            logBan(playerName, reason, "0d", "blocked-op", true);
            return BanResult.BLOCKED_OP;
        }

        String defaultDuration = getAiBanDuration("12h");
        String duration = suspicionManager.clampBanDuration(defaultDuration);
        String defaultReason = getAiBanReason("AI phát hiện gian lận");
        String finalReason = (reason == null || reason.isBlank()) ? defaultReason : reason;

        if (!isAiBanFeatureEnabled()) {
            return applyInternalTempBan(playerName, finalReason, duration);
        }

        boolean aiBanEnabled = plugin.isPluginIntegrationEnabled("ai_ban");
        boolean liteBansInstalled = Bukkit.getPluginManager().isPluginEnabled("LiteBans");
        if (aiBanEnabled && liteBansInstalled) {
            String template = plugin.getAiBanConfig() == null
                    ? "litebans:ban {player} {duration} {reason}"
                    : getAiBanConfigString("ai_ban.commands.ban", "liteban.commands.ban", "litebans:ban {player} {duration} {reason}");
            String command = template
                    .replace("{player}", playerName)
                    .replace("{duration}", duration)
                    .replace("{reason}", finalReason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            sendAiBanNotice("success", "Đã ban " + playerName + " qua AI_ban trong " + duration + ".");
            logBan(playerName, finalReason, duration, "ai_ban", false);
            return BanResult.SUCCESS;
        }

        if (aiBanEnabled && !liteBansInstalled) {
            boolean fallbackAllowed = plugin.getPluginSettingsConfig() == null
                    || plugin.getPluginSettingsConfig().getBoolean("behavior.fallback_to_internal_when_missing", true);
            if (!fallbackAllowed) {
                sendAiBanNotice("error", "Thiếu LiteBans và fallback đang tắt. Không thể ban " + playerName + ".");
                return BanResult.FAILED;
            }
            sendAiBanNotice("warning", "Đã bật AI_ban qua LiteBans nhưng plugin chưa có. Chuyển sang tempban nội bộ cho " + playerName + ".");
        }
        return applyInternalTempBan(playerName, finalReason, duration);
    }

    public BanResult tempBanPlayerByName(String playerName, int days, String reason) {
        return tempBanPlayerByName(playerName, days + "d", reason);
    }

    public BanResult tempBanPlayerByName(String playerName, String duration, String reason) {
        if (isProtectedOperator(playerName)) {
            sendAiBanNotice("warning", "Bỏ qua tempban vì " + playerName + " là OP.");
            logBan(playerName, reason, "0d", "blocked-op", true);
            return BanResult.BLOCKED_OP;
        }
        String safeDuration = normalizeDuration(duration);
        String defaultReason = getAiBanConfigString("ai_ban.default_reason", "liteban.default_reason", "AI phát hiện gian lận");
        String finalReason = (reason == null || reason.isBlank()) ? defaultReason : reason;
        return applyInternalTempBan(playerName, finalReason, safeDuration);
    }

    public BanResult kickPlayerByName(String playerName, String reason) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online == null || !online.isOnline()) {
            return BanResult.FAILED;
        }

        String finalReason = (reason == null || reason.isBlank())
                ? plugin.tr(plugin.getActiveConfigProfile(),
                "AIAdmin phát hiện hành vi bất thường. Vui lòng chờ staff kiểm tra.",
                "AIAdmin detected unusual behavior. Please wait for staff review.")
                : reason;

        boolean aiBanEnabled = plugin.isPluginIntegrationEnabled("ai_ban");
        boolean liteBansInstalled = Bukkit.getPluginManager().isPluginEnabled("LiteBans");
        if (aiBanEnabled && liteBansInstalled && plugin.getAiBanConfig() != null) {
            String command = getAiBanConfigString("ai_ban.commands.kick", "liteban.commands.kick", "litebans:kick {player} {reason}")
                    .replace("{player}", playerName)
                    .replace("{reason}", finalReason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } else {
            online.kickPlayer(color(finalReason));
        }

        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordKick(playerName);
        }
        logBan(playerName, finalReason, "kick", "kick", false);
        return BanResult.SUCCESS;
    }

    public boolean observePlayer(CommandSender requester, String playerName, String reason, boolean notifyStaff) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            if (requester != null) {
                requester.sendMessage(color(plugin.tr(requester,
                        "&cKhông thể quan sát: " + playerName + " đang offline hoặc không tồn tại.",
                        "&cCannot observe: " + playerName + " is offline or missing.")));
            }
            return false;
        }

        String safeReason = (reason == null || reason.isBlank()) ? "manual-observe" : reason;
        if (plugin.getBotManager() != null) {
            plugin.getBotManager().observeTarget(target, safeReason);
        }

        boolean started = startObservation(target.getName());
        if (requester != null) {
            if (started) {
                requester.sendMessage(color(plugin.tr(requester,
                        "&aAI đã bắt đầu quan sát &f" + target.getName() + "&a.",
                        "&aAI has started observing &f" + target.getName() + "&a.")));
            } else {
                requester.sendMessage(color(plugin.tr(requester,
                        "&eAI đang quan sát &f" + target.getName() + "&e rồi.",
                        "&eAI is already observing &f" + target.getName() + "&e.")));
            }
        }
        if (notifyStaff && started) {
            aiChat.sendStaffNotice(plugin.tr(plugin.getActiveConfigProfile(),
                    "AI đang quan sát " + target.getName() + " (reason=" + safeReason + ").",
                    "AI is observing " + target.getName() + " (reason=" + safeReason + ")."));
        }
        return started;
    }

    public boolean stopObserving(CommandSender requester, String playerName, boolean notifyStaff) {
        String key = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
        BukkitRunnable task = observationTasks.remove(key);
        observationStates.remove(key);
        boolean removed = observingPlayers.remove(key);
        if (task != null) {
            task.cancel();
            removed = true;
        }
        boolean botStopped = stopActiveBotObservation(playerName, "manual-stop");
        removed = removed || botStopped;

        if (requester != null) {
            if (removed) {
                requester.sendMessage(color(plugin.tr(requester,
                        "&aĐã dừng quan sát &f" + playerName + "&a.",
                        "&aStopped observing &f" + playerName + "&a.")));
            } else {
                requester.sendMessage(color(plugin.tr(requester,
                        "&eAI hiện không quan sát &f" + playerName + "&e.",
                        "&eAI is not currently observing &f" + playerName + "&e.")));
            }
        }
        if (notifyStaff && removed) {
            aiChat.sendStaffNotice(plugin.tr(plugin.getActiveConfigProfile(),
                    "AI đã dừng quan sát " + playerName + ".",
                    "AI stopped observing " + playerName + "."));
        }
        return removed;
    }
    private boolean stopActiveBotObservation(String playerName, String reason) {
        if (plugin.getBotManager() == null || playerName == null || playerName.isBlank()) {
            return false;
        }
        String activeTargetName = plugin.getBotManager().getActiveTargetName();
        if (activeTargetName == null || !playerName.equalsIgnoreCase(activeTargetName)) {
            return false;
        }
        plugin.getBotManager().stopObservation(reason);
        return true;
    }

    private boolean startObservation(String playerName) {
        String key = playerName.toLowerCase(Locale.ROOT);
        if (!observingPlayers.add(key)) {
            return false;
        }

        PlayerRiskProfile baselineProfile = suspicionManager.getOrCreateProfile(playerName);
        observationStates.put(key, new ObservationState(baselineProfile));

        int durationSeconds = Math.max(15, plugin.getOptionConfig().getInt("anticheat.report_observe_seconds", 120));
        int intervalSeconds = Math.max(3, plugin.getOptionConfig().getInt("anticheat.report_observe_interval_seconds", 5));
        long periodTicks = intervalSeconds * 20L;
        int maxRounds = Math.max(1, durationSeconds / intervalSeconds);

        BukkitRunnable task = new BukkitRunnable() {
            private int rounds = 0;

            @Override
            public void run() {
                Player target = Bukkit.getPlayerExact(playerName);
                if (target == null || !target.isOnline()) {
                    observationTasks.remove(key);
                    observationStates.remove(key);
                    observingPlayers.remove(key);
                    stopActiveBotObservation(playerName, "target-offline");
                    cancel();
                    return;
                }

                ObservationState state = observationStates.get(key);
                if (state != null) {
                    evaluateObservedPlayer(target, state);
                }
                rounds++;
                if (rounds >= maxRounds) {
                    observationTasks.remove(key);
                    ObservationState finishedState = observationStates.remove(key);
                    observingPlayers.remove(key);
                    stopActiveBotObservation(playerName, "observe-cycle-complete");
                    aiChat.sendStaffNotice(buildObservationSummary(target.getName(), finishedState));
                    cancel();
                }
            }
        };
        observationTasks.put(key, task);
        task.runTaskTimer(plugin, 0L, periodTicks);
        return true;
    }

    private void evaluateObservedPlayer(Player player, ObservationState state) {
        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(player.getName());
        suspicionManager.observeLiveState(player);
        suspicionManager.applyMovementHeuristics(player.getName());
        int roundStrength = amplifyObservedEvidence(player, profile, state);
        if (roundStrength >= 3) {
            state.strongSignalRounds++;
        }
        if (roundStrength >= 7) {
            state.severeSignalRounds++;
        }
        RiskTier tier = suspicionManager.getRiskTier(profile.getSuspicion());
        SkillClass skillClass = suspicionManager.getSkillClass(profile);

        if (!shouldEscalateObservation(profile, state, tier, skillClass) || state.escalated) {
            return;
        }

        state.escalated = true;
        aiChat.sendStaffNotice(plugin.tr(plugin.getActiveConfigProfile(),
                "Quan sát " + player.getName() + ": đã xuất hiện tín hiệu khá rõ trong lúc theo dõi, nên kiểm tra ngay. "
                        + summarizeObservationDelta(profile, state, false),
                "Observation on " + player.getName() + ": stronger-than-normal signs detected, review more closely. "
                        + summarizeObservationDelta(profile, state, true)));
    }

    private int amplifyObservedEvidence(Player player, PlayerRiskProfile profile, ObservationState state) {
        if (player == null || profile == null || state == null) {
            return 0;
        }

        int score = 0;
        int deltaAlerts = Math.max(0, profile.getTotalAlerts() - state.baselineAlerts);
        int deltaAim = Math.max(0, profile.getSuspiciousAimSamples() - state.baselineAimSamples);
        int deltaMove = Math.max(0, profile.getSuspiciousMoveSamples() - state.baselineMoveSamples);
        int deltaHighCps = Math.max(0, profile.getHighCpsSamples() - state.baselineHighCpsSamples);
        int deltaChatSpam = Math.max(0, profile.getChatSpamSamples() - state.baselineChatSpamSamples);
        int deltaScaffold = Math.max(0, profile.getScaffoldSamples() - state.baselineScaffoldSamples);
        int deltaHoverFly = Math.max(0, profile.getHoverFlySamples() - state.baselineHoverFlySamples);
        int deltaXray = Math.max(0, profile.getXraySamples() - state.baselineXraySamples);
        int deltaHackConfidence = Math.max(0, profile.getHackConfidence() - state.baselineHackConfidence);
        BehaviorState liveState = suspicionManager.getBehaviorState(profile, player);
        boolean combatContext = liveState == BehaviorState.COMBAT;
        boolean unstableContext = liveState == BehaviorState.KNOCKBACK || liveState == BehaviorState.LAGGED;

        if (deltaHoverFly >= 1 && consumeObservationAmplify(profile.getName(), "observe-fly", 2_500L)) {
            suspicionManager.addSuspicion(profile.getName(), 7, "observe-fly", "confirmed by observation");
            profile.boostHackConfidence(10);
            score += 11;
        }
        if (deltaScaffold >= 1 && consumeObservationAmplify(profile.getName(), "observe-scaffold", 2_500L)) {
            suspicionManager.addSuspicion(profile.getName(), 6, "observe-scaffold", "repeated bridge pattern while watched");
            profile.boostHackConfidence(9);
            score += 10;
        }
        if (deltaXray >= 1 && consumeObservationAmplify(profile.getName(), "observe-xray", 5_000L)) {
            suspicionManager.addSuspicion(profile.getName(), 7, "observe-xray", "hidden ore chain confirmed");
            profile.boostHackConfidence(10);
            score += 11;
        }
        if ((deltaAim >= 1 && deltaHighCps >= 1)
                && consumeObservationAmplify(profile.getName(), "observe-combat", 2_000L)) {
            suspicionManager.addSuspicion(profile.getName(), 5, "observe-combat", "aim+cps combo while watched");
            profile.boostHackConfidence(7);
            score += 8;
        }
        if (combatContext
                && profile.getCurrentCps() >= Math.max(13, getOptionInt("anticheat.behavior.cps_flag_threshold", 16) - 1)
                && deltaHackConfidence >= 3
                && consumeObservationAmplify(profile.getName(), "observe-high-cps", 2_000L)) {
            suspicionManager.addSuspicion(profile.getName(), 4, "observe-high-cps", "combat cps=" + profile.getCurrentCps());
            profile.boostHackConfidence(5);
            score += 6;
        }
        if (combatContext
                && deltaAim >= 2
                && consumeObservationAmplify(profile.getName(), "observe-aim", 1_600L)) {
            suspicionManager.addSuspicion(profile.getName(), 3, "observe-aim", "multiple aim snaps while watched");
            profile.boostHackConfidence(4);
            score += 5;
        }
        if (combatContext
                && deltaAim >= 1
                && deltaMove >= 1
                && deltaHackConfidence >= 2
                && consumeObservationAmplify(profile.getName(), "observe-combat-confirm", 2_000L)) {
            suspicionManager.addSuspicion(profile.getName(), 3, "observe-combat-confirm", "combat tracking stayed suspicious while watched");
            profile.boostHackConfidence(4);
            score += 5;
        }
        if (deltaMove >= 2
                && !unstableContext
                && consumeObservationAmplify(profile.getName(), "observe-move", 2_500L)) {
            suspicionManager.addSuspicion(profile.getName(), 3, "observe-move", "movement pattern stayed suspicious while watched");
            profile.boostHackConfidence(4);
            score += 4;
        }
        if (deltaChatSpam >= 1 && consumeObservationAmplify(profile.getName(), "observe-spam", 3_000L)) {
            suspicionManager.addSuspicion(profile.getName(), 2, "observe-spam", "chat spam continued during observe");
            profile.boostHackConfidence(2);
            score += 2;
        }
        if (deltaAlerts >= 1
                && consumeObservationAmplify(profile.getName(), "observe-alert-sync", 2_500L)) {
            suspicionManager.addSuspicion(profile.getName(), 3, "observe-alert-sync", "anti-cheat alerts continued during observe");
            profile.boostHackConfidence(4);
            score += 5;
        }
        if (deltaHackConfidence >= 6
                && (deltaAim + deltaHighCps + deltaScaffold + deltaHoverFly + deltaXray) >= 2
                && consumeObservationAmplify(profile.getName(), "observe-confirm", 2_500L)) {
            suspicionManager.addSuspicion(profile.getName(), 4, "observe-confirm", "camera confirm strength=" + deltaHackConfidence);
            profile.boostHackConfidence(5);
            score += 6;
        }
        if ((deltaHoverFly + deltaScaffold + deltaXray) >= 2
                && consumeObservationAmplify(profile.getName(), "observe-hard-confirm", 2_500L)) {
            suspicionManager.addSuspicion(profile.getName(), 5, "observe-hard-confirm", "multiple hard signals confirmed by camera");
            profile.boostHackConfidence(6);
            score += 8;
        }
        return score;
    }

    private boolean shouldEscalateObservation(PlayerRiskProfile profile, ObservationState state, RiskTier tier, SkillClass skillClass) {
        if (profile == null || state == null) {
            return false;
        }

        int deltaSuspicion = profile.getSuspicion() - state.baselineSuspicion;
        int deltaHackConfidence = profile.getHackConfidence() - state.baselineHackConfidence;
        int deltaAlerts = profile.getTotalAlerts() - state.baselineAlerts;
        int deltaAim = profile.getSuspiciousAimSamples() - state.baselineAimSamples;
        int deltaMove = profile.getSuspiciousMoveSamples() - state.baselineMoveSamples;
        int deltaHighCps = profile.getHighCpsSamples() - state.baselineHighCpsSamples;
        int deltaChatSpam = profile.getChatSpamSamples() - state.baselineChatSpamSamples;
        int deltaScaffold = profile.getScaffoldSamples() - state.baselineScaffoldSamples;
        int deltaHoverFly = profile.getHoverFlySamples() - state.baselineHoverFlySamples;
        int deltaXray = profile.getXraySamples() - state.baselineXraySamples;
        int evidenceSignals = Math.max(0, deltaAim)
                + Math.max(0, deltaMove)
                + Math.max(0, deltaHighCps)
                + Math.max(0, deltaChatSpam)
                + Math.max(0, deltaScaffold)
                + Math.max(0, deltaHoverFly)
                + Math.max(0, deltaXray);

        if (deltaAlerts >= 1) {
            return true;
        }
        if (deltaXray >= 1) {
            return true;
        }
        if (deltaHoverFly >= 1 || deltaScaffold >= 1) {
            return true;
        }
        if (deltaHighCps >= 1 && (deltaAim >= 1 || deltaMove >= 1)) {
            return true;
        }
        if (deltaAim >= 2 && deltaHackConfidence >= 2) {
            return true;
        }
        if (deltaChatSpam >= 1 && deltaSuspicion >= 2) {
            return true;
        }
        if (state.severeSignalRounds >= 1) {
            return true;
        }
        if (deltaSuspicion >= 5 && evidenceSignals >= 1) {
            return true;
        }
        if (tier.ordinal() >= RiskTier.WATCH.ordinal()
                && deltaHackConfidence >= 4
                && evidenceSignals >= 2
                && deltaSuspicion >= 2) {
            return true;
        }
        if (state.strongSignalRounds >= 1 && (deltaSuspicion >= 1 || evidenceSignals >= 2)) {
            return true;
        }
        if (tier.ordinal() >= RiskTier.DANGER.ordinal() && evidenceSignals >= 1) {
            return true;
        }
        if (tier.ordinal() < RiskTier.WATCH.ordinal()) {
            return false;
        }
        if (skillClass == SkillClass.HACK_LIKELY
                && evidenceSignals >= 1
                && (deltaSuspicion >= 2 || deltaHackConfidence >= 6)) {
            return true;
        }
        if (skillClass != SkillClass.HACK_LIKELY) {
            return false;
        }
        return deltaSuspicion >= 2 && deltaHackConfidence >= 6 && evidenceSignals >= 2;
    }

    private String buildObservationSummary(String playerName, ObservationState state) {
        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(playerName);
        AIAdmin.ConfigProfile language = plugin.getActiveConfigProfile();
        if (state == null) {
            return plugin.tr(language,
                    "Chu kỳ quan sát đã hoàn tất cho " + playerName + ".",
                    "Observation cycle completed for " + playerName + ".");
        }

        int deltaSuspicion = profile.getSuspicion() - state.baselineSuspicion;
        int deltaAlerts = profile.getTotalAlerts() - state.baselineAlerts;
        int deltaAim = profile.getSuspiciousAimSamples() - state.baselineAimSamples;
        int deltaHighCps = profile.getHighCpsSamples() - state.baselineHighCpsSamples;
        int deltaScaffold = profile.getScaffoldSamples() - state.baselineScaffoldSamples;
        int deltaHoverFly = profile.getHoverFlySamples() - state.baselineHoverFlySamples;
        int deltaXray = profile.getXraySamples() - state.baselineXraySamples;
        if (state.escalated) {
            return plugin.tr(language,
                    "Chu kỳ quan sát đã hoàn tất cho " + playerName + ". Đã xuất hiện thêm dấu hiệu đáng nghi trong lúc theo dõi. "
                            + summarizeObservationDelta(profile, state, false),
                    "Observation cycle completed for " + playerName + ". Additional suspicious signs appeared during tracking. "
                            + summarizeObservationDelta(profile, state, true));
        }
        boolean strongHackPattern = deltaXray >= 1
                || deltaHoverFly >= 1
                || deltaScaffold >= 1
                || (deltaAim >= 1 && deltaHighCps >= 1)
                || state.severeSignalRounds >= 1;
        if (strongHackPattern) {
            return plugin.tr(language,
                    "Chu kỳ quan sát đã hoàn tất cho " + playerName + ". Đã ghi nhận dấu hiệu hack khá rõ trong lúc theo dõi. "
                            + summarizeObservationDelta(profile, state, false),
                    "Observation cycle completed for " + playerName + ". Clear cheating signals were recorded during tracking. "
                            + summarizeObservationDelta(profile, state, true));
        }
        if ((deltaAlerts <= 0 && deltaSuspicion <= 2)
                && state.strongSignalRounds <= 0
                && deltaAim <= 0
                && deltaHighCps <= 0) {
            return plugin.tr(language,
                    "Chu kỳ quan sát đã hoàn tất cho " + playerName + ". Chưa thấy đủ dấu hiệu rõ ràng để kết luận hack.",
                    "Observation cycle completed for " + playerName + ". Not enough clear signs to call this cheating.");
        }
        return plugin.tr(language,
                "Chu kỳ quan sát đã hoàn tất cho " + playerName + ". Có vài tín hiệu đáng lưu ý, nên tiếp tục kiểm tra thêm. "
                        + summarizeObservationDelta(profile, state, false),
                "Observation cycle completed for " + playerName + ". A few light signals appeared, but not enough for a conclusion. "
                        + summarizeObservationDelta(profile, state, true));
    }

    private String summarizeObservationDelta(PlayerRiskProfile profile, ObservationState state, boolean english) {
        int deltaSuspicion = Math.max(0, profile.getSuspicion() - state.baselineSuspicion);
        int deltaHackConfidence = Math.max(0, profile.getHackConfidence() - state.baselineHackConfidence);
        int deltaAlerts = Math.max(0, profile.getTotalAlerts() - state.baselineAlerts);
        int deltaAim = Math.max(0, profile.getSuspiciousAimSamples() - state.baselineAimSamples);
        int deltaMove = Math.max(0, profile.getSuspiciousMoveSamples() - state.baselineMoveSamples);
        int deltaHighCps = Math.max(0, profile.getHighCpsSamples() - state.baselineHighCpsSamples);
        int deltaChatSpam = Math.max(0, profile.getChatSpamSamples() - state.baselineChatSpamSamples);
        int deltaScaffold = Math.max(0, profile.getScaffoldSamples() - state.baselineScaffoldSamples);
        int deltaHoverFly = Math.max(0, profile.getHoverFlySamples() - state.baselineHoverFlySamples);
        int deltaXray = Math.max(0, profile.getXraySamples() - state.baselineXraySamples);
        if (!english) {
            return "delta_nghi_ngo=" + deltaSuspicion
                    + ", delta_hack_conf=" + deltaHackConfidence
                    + ", canh_bao_moi=" + deltaAlerts
                    + ", tin_hieu_aim=" + deltaAim
                    + ", tin_hieu_move=" + deltaMove
                    + ", tin_hieu_cps=" + deltaHighCps
                    + ", tin_hieu_spam=" + deltaChatSpam
                    + ", tin_hieu_scaffold=" + deltaScaffold
                    + ", tin_hieu_fly=" + deltaHoverFly
                    + ", tin_hieu_xray=" + deltaXray
                    + ", round_manh=" + state.strongSignalRounds
                    + ", round_rat_manh=" + state.severeSignalRounds;
        }
        return english
                ? "delta_suspicion=" + deltaSuspicion
                + ", delta_hack_conf=" + deltaHackConfidence
                + ", new_alerts=" + deltaAlerts
                + ", aim_signals=" + deltaAim
                + ", move_signals=" + deltaMove
                + ", cps_signals=" + deltaHighCps
                + ", spam_signals=" + deltaChatSpam
                + ", scaffold_signals=" + deltaScaffold
                + ", fly_signals=" + deltaHoverFly
                + ", xray_signals=" + deltaXray
                + ", strong_rounds=" + state.strongSignalRounds
                + ", severe_rounds=" + state.severeSignalRounds
                : "delta_nghi_ngo=" + deltaSuspicion
                + ", delta_hack_conf=" + deltaHackConfidence
                + ", canh_bao_moi=" + deltaAlerts
                + ", tin_hieu_aim=" + deltaAim
                + ", tin_hieu_move=" + deltaMove
                + ", tin_hieu_cps=" + deltaHighCps;
    }

    private void applyActionPipeline(Player player, PlayerRiskProfile profile, RiskTier tier, SkillClass skillClass) {
        if (!plugin.getConfig().getBoolean("actions.pipeline.enabled", true)) {
            return;
        }
        String playerName = player.getName();
        int suspicion = profile.getSuspicion();

        RiskTier warnTier = parseTier(plugin.getConfig().getString("actions.pipeline.warn_tier", "WATCH"), RiskTier.WATCH);
        RiskTier flagTier = parseTier(plugin.getConfig().getString("actions.pipeline.flag_tier", "ALERT"), RiskTier.ALERT);
        RiskTier kickTier = parseTier(plugin.getConfig().getString("actions.pipeline.kick_tier", "DANGER"), RiskTier.DANGER);
        RiskTier banTier = parseTier(plugin.getConfig().getString("actions.pipeline.ban_tier", "SEVERE"), RiskTier.SEVERE);

        if (plugin.getConfig().getBoolean("actions.pipeline.do_warn", true)
                && tier.ordinal() >= warnTier.ordinal()
                && canRunAction(playerName, "warn")) {
            aiChat.sendStaffNotice(formatPipelineMessage(
                    plugin.getConfig().getString("actions.pipeline.messages.warn", "&e[Warn] {player} tier={tier} suspicion={suspicion}"),
                    playerName, tier, suspicion, skillClass
            ));
        }

        if (plugin.getConfig().getBoolean("actions.pipeline.do_flag", true)
                && tier.ordinal() >= flagTier.ordinal()
                && canRunAction(playerName, "flag")) {
            aiChat.sendStaffNotice(formatPipelineMessage(
                    plugin.getConfig().getString("actions.pipeline.messages.flag", "&6[Flag] {player} tier={tier} suspicion={suspicion}"),
                    playerName, tier, suspicion, skillClass
            ));
        }

        boolean protectLikelyPro = plugin.getConfig().getBoolean("actions.pipeline.protection.relax_for_likely_pro", true);
        if (protectLikelyPro && skillClass == SkillClass.PRO && tier.ordinal() <= RiskTier.ALERT.ordinal()) {
            return;
        }

        if (plugin.getConfig().getBoolean("actions.pipeline.do_kick", true)
                && tier.ordinal() >= kickTier.ordinal()
                && canRunAction(playerName, "kick")) {
            if (!isProtectedOperator(playerName)) {
                String kickMessage = plugin.getConfig().getString("actions.kick_message", "AIAdmin phát hiện hành vi bất thường. Vui lòng chờ staff kiểm tra.");
                if (kickPlayerByName(playerName, kickMessage) == BanResult.SUCCESS) {
                    aiChat.sendStaffNotice(formatPipelineMessage(
                            plugin.getConfig().getString("actions.pipeline.messages.kick", "&c[Kick] {player} tier={tier} suspicion={suspicion}"),
                            playerName, tier, suspicion, skillClass
                    ));
                }
            }
        }

        boolean autoTempbanHack = plugin.getConfig().getBoolean("actions.pipeline.auto_tempban_hack_likely", true);
        RiskTier tempbanTier = parseTier(plugin.getConfig().getString("actions.pipeline.tempban_tier", "DANGER"), RiskTier.DANGER);
        if (autoTempbanHack
                && tier.ordinal() >= tempbanTier.ordinal()
                && skillClass == SkillClass.HACK_LIKELY
                && canRunAction(playerName, "tempban_hack")) {
            if (!isProtectedOperator(playerName)) {
                String reason = plugin.getConfig().getString("actions.pipeline.hack_tempban_reason", "AI phát hiện hành vi giống hack");
                String tempbanDuration = getAiBanDuration("12h");
                if (plugin.getLitebanConfig() != null) {
                    tempbanDuration = getAiBanConfigString("ai_ban.default_duration", "liteban.default_duration", tempbanDuration);
                }
                String safeTempbanDuration = normalizeDuration(tempbanDuration);
                tempBanPlayerByName(playerName, safeTempbanDuration, reason);
                aiChat.sendStaffNotice(formatPipelineMessage(
                        plugin.getConfig().getString("actions.pipeline.messages.ban", "&4[Ban] {player} tier={tier} suspicion={suspicion}"),
                        playerName, tier, suspicion, skillClass
                ) + color(" &7(tempban " + safeTempbanDuration + ")"));
            }
        }

        if (plugin.getConfig().getBoolean("actions.pipeline.do_ban", false)
                && tier.ordinal() >= banTier.ordinal()
                && canRunAction(playerName, "ban")) {
            if (!isProtectedOperator(playerName)) {
                String reason = plugin.getConfig().getString("actions.pipeline.ban_reason", "AI phát hiện hành vi gian lận mức rủi ro cao");
                banPlayerByName(playerName, reason);
                aiChat.sendStaffNotice(formatPipelineMessage(
                        plugin.getConfig().getString("actions.pipeline.messages.ban", "&4[Ban] {player} tier={tier} suspicion={suspicion}"),
                        playerName, tier, suspicion, skillClass
                ));
            }
        }
    }

    private boolean canRunAction(String playerName, String actionName) {
        int cooldownSeconds = Math.max(5, plugin.getConfig().getInt("actions.pipeline.action_cooldown_seconds", 45));
        String key = playerName.toLowerCase(Locale.ROOT) + ":" + actionName.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long last = actionCooldowns.getOrDefault(key, 0L);
        if (now - last < cooldownSeconds * 1000L) {
            return false;
        }
        actionCooldowns.put(key, now);
        return true;
    }

    private boolean consumeObservationAmplify(String playerName, String actionName, long cooldownMillis) {
        String key = playerName.toLowerCase(Locale.ROOT) + ":" + actionName.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long last = observationAmplifyCooldowns.getOrDefault(key, 0L);
        if (now - last < cooldownMillis) {
            return false;
        }
        observationAmplifyCooldowns.put(key, now);
        return true;
    }

    private String formatPipelineMessage(String template, String player, RiskTier tier, int suspicion, SkillClass skillClass) {
        return color(template
                .replace("{player}", player)
                .replace("{tier}", tier.name())
                .replace("{suspicion}", String.valueOf(suspicion))
                .replace("{skill}", skillClass.name()));
    }

    private BanResult applyInternalTempBan(String playerName, String reason, String duration) {
        if (isProtectedOperator(playerName)) {
            return BanResult.BLOCKED_OP;
        }

        String safeDuration = normalizeDuration(duration);
        Date expiresAt = Date.from(Instant.now().plusMillis(parseDurationMillis(safeDuration)));
        Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, expiresAt, "AIAdmin");

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            online.kickPlayer("Thời gian cấm: " + safeDuration + " | Lý do: " + reason);
        }
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordTempBan(playerName);
        }
        sendAiBanNotice("info", "Đã tempban nội bộ " + playerName + " trong " + safeDuration + ".");
        logBan(playerName, reason, safeDuration, "internal-tempban", false);
        return BanResult.SUCCESS;
    }

    private boolean isProtectedOperator(String playerName) {
        boolean exemptOps = getAiBanConfigBoolean("ai_ban.exempt_ops", "liteban.exempt_ops", true);
        if (!exemptOps) {
            return false;
        }

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOp()) {
            return true;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        return offline != null && offline.isOp();
    }

    public boolean isDurationToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2) {
            return false;
        }
        char unit = normalized.charAt(normalized.length() - 1);
        if (unit != 's' && unit != 'm' && unit != 'h' && unit != 'd') {
            return false;
        }
        try {
            return Integer.parseInt(normalized.substring(0, normalized.length() - 1)) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public String normalizeDuration(String duration) {
        if (!isDurationToken(duration)) {
            return "12h";
        }
        String normalized = duration.trim().toLowerCase(Locale.ROOT);
        int value = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
        char unit = normalized.charAt(normalized.length() - 1);
        long millis = switch (unit) {
            case 's' -> value * 1_000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            default -> value * 86_400_000L;
        };
        long maxMillis = 45L * 86_400_000L;
        millis = Math.max(1_000L, Math.min(maxMillis, millis));

        if (millis % 86_400_000L == 0L) {
            return (millis / 86_400_000L) + "d";
        }
        if (millis % 3_600_000L == 0L) {
            return (millis / 3_600_000L) + "h";
        }
        if (millis % 60_000L == 0L) {
            return Math.max(1L, millis / 60_000L) + "m";
        }
        return Math.max(1L, millis / 1_000L) + "s";
    }

    private long parseDurationMillis(String duration) {
        String normalized = normalizeDuration(duration);
        int value = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
        char unit = normalized.charAt(normalized.length() - 1);
        return switch (unit) {
            case 's' -> value * 1_000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            default -> value * 86_400_000L;
        };
    }

    private RiskTier parseTier(String raw, RiskTier def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return RiskTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    private void logBan(String playerName, String reason, String duration, String method, boolean blockedOp) {
        if (plugin.getDatabaseManager() == null) {
            return;
        }
        plugin.getDatabaseManager().logBanEvent(playerName, reason, duration, method, blockedOp, "AIAdmin");
    }

    private void logBotEvent(String botName, String targetName, String reason, String eventType, Player targetPlayer) {
        if (plugin.getDatabaseManager() == null || targetPlayer == null || targetPlayer.getLocation() == null || targetPlayer.getWorld() == null) {
            return;
        }
        String location = targetPlayer.getWorld().getName() + "@"
                + targetPlayer.getLocation().getBlockX() + ","
                + targetPlayer.getLocation().getBlockY() + ","
                + targetPlayer.getLocation().getBlockZ();
        plugin.getDatabaseManager().logBotEvent(botName, targetName, reason, eventType, location);
    }

    private void sendAiBanNotice(String type, String message) {
        String prefix = getAiBanConfigString("ai_ban.colors.prefix", "liteban.colors.prefix", "&8[AI_ban] ");
        String typeColor;
        if ("success".equalsIgnoreCase(type)) {
            typeColor = getAiBanConfigString("ai_ban.colors.success", "liteban.colors.success", "&a");
        } else if ("warning".equalsIgnoreCase(type)) {
            typeColor = getAiBanConfigString("ai_ban.colors.warning", "liteban.colors.warning", "&6");
        } else if ("error".equalsIgnoreCase(type)) {
            typeColor = getAiBanConfigString("ai_ban.colors.error", "liteban.colors.error", "&c");
        } else {
            typeColor = getAiBanConfigString("ai_ban.colors.info", "liteban.colors.info", "&b");
        }

        String normalizedType = type == null ? "info" : type.toLowerCase(Locale.ROOT);
        String template = getAiBanConfigString("ai_ban.ui.templates." + normalizedType,
                "liteban.ui.templates." + normalizedType, "{prefix}{color}{message}");
        if (template == null || template.isBlank()) {
            template = "{prefix}{color}{message}";
        }
        String rendered = template
                .replace("{prefix}", prefix)
                .replace("{color}", typeColor)
                .replace("{message}", message);

        boolean useFrame = getAiBanConfigBoolean("ai_ban.ui.frame.enabled", "liteban.ui.frame.enabled", false);
        if (useFrame) {
            aiChat.sendStaffNotice(color(getAiBanConfigString("ai_ban.ui.frame.top", "liteban.ui.frame.top", "&8&m------------------------------")));
        }
        aiChat.sendStaffNotice(color(rendered));
        if (useFrame) {
            aiChat.sendStaffNotice(color(getAiBanConfigString("ai_ban.ui.frame.bottom", "liteban.ui.frame.bottom", "&8&m------------------------------")));
        }
    }

    private boolean isAiBanFeatureEnabled() {
        if (plugin.getConfig().contains("ai_ban.enabled")) {
            return plugin.getConfig().getBoolean("ai_ban.enabled", true);
        }
        return plugin.getConfig().getBoolean("litebans.enabled", true);
    }

    private String getAiBanDuration(String def) {
        String duration = plugin.getConfig().contains("ai_ban.ban_duration")
                ? plugin.getConfig().getString("ai_ban.ban_duration", def)
                : plugin.getConfig().getString("litebans.ban_duration", def);
        return getAiBanConfigString("ai_ban.default_duration", "liteban.default_duration", duration);
    }

    private String getAiBanReason(String def) {
        String reason = plugin.getConfig().contains("ai_ban.ban_reason")
                ? plugin.getConfig().getString("ai_ban.ban_reason", def)
                : plugin.getConfig().getString("litebans.ban_reason", def);
        return getAiBanConfigString("ai_ban.default_reason", "liteban.default_reason", reason);
    }

    private String getAiBanConfigString(String path, String legacyPath, String def) {
        if (plugin.getAiBanConfig() == null) {
            return def;
        }
        if (path != null && plugin.getAiBanConfig().contains(path)) {
            return plugin.getAiBanConfig().getString(path, def);
        }
        if (legacyPath != null && plugin.getAiBanConfig().contains(legacyPath)) {
            return plugin.getAiBanConfig().getString(legacyPath, def);
        }
        return def;
    }

    private boolean getAiBanConfigBoolean(String path, String legacyPath, boolean def) {
        if (plugin.getAiBanConfig() == null) {
            return def;
        }
        if (path != null && plugin.getAiBanConfig().contains(path)) {
            return plugin.getAiBanConfig().getBoolean(path, def);
        }
        if (legacyPath != null && plugin.getAiBanConfig().contains(legacyPath)) {
            return plugin.getAiBanConfig().getBoolean(legacyPath, def);
        }
        return def;
    }

    private String color(String input) {
        return input.replace("&", "\u00A7");
    }

    private int getOptionInt(String path, int def) {
        return plugin.getOptionConfig() == null ? def : plugin.getOptionConfig().getInt(path, def);
    }

    private boolean getOptionBoolean(String path, boolean def) {
        return plugin.getOptionConfig() == null ? def : plugin.getOptionConfig().getBoolean(path, def);
    }
}
