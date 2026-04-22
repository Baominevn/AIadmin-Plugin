package me.aiadmin.system;

import me.aiadmin.AIAdmin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SuspicionManager {

    public enum RiskTier {
        CLEAR,
        WATCH,
        ALERT,
        DANGER,
        SEVERE
    }

    public enum ThreatLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum SkillClass {
        PRO,
        BALANCED,
        HACK_LIKELY
    }

    public enum BehaviorState {
        IDLE,
        COMBAT,
        MINING,
        BRIDGING,
        PARKOUR,
        KNOCKBACK,
        LAGGED
    }

    public static class PlayerRiskProfile {
        public static class EvidenceEntry {
            private final long timestamp;
            private final String source;
            private final String detail;
            private final int points;
            private final String locationSummary;
            private final boolean alert;
            private final String stateName;

            private EvidenceEntry(long timestamp, String source, String detail, int points, String locationSummary, boolean alert, String stateName) {
                this.timestamp = timestamp;
                this.source = source;
                this.detail = detail;
                this.points = points;
                this.locationSummary = locationSummary;
                this.alert = alert;
                this.stateName = stateName;
            }

            public long getTimestamp() {
                return timestamp;
            }

            public String getSource() {
                return source;
            }

            public String getDetail() {
                return detail;
            }

            public int getPoints() {
                return points;
            }

            public String getLocationSummary() {
                return locationSummary;
            }

            public boolean isAlert() {
                return alert;
            }

            public String getStateName() {
                return stateName;
            }
        }

        private final String name;
        private int suspicion;
        private int totalAlerts;
        private String lastKnownIp;
        private String lastSuspiciousWorld;
        private double lastSuspiciousX;
        private double lastSuspiciousY;
        private double lastSuspiciousZ;
        private long lastSuspiciousAt;

        private double totalDistance;
        private double maxMoveDelta;
        private int movementSamples;

        private int hackConfidence;
        private int proConfidence;
        private int suspiciousAimSamples;
        private int suspiciousMoveSamples;
        private int highCpsSamples;
        private int peakCps;
        private int chatSpamSamples;
        private int scaffoldSamples;
        private int hoverFlySamples;
        private int xraySamples;
        private int legitCombatSamples;
        private long lastCombatMillis;
        private long lastMoveMillis;
        private long lastClickMillis;
        private long lastMiningMillis;
        private long lastBlockPlaceMillis;
        private long lastKnockbackMillis;
        private long lastLagMillis;
        private long lastParkourMillis;
        private BehaviorState lastBehaviorState = BehaviorState.IDLE;

        private final Map<String, Integer> alertCounts = new HashMap<>();
        private final Deque<String> recentReasons = new ArrayDeque<>();
        private final Deque<EvidenceEntry> recentEvidence = new ArrayDeque<>();
        private final Deque<Long> clickTimes = new ArrayDeque<>();
        private final Deque<Long> chatTimes = new ArrayDeque<>();
        private final Deque<String> recentChatMessages = new ArrayDeque<>();
        private final Deque<Long> scaffoldTimes = new ArrayDeque<>();
        private final Deque<Long> xrayHiddenTimes = new ArrayDeque<>();

        public PlayerRiskProfile(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getSuspicion() {
            return suspicion;
        }

        public int getTotalAlerts() {
            return totalAlerts;
        }

        public String getLastKnownIp() {
            return lastKnownIp;
        }

        public long getLastSuspiciousAt() {
            return lastSuspiciousAt;
        }

        public String getLastSuspiciousLocationSummary() {
            return getLastSuspiciousLocationSummary(false);
        }

        public String getLastSuspiciousLocationSummary(boolean english) {
            if (lastSuspiciousWorld == null || lastSuspiciousWorld.isBlank()) {
                return english ? "unknown" : "chưa rõ";
            }
            return lastSuspiciousWorld + " @ "
                    + round(lastSuspiciousX) + ", "
                    + round(lastSuspiciousY) + ", "
                    + round(lastSuspiciousZ);
        }

        public Map<String, Integer> getAlertCounts() {
            return Collections.unmodifiableMap(alertCounts);
        }

        public int getHackConfidence() {
            return hackConfidence;
        }

        public int getProConfidence() {
            return proConfidence;
        }

        public int getSuspiciousAimSamples() {
            return suspiciousAimSamples;
        }

        public int getHighCpsSamples() {
            return highCpsSamples;
        }

        public int getCurrentCps() {
            return getCps(System.currentTimeMillis(), 1000L);
        }

        public int getPeakCps() {
            return getPeakCps(System.currentTimeMillis());
        }

        public int getSuspiciousMoveSamples() {
            return suspiciousMoveSamples;
        }

        public int getChatSpamSamples() {
            return chatSpamSamples;
        }

        public int getScaffoldSamples() {
            return scaffoldSamples;
        }

        public int getHoverFlySamples() {
            return hoverFlySamples;
        }

        public int getXraySamples() {
            return xraySamples;
        }

        public int getLegitCombatSamples() {
            return legitCombatSamples;
        }

        public long getLastCombatMillis() {
            return lastCombatMillis;
        }

        public long getLastMiningMillis() {
            return lastMiningMillis;
        }

        public long getLastBlockPlaceMillis() {
            return lastBlockPlaceMillis;
        }

        public long getLastKnockbackMillis() {
            return lastKnockbackMillis;
        }

        public long getLastLagMillis() {
            return lastLagMillis;
        }

        public long getLastParkourMillis() {
            return lastParkourMillis;
        }

        public BehaviorState getLastBehaviorState() {
            return lastBehaviorState;
        }

        public void setLastKnownIp(String lastKnownIp) {
            this.lastKnownIp = lastKnownIp;
        }

        public void markSuspiciousLocation(Location location) {
            if (location == null || location.getWorld() == null) {
                return;
            }
            this.lastSuspiciousWorld = location.getWorld().getName();
            this.lastSuspiciousX = location.getX();
            this.lastSuspiciousY = location.getY();
            this.lastSuspiciousZ = location.getZ();
            this.lastSuspiciousAt = System.currentTimeMillis();
        }

        public void setLastCombatMillis(long lastCombatMillis) {
            this.lastCombatMillis = lastCombatMillis;
        }

        public void setLastBehaviorState(BehaviorState lastBehaviorState) {
            this.lastBehaviorState = lastBehaviorState == null ? BehaviorState.IDLE : lastBehaviorState;
        }

        public void markMining(long nowMillis) {
            this.lastMiningMillis = nowMillis;
        }

        public void markBlockPlace(long nowMillis) {
            this.lastBlockPlaceMillis = nowMillis;
        }

        public void markKnockback(long nowMillis) {
            this.lastKnockbackMillis = nowMillis;
        }

        public void markLag(long nowMillis) {
            this.lastLagMillis = nowMillis;
        }

        public void markParkour(long nowMillis) {
            this.lastParkourMillis = nowMillis;
        }

        public void addSuspicion(int amount, String reason) {
            int adjustedAmount = applySuspicionCurve(amount);
            suspicion = Math.max(0, Math.min(300, suspicion + adjustedAmount));
            if (reason != null && !reason.isEmpty()) {
                recentReasons.addFirst(reason);
                while (recentReasons.size() > 8) {
                    recentReasons.removeLast();
                }
            }
        }

        public void reduceSuspicion(int amount) {
            suspicion = Math.max(0, suspicion - Math.max(0, amount));
        }

        public void registerAlert(String type, int amount, String detail) {
            totalAlerts++;
            alertCounts.merge(type.toLowerCase(Locale.ROOT), 1, Integer::sum);
            addSuspicion(amount, type + ": " + detail);
        }

        public void addEvidence(String source, String detail, int points, boolean alert, boolean english) {
            String safeSource = source == null || source.isBlank() ? "unknown" : source;
            String safeDetail = detail == null || detail.isBlank() ? (english ? "no details" : "không có chi tiết") : detail;
            String location = getLastSuspiciousLocationSummary(english);
            recentEvidence.addFirst(new EvidenceEntry(
                    System.currentTimeMillis(),
                    safeSource,
                    safeDetail,
                    points,
                    location,
                    alert,
                    lastBehaviorState.name()
            ));
            while (recentEvidence.size() > 10) {
                recentEvidence.removeLast();
            }
        }

        public List<EvidenceEntry> getRecentEvidence(int limit) {
            List<EvidenceEntry> entries = new ArrayList<>();
            int count = 0;
            for (EvidenceEntry entry : recentEvidence) {
                entries.add(entry);
                count++;
                if (count >= Math.max(1, limit)) {
                    break;
                }
            }
            return entries;
        }

        public long recordMove(double delta, long nowMillis) {
            long elapsed = lastMoveMillis <= 0L ? 50L : Math.max(1L, nowMillis - lastMoveMillis);
            lastMoveMillis = nowMillis;
            movementSamples++;
            totalDistance += delta;
            maxMoveDelta = Math.max(maxMoveDelta, delta);
            return elapsed;
        }

        public double consumeMaxMoveDelta() {
            double snapshot = maxMoveDelta;
            maxMoveDelta = 0D;
            return snapshot;
        }

        public void recordClick(long nowMillis) {
            if (lastClickMillis <= 0L || nowMillis - lastClickMillis > 1500L) {
                peakCps = 0;
            }
            lastClickMillis = nowMillis;
            clickTimes.addLast(nowMillis);
            trimClicks(nowMillis, 3000L);
            peakCps = Math.max(peakCps, getCps(nowMillis, 1000L));
        }

        public int getCps(long nowMillis, long windowMillis) {
            trimClicks(nowMillis, windowMillis);
            return clickTimes.size();
        }

        public int getPeakCps(long nowMillis) {
            trimClicks(nowMillis, 3000L);
            if (clickTimes.isEmpty() || lastClickMillis <= 0L || nowMillis - lastClickMillis > 1500L) {
                peakCps = 0;
                return 0;
            }

            List<Long> timestamps = new ArrayList<>(clickTimes);
            int peak = 0;
            int left = 0;
            for (int right = 0; right < timestamps.size(); right++) {
                long rightTime = timestamps.get(right);
                while (left <= right && rightTime - timestamps.get(left) >= 1000L) {
                    left++;
                }
                peak = Math.max(peak, right - left + 1);
            }
            peakCps = peak;
            return peak;
        }

        public void markSuspiciousAim() {
            suspiciousAimSamples++;
        }

        public void markSuspiciousMove() {
            suspiciousMoveSamples++;
        }

        public void markHighCps() {
            highCpsSamples++;
        }

        public void markChatSpam() {
            chatSpamSamples++;
        }

        public void markScaffold() {
            scaffoldSamples++;
        }

        public void markHoverFly() {
            hoverFlySamples++;
        }

        public void markXray() {
            xraySamples++;
        }

        public void markLegitCombat() {
            legitCombatSamples++;
        }

        public void recordChat(long nowMillis, String message) {
            chatTimes.addLast(nowMillis);
            trimTimedQueue(chatTimes, nowMillis, 5000L);

            if (message != null && !message.isBlank()) {
                recentChatMessages.addLast(message.toLowerCase(Locale.ROOT));
                while (recentChatMessages.size() > 6) {
                    recentChatMessages.removeFirst();
                }
            }
            while (recentChatMessages.size() > chatTimes.size()) {
                recentChatMessages.removeFirst();
            }
        }

        public int getChatBurst(long nowMillis, long windowMillis) {
            trimTimedQueue(chatTimes, nowMillis, windowMillis);
            while (recentChatMessages.size() > chatTimes.size()) {
                recentChatMessages.removeFirst();
            }
            return chatTimes.size();
        }

        public int countRecentRepeatedMessages(String message) {
            if (message == null || message.isBlank()) {
                return 0;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            int repeats = 0;
            for (String entry : recentChatMessages) {
                if (normalized.equals(entry)) {
                    repeats++;
                }
            }
            return repeats;
        }

        public void recordScaffoldPlace(long nowMillis) {
            scaffoldTimes.addLast(nowMillis);
            trimTimedQueue(scaffoldTimes, nowMillis, 2500L);
        }

        public int getScaffoldBurst(long nowMillis, long windowMillis) {
            trimTimedQueue(scaffoldTimes, nowMillis, windowMillis);
            return scaffoldTimes.size();
        }

        public void recordXrayHidden(long nowMillis) {
            xrayHiddenTimes.addLast(nowMillis);
            trimTimedQueue(xrayHiddenTimes, nowMillis, 600_000L);
        }

        public int getXrayHiddenCount(long nowMillis, long windowMillis) {
            trimTimedQueue(xrayHiddenTimes, nowMillis, windowMillis);
            return xrayHiddenTimes.size();
        }

        public void boostHackConfidence(int amount) {
            hackConfidence = clamp100(hackConfidence + Math.max(0, amount));
            if (amount > 0) {
                proConfidence = clamp100(proConfidence - Math.max(0, amount / 3));
            }
        }

        public void boostProConfidence(int amount) {
            proConfidence = clamp100(proConfidence + Math.max(0, amount));
            if (amount > 0) {
                hackConfidence = clamp100(hackConfidence - Math.max(0, amount / 4));
            }
        }

        public String describeMovement() {
            return describeMovement(false);
        }

        public String describeMovement(boolean english) {
            if (movementSamples == 0) {
                return english ? "no samples yet" : "chưa có mẫu";
            }
            double average = totalDistance / movementSamples;
            return english
                    ? "avg=" + round(average) + ", max=" + round(maxMoveDelta) + ", samples=" + movementSamples
                    : "trung bình=" + round(average) + ", tối đa=" + round(maxMoveDelta) + ", mẫu=" + movementSamples;
        }

        public String describeLearning() {
            return describeLearning(false);
        }

        public String describeLearning(boolean english) {
            return english
                    ? "hack_conf=" + hackConfidence
                    + ", pro_conf=" + proConfidence
                    + ", aim=" + suspiciousAimSamples
                    + ", high_cps=" + highCpsSamples
                    + ", legit_combat=" + legitCombatSamples
                    : "hack_conf=" + hackConfidence
                    + ", pro_conf=" + proConfidence
                    + ", aim=" + suspiciousAimSamples
                    + ", cps_cao=" + highCpsSamples
                    + ", combat_hop_le=" + legitCombatSamples;
        }

        private void trimClicks(long nowMillis, long windowMillis) {
            while (!clickTimes.isEmpty() && nowMillis - clickTimes.peekFirst() > windowMillis) {
                clickTimes.removeFirst();
            }
        }

        private void trimTimedQueue(Deque<Long> queue, long nowMillis, long windowMillis) {
            while (!queue.isEmpty() && nowMillis - queue.peekFirst() > windowMillis) {
                queue.removeFirst();
            }
        }

        private int clamp100(int value) {
            return Math.max(0, Math.min(100, value));
        }

        private int applySuspicionCurve(int amount) {
            int safeAmount = Math.max(0, amount);
            if (safeAmount <= 0) {
                return 0;
            }
            if (suspicion >= 240) {
                return 1;
            }
            if (suspicion >= 160) {
                return Math.max(1, safeAmount / 3);
            }
            if (suspicion >= 100) {
                return Math.max(1, safeAmount / 2);
            }
            if (suspicion >= 60) {
                return Math.max(1, (int) Math.round(safeAmount * 0.7D));
            }
            return safeAmount;
        }

        private String round(double value) {
            return String.format(Locale.US, "%.2f", value);
        }
    }

    private static class IpProfile {
        private final Set<String> accounts = new HashSet<>();
        private final Deque<Long> joinTimes = new ArrayDeque<>();
        private boolean flagged;
    }

    private static final Set<Material> XRAY_ORES = EnumSet.of(
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS
    );

    private final AIAdmin plugin;
    private final Map<String, PlayerRiskProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, IpProfile> ipProfiles = new ConcurrentHashMap<>();
    private final Map<String, Long> movementSpikeCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> behaviorSignalCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Integer> hoverFlyStreaks = new ConcurrentHashMap<>();
    private int recordedAlertCount;
    private BukkitRunnable decayTask;

    public SuspicionManager(AIAdmin plugin) {
        this.plugin = plugin;
    }

    public void startLearningTasks() {
        stopLearningTasks();
        if (plugin.getOptionConfig() == null) {
            return;
        }
        if (!plugin.getOptionConfig().getBoolean("anticheat.suspicion_decay.enabled", true)) {
            return;
        }

        int intervalMinutes = Math.max(1, plugin.getOptionConfig().getInt("anticheat.suspicion_decay.interval_minutes", 20));
        int decayPoints = Math.max(1, plugin.getOptionConfig().getInt("anticheat.suspicion_decay.points_per_interval", 1));
        long periodTicks = intervalMinutes * 60L * 20L;

        decayTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (PlayerRiskProfile profile : profiles.values()) {
                    SkillClass skillClass = getSkillClass(profile);
                    if (skillClass == SkillClass.PRO && profile.getSuspicion() > 0) {
                        profile.reduceSuspicion(decayPoints);
                    }
                }
            }
        };
        decayTask.runTaskTimer(plugin, periodTicks, periodTicks);
    }

    public void stopLearningTasks() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    public void handleJoin(Player player) {
        String playerName = player.getName();
        PlayerRiskProfile profile = getOrCreateProfile(playerName);
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return;
        }

        String ip = address.getAddress().getHostAddress();
        profile.setLastKnownIp(ip);

        IpProfile ipProfile = ipProfiles.computeIfAbsent(ip, key -> new IpProfile());
        ipProfile.accounts.add(playerName.toLowerCase(Locale.ROOT));
        ipProfile.joinTimes.addLast(Instant.now().toEpochMilli());
        trimOldJoins(ipProfile.joinTimes);

        int maxAccounts = plugin.getConfig().getInt("scan.max_accounts_per_ip", 4);
        int joinBurstLimit = plugin.getConfig().getInt("scan.join_burst_limit", 3);

        if (ipProfile.accounts.size() > maxAccounts) {
            ipProfile.flagged = true;
            addBehaviorSuspicion(profile, "alt-account", plugin.getConfig().getInt("suspicion.points.alt_account", 4), "too-many-accounts-on-ip");
            notifyStaff(playerName + " đang dùng IP có " + ipProfile.accounts.size() + " tài khoản.");
        }

        if (ipProfile.joinTimes.size() >= joinBurstLimit) {
            ipProfile.flagged = true;
            addBehaviorSuspicion(profile, "join-burst", plugin.getConfig().getInt("suspicion.points.join_burst", 3), "join-burst-detected");
            notifyStaff("Phát hiện đăng nhập dồn dập trên cùng IP của " + playerName + ".");
        }

        if (plugin.getConfig().getBoolean("scan.warn_player_on_alt_limit", true) && ipProfile.accounts.size() > maxAccounts) {
            player.sendMessage(color("&cHệ thống phát hiện quá nhiều tài khoản trên cùng IP. Staff sẽ kiểm tra thêm."));
        }
    }

    public void handleQuit(Player player) {
        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        InetSocketAddress address = player.getAddress();
        if (address != null && address.getAddress() != null) {
            profile.setLastKnownIp(address.getAddress().getHostAddress());
        }
    }

    public void captureMovement(Player player, Location from, Location to) {
        if (player == null || from == null || to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        if (!shouldTrackBehavior(player)) {
            return;
        }

        double delta = from.distance(to);
        if (delta <= 0D) {
            return;
        }
        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        long elapsedMillis = profile.recordMove(delta, now);

        double horizontal = Math.sqrt(Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2));
        double verticalUp = Math.max(0D, to.getY() - from.getY());
        double tickScale = Math.max(1.0D, elapsedMillis / 50.0D);
        double horizontalPerTick = horizontal / tickScale;
        double verticalUpPerTick = verticalUp / tickScale;
        boolean movementGrace = hasMovementGrace(player, elapsedMillis);
        if (movementGrace) {
            profile.markLag(now);
        }
        if (!movementGrace
                && !player.isOnGround()
                && verticalUpPerTick > 0.12D
                && verticalUpPerTick < 0.55D
                && horizontalPerTick > 0.18D
                && profile.getLastCombatMillis() > 0L
                && now - profile.getLastCombatMillis() > Math.max(800L, getOptionInt("anticheat.behavior.recent_combat_click_window_ms", 700) + 300L)) {
            profile.markParkour(now);
        }
        BehaviorState state = getBehaviorState(profile, player, now);
        profile.setLastBehaviorState(state);
        if (state == BehaviorState.KNOCKBACK || state == BehaviorState.LAGGED) {
            profile.boostProConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "move-grace", horizontalPerTick, 1);
            }
            observeLiveState(player);
            return;
        }

        double speedThreshold = getOptionDouble("anticheat.behavior.speed_threshold_blocks_per_tick", 0.95D);
        double flyUpThreshold = getOptionDouble("anticheat.behavior.vertical_up_threshold", 0.75D);
        double tunedSpeedThreshold = Math.max(0.65D, speedThreshold * 0.92D);
        double burstSpeedThreshold = Math.max(tunedSpeedThreshold + 0.15D, tunedSpeedThreshold * 1.22D);
        double tunedFlyUpThreshold = Math.max(0.45D, flyUpThreshold * 0.90D);
        if (state == BehaviorState.MINING) {
            tunedSpeedThreshold *= 1.10D;
            tunedFlyUpThreshold *= 1.15D;
        } else if (state == BehaviorState.BRIDGING) {
            tunedSpeedThreshold *= 1.08D;
            tunedFlyUpThreshold *= 1.12D;
        } else if (state == BehaviorState.PARKOUR) {
            tunedSpeedThreshold *= 1.18D;
            tunedFlyUpThreshold *= 1.30D;
        }
        if (!movementGrace && horizontalPerTick > tunedSpeedThreshold) {
            profile.markSuspiciousLocation(to);
            profile.markSuspiciousMove();
            if (consumeBehaviorCooldown(profile.getName(), "speed", 1_500L)) {
                addBehaviorSuspicion(profile, "speed", plugin.getConfig().getInt("suspicion.points.speed", 5), "horizontal=" + round(horizontalPerTick));
                if (plugin.getLearningManager() != null) {
                    plugin.getLearningManager().recordHackSignal(profile.getName(), "speed-move", horizontalPerTick, 2);
                }
            }
            if (horizontalPerTick > burstSpeedThreshold && consumeBehaviorCooldown(profile.getName(), "speed-burst", 3_000L)) {
                profile.markSuspiciousLocation(to);
                addBehaviorSuspicion(profile, "speed-burst", 1, "burst-horizontal=" + round(horizontalPerTick));
                profile.boostHackConfidence(2);
                if (plugin.getLearningManager() != null) {
                    plugin.getLearningManager().recordHackSignal(profile.getName(), "speed-burst", horizontalPerTick, 2);
                }
            }
        } else {
            profile.boostProConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "move-legit", horizontalPerTick, 1);
            }
        }
        if (!movementGrace
                && verticalUpPerTick > tunedFlyUpThreshold
                && !player.isOnGround()
                && !player.getAllowFlight()
                && consumeBehaviorCooldown(profile.getName(), "fly", 2_000L)) {
            profile.markSuspiciousLocation(to);
            profile.markSuspiciousMove();
            addBehaviorSuspicion(profile, "fly", plugin.getConfig().getInt("suspicion.points.fly", 6), "vertical-up=" + round(verticalUpPerTick));
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordHackSignal(profile.getName(), "fly-up", verticalUpPerTick, 2);
            }
        }

        double yawDelta = angleDistance(from.getYaw(), to.getYaw());
        double pitchDelta = Math.abs(to.getPitch() - from.getPitch());
        double snapYaw = Math.max(40.0D, getOptionDouble("anticheat.behavior.snap_yaw_threshold", 65.0D) * 0.90D);
        double snapPitch = Math.max(1.0D, getOptionDouble("anticheat.behavior.snap_pitch_threshold", 1.2D) * 1.15D);
        boolean combatState = state == BehaviorState.COMBAT;
        if (combatState && yawDelta > snapYaw && pitchDelta < snapPitch) {
            profile.markSuspiciousLocation(to);
            profile.markSuspiciousAim();
            if (profile.getSuspiciousAimSamples() % 2 == 0 && consumeBehaviorCooldown(profile.getName(), "aimassist", 1_200L)) {
                addBehaviorSuspicion(profile, "aimassist", plugin.getConfig().getInt("suspicion.points.aimassist", 5), "snap-aim");
            } else {
                profile.boostHackConfidence(1);
            }
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordHackSignal(profile.getName(), "aim-snap", yawDelta, 2);
            }
        } else if (yawDelta > 0.4D) {
            profile.boostProConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "aim-legit", yawDelta, 1);
            }
        }

        observeLiveState(player);
    }

    public void recordClick(Player player) {
        if (player == null || !shouldTrackBehavior(player)) {
            return;
        }
        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        profile.recordClick(now);
        BehaviorState state = getBehaviorState(profile, player, now);
        profile.setLastBehaviorState(state);
        int cps = profile.getCps(now, 1000L);
        boolean combatState = state == BehaviorState.COMBAT || isLookingAtTrackableTarget(player, 4.25D);
        if (!combatState) {
            return;
        }

        int warnCps = Math.max(11, getOptionInt("anticheat.behavior.cps_warn_threshold", 14));
        int flagCps = Math.max(warnCps + 2, getOptionInt("anticheat.behavior.cps_flag_threshold", 18));
        if (cps >= flagCps && consumeBehaviorCooldown(profile.getName(), "autoclicker", 1_200L)) {
            profile.markSuspiciousLocation(player.getLocation());
            profile.markHighCps();
            addBehaviorSuspicion(profile, "autoclicker", plugin.getConfig().getInt("suspicion.points.autoclicker", 4), "cps=" + cps);
            profile.boostHackConfidence(3);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordHackSignal(profile.getName(), "cps-flag", cps, 2);
            }
        } else if (cps >= warnCps && consumeBehaviorCooldown(profile.getName(), "click-cps", 2_000L)) {
            profile.markSuspiciousLocation(player.getLocation());
            profile.markHighCps();
            addBehaviorSuspicion(profile, "click-cps", 1, "cps=" + cps);
            profile.boostHackConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordHackSignal(profile.getName(), "cps-warn", cps, 1);
            }
        } else if (cps >= 6 && cps <= 12) {
            profile.markLegitCombat();
            profile.boostProConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "cps-legit", cps, 1);
            }
        }
    }

    public void recordCombatHit(Player damager, Player victim) {
        recordCombatInteraction(damager, victim);
    }

    public void recordCombatInteraction(Player damager, Entity victim) {
        if (damager == null || victim == null || !shouldTrackBehavior(damager) || !isTrackableCombatTarget(victim, damager)) {
            return;
        }
        PlayerRiskProfile profile = getOrCreateProfile(damager.getName());
        long now = System.currentTimeMillis();
        long previousCombatMillis = profile.getLastCombatMillis();
        profile.setLastBehaviorState(BehaviorState.COMBAT);

        double reach = damager.getEyeLocation().distance(victim.getLocation());
        double reachThreshold = Math.max(2.8D, getOptionDouble("anticheat.behavior.reach_threshold", 3.4D) - 0.10D);
        if (reach > reachThreshold && consumeBehaviorCooldown(profile.getName(), "reach", 1_250L)) {
            profile.markSuspiciousLocation(damager.getLocation());
            addBehaviorSuspicion(profile, "reach", plugin.getConfig().getInt("suspicion.points.reach", 5), "reach=" + round(reach));
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordHackSignal(profile.getName(), "reach", reach, 2);
            }
        } else {
            profile.boostProConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "reach-legit", reach, 1);
            }
        }

        int minInterval = Math.max(45, getOptionInt("anticheat.behavior.min_hit_interval_ms", 65) - 10);
        long hitInterval = previousCombatMillis > 0L ? now - previousCombatMillis : Long.MAX_VALUE;
        boolean severeInterval = hitInterval < Math.max(40, minInterval - 10);
        boolean corroborated = profile.getHighCpsSamples() >= 1 || profile.getSuspiciousAimSamples() >= 1;
        if (previousCombatMillis > 0L
                && hitInterval < minInterval
                && (severeInterval || corroborated)
                && consumeBehaviorCooldown(profile.getName(), "killaura", 1_500L)) {
            profile.markSuspiciousLocation(damager.getLocation());
            addBehaviorSuspicion(profile, "killaura", plugin.getConfig().getInt("suspicion.points.killaura", 6), "hit-interval-ms=" + hitInterval);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordHackSignal(profile.getName(), "hit-interval", hitInterval, 2);
            }
        } else {
            profile.boostProConfidence(1);
            profile.markLegitCombat();
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "combat-legit", minInterval, 1);
            }
        }
        profile.setLastCombatMillis(now);
    }

    public void recordReceivedDamage(Player victim, Entity damager) {
        if (victim == null || !shouldTrackBehavior(victim)) {
            return;
        }
        PlayerRiskProfile profile = getOrCreateProfile(victim.getName());
        long now = System.currentTimeMillis();
        profile.markKnockback(now);
        profile.setLastBehaviorState(BehaviorState.KNOCKBACK);
    }

    public void recordChatMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank() || !shouldTrackBehavior(player)) {
            return;
        }

        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        profile.recordChat(now, normalized);

        int burst = profile.getChatBurst(now, 4000L);
        int repeated = profile.countRecentRepeatedMessages(normalized);
        int burstThreshold = Math.max(4, getOptionInt("anticheat.behavior.chat_spam_burst_threshold", 5));
        int repeatThreshold = Math.max(3, getOptionInt("anticheat.behavior.chat_spam_repeat_threshold", 3));
        boolean excessiveLength = normalized.length() >= 120;
        boolean suspiciousSpam = burst >= burstThreshold || repeated >= repeatThreshold || (burst >= 3 && excessiveLength);
        if (!suspiciousSpam || !consumeBehaviorCooldown(profile.getName(), "chat-spam", 3_500L)) {
            return;
        }

        profile.markChatSpam();
        profile.markSuspiciousLocation(player.getLocation());
        int points = plugin.getConfig().getInt("suspicion.points.spam", 2);
        addBehaviorSuspicion(profile, "chat-spam", points, "burst=" + burst + ", repeat=" + repeated);
        profile.boostHackConfidence(repeated >= repeatThreshold ? 3 : 2);
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "chat-spam", burst, repeated >= repeatThreshold ? 2 : 1);
        }
    }

    public void recordBlockPlace(Player player, Location placedLocation, Location againstLocation) {
        if (player == null || placedLocation == null || !shouldTrackBehavior(player)) {
            return;
        }

        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        profile.markBlockPlace(now);
        profile.recordScaffoldPlace(now);

        Location playerLocation = player.getLocation();
        double dx = placedLocation.getX() + 0.5D - playerLocation.getX();
        double dz = placedLocation.getZ() + 0.5D - playerLocation.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        boolean belowFeet = placedLocation.getY() <= playerLocation.getY() - 1.0D;
        boolean closeToFeet = horizontalDistance <= 1.9D;
        double horizontalVelocity = player.getVelocity() == null ? 0.0D : Math.sqrt(
                (player.getVelocity().getX() * player.getVelocity().getX())
                        + (player.getVelocity().getZ() * player.getVelocity().getZ())
        );
        boolean moving = horizontalVelocity >= getOptionDouble("anticheat.behavior.scaffold_horizontal_speed_threshold", 0.12D);
        boolean airborne = !player.isOnGround();
        boolean lookingDown = playerLocation.getPitch() >= 35.0F;
        int burst = profile.getScaffoldBurst(now, 2500L);
        int burstThreshold = Math.max(4, getOptionInt("anticheat.behavior.scaffold_burst_threshold", 4));
        boolean suspicious = closeToFeet && belowFeet && lookingDown && burst >= burstThreshold && (moving || airborne);
        boolean legitBridge = closeToFeet
                && belowFeet
                && lookingDown
                && player.isOnGround()
                && !airborne
                && burst < burstThreshold
                && horizontalVelocity <= getOptionDouble("anticheat.behavior.scaffold_horizontal_speed_threshold", 0.12D) * 1.8D;
        if (closeToFeet && belowFeet && lookingDown) {
            profile.setLastBehaviorState(BehaviorState.BRIDGING);
        }
        if (legitBridge) {
            profile.boostProConfidence(1);
            if (plugin.getLearningManager() != null) {
                plugin.getLearningManager().recordLegitSignal(profile.getName(), "bridge-legit", burst, 1);
            }
        }

        if (!suspicious || !consumeBehaviorCooldown(profile.getName(), "scaffold", 2_200L)) {
            return;
        }

        profile.markScaffold();
        profile.markSuspiciousLocation(placedLocation);
        int points = plugin.getConfig().getInt("suspicion.points.scaffold", 3);
        addBehaviorSuspicion(profile, "scaffold", points, "burst=" + burst + ", moving=" + round(horizontalVelocity));
        profile.boostHackConfidence(3);
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "scaffold", burst, 2);
        }
    }

    public void recordBlockBreak(Player player, Block block) {
        if (player == null || block == null || !shouldTrackBehavior(player)) {
            return;
        }
        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        profile.markMining(now);
        profile.setLastBehaviorState(BehaviorState.MINING);
        if (!isXrayOre(block.getType())) {
            return;
        }

        int airFaces = countExposedFaces(block);
        int airThreshold = Math.max(0, getOptionInt("anticheat.behavior.xray_air_exposure_max", 1));
        if (airFaces > airThreshold) {
            return;
        }

        profile.recordXrayHidden(now);
        int windowSeconds = Math.max(120, getOptionInt("anticheat.behavior.xray_window_seconds", 600));
        int threshold = Math.max(3, getOptionInt("anticheat.behavior.xray_hidden_ore_threshold", 6));
        int hiddenCount = profile.getXrayHiddenCount(now, windowSeconds * 1000L);
        if (hiddenCount < threshold || !consumeBehaviorCooldown(profile.getName(), "xray", 8_000L)) {
            return;
        }

        profile.markXray();
        profile.markSuspiciousLocation(block.getLocation());
        int points = plugin.getConfig().getInt("suspicion.points.xray", 4);
        addBehaviorSuspicion(profile, "xray", points, "hidden_ore=" + hiddenCount);
        profile.boostHackConfidence(4);
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "xray", hiddenCount, 3);
        }
    }

    public void observeLiveState(Player player) {
        if (player == null || !shouldTrackBehavior(player)) {
            return;
        }

        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        profile.setLastBehaviorState(getBehaviorState(profile, player, now));
        String key = player.getName().toLowerCase(Locale.ROOT);

        if (player.isInsideVehicle()
                || player.isGliding()
                || player.isOnGround()
                || player.getAllowFlight()
                || player.isFlying()
                || player.getNoDamageTicks() >= Math.max(8, player.getMaximumNoDamageTicks() - 6)) {
            hoverFlyStreaks.remove(key);
            return;
        }

        double horizontalVelocity = player.getVelocity() == null ? 0.0D : Math.sqrt(
                (player.getVelocity().getX() * player.getVelocity().getX())
                        + (player.getVelocity().getZ() * player.getVelocity().getZ())
        );
        double verticalVelocity = player.getVelocity() == null ? 0.0D : Math.abs(player.getVelocity().getY());
        double horizontalThreshold = getOptionDouble("anticheat.behavior.hover_fly_horizontal_threshold", 0.10D);
        double verticalThreshold = getOptionDouble("anticheat.behavior.hover_fly_vertical_threshold", 0.08D);
        boolean hoveringForward = horizontalVelocity >= horizontalThreshold && verticalVelocity <= verticalThreshold;
        boolean unnaturalAirState = player.getFallDistance() <= 1.0F && hoveringForward;

        if (!unnaturalAirState) {
            hoverFlyStreaks.remove(key);
            return;
        }

        int streak = hoverFlyStreaks.merge(key, 1, Integer::sum);
        int streakThreshold = Math.max(5, getOptionInt("anticheat.behavior.hover_fly_streak_threshold", 6));
        if (streak < streakThreshold || !consumeBehaviorCooldown(profile.getName(), "fly-hover", 2_500L)) {
            return;
        }

        hoverFlyStreaks.put(key, 0);
        profile.markHoverFly();
        profile.markSuspiciousMove();
        profile.markSuspiciousLocation(player.getLocation());
        int points = plugin.getConfig().getInt("suspicion.points.fly_hover", plugin.getConfig().getInt("suspicion.points.fly", 3));
        addBehaviorSuspicion(profile, "fly-hover", points, "horizontal=" + round(horizontalVelocity) + ", vertical=" + round(verticalVelocity));
        profile.boostHackConfidence(3);
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "fly-hover", horizontalVelocity, 2);
        }
    }

    public void applyMovementHeuristics(String playerName) {
        PlayerRiskProfile profile = getOrCreateProfile(playerName);
        double maxMoveDelta = profile.consumeMaxMoveDelta();
        double threshold = Math.max(1.55D, plugin.getConfig().getDouble("scan.max_move_delta_watch", 1.55D));
        if (maxMoveDelta <= threshold) {
            return;
        }
        BehaviorState state = getBehaviorState(playerName);
        if (state == BehaviorState.KNOCKBACK || state == BehaviorState.LAGGED) {
            return;
        }

        boolean severeSpike = maxMoveDelta >= threshold + 0.75D;
        boolean corroborated = profile.getSuspiciousMoveSamples() >= 2
                || profile.getSuspiciousAimSamples() >= 1
                || profile.getHighCpsSamples() >= 1
                || profile.getTotalAlerts() >= 1;
        if (!severeSpike && !corroborated) {
            return;
        }

        String key = playerName.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long cooldownMillis = severeSpike ? 10_000L : 30_000L;
        long last = movementSpikeCooldowns.getOrDefault(key, 0L);
        if (now - last < cooldownMillis) {
            return;
        }
        movementSpikeCooldowns.put(key, now);

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            profile.markSuspiciousLocation(online.getLocation());
        }
        int points = severeSpike
                ? Math.max(1, plugin.getConfig().getInt("suspicion.points.movement_spike", 1))
                : 1;
        addBehaviorSuspicion(profile, "movement-spike", points, "max-delta=" + round(maxMoveDelta));
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "movement-spike", maxMoveDelta, severeSpike ? 3 : 1);
        }
    }

    public void addSuspicion(String playerName, int amount, String source, String detail) {
        PlayerRiskProfile profile = getOrCreateProfile(playerName);
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            profile.markSuspiciousLocation(online.getLocation());
            profile.setLastBehaviorState(getBehaviorState(profile, online));
        }
        int adjusted = adjustPointsByLearning(profile, amount);
        profile.addSuspicion(adjusted, source + ": " + detail);
        profile.addEvidence(source, detail, adjusted, false, plugin.getActiveConfigProfile() == AIAdmin.ConfigProfile.ENGLISH);
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordSuspicion(profile.getName());
        }
    }

    public void recordAlert(String playerName, String source, String type, int points, String detail) {
        PlayerRiskProfile profile = getOrCreateProfile(playerName);
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            profile.markSuspiciousLocation(online.getLocation());
            profile.setLastBehaviorState(getBehaviorState(profile, online));
        }
        int adjusted = adjustPointsByLearning(profile, points);
        profile.registerAlert(type, adjusted, source + " " + detail);
        profile.addEvidence(type, source + " | " + detail, adjusted, true, plugin.getActiveConfigProfile() == AIAdmin.ConfigProfile.ENGLISH);
        profile.boostHackConfidence(source.equalsIgnoreCase("console") ? 10 : 6);
        if (plugin.getLearningManager() != null) {
            plugin.getLearningManager().recordHackSignal(profile.getName(), "alert-" + type, adjusted, Math.max(2, adjusted));
        }
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordSuspicion(profile.getName());
        }
        if (plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().logAlertEvent(
                    profile.getName(),
                    source,
                    type,
                    adjusted,
                    getRiskTier(profile.getSuspicion()).name(),
                    detail
            );
        }
        recordedAlertCount++;

        RiskTier tier = getRiskTier(profile.getSuspicion());
        if (tier.ordinal() >= RiskTier.ALERT.ordinal()) {
            notifyStaff(playerName + " bị gắn cờ " + type + " từ " + source + " (+" + adjusted + ", tier=" + tier.name() + ").");
        }
    }

    public int getSuspicion(String playerName) {
        return getOrCreateProfile(playerName).getSuspicion();
    }

    public PlayerRiskProfile getOrCreateProfile(String playerName) {
        return profiles.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), key -> new PlayerRiskProfile(playerName));
    }

    public List<PlayerRiskProfile> getTopProfiles(int limit) {
        List<PlayerRiskProfile> values = new ArrayList<>(profiles.values());
        values.sort(Comparator.comparingInt(PlayerRiskProfile::getSuspicion).reversed());
        return values.subList(0, Math.min(limit, values.size()));
    }

    public List<PlayerRiskProfile> getSuspiciousProfiles(int limit) {
        List<PlayerRiskProfile> values = new ArrayList<>();
        for (PlayerRiskProfile profile : profiles.values()) {
            if (getRiskTier(profile.getSuspicion()).ordinal() >= RiskTier.WATCH.ordinal()) {
                values.add(profile);
            }
        }
        values.sort(Comparator.comparingInt(PlayerRiskProfile::getSuspicion).reversed());
        return values.subList(0, Math.min(limit, values.size()));
    }

    public SkillClass getSkillClass(String playerName) {
        return getSkillClass(getOrCreateProfile(playerName));
    }

    public SkillClass getSkillClass(PlayerRiskProfile profile) {
        int alertThreshold = plugin.getConfig().getInt("scan.suspicion_alert", 13);
        if (profile.getSuspicion() >= alertThreshold
                && profile.getHackConfidence() >= profile.getProConfidence() + 26
                && hasStrongHackEvidence(profile)) {
            return SkillClass.HACK_LIKELY;
        }
        if (profile.getProConfidence() >= profile.getHackConfidence() + 20
                && profile.getSuspicion() < alertThreshold) {
            return SkillClass.PRO;
        }
        return SkillClass.BALANCED;
    }

    public boolean hasStrongHackEvidence(PlayerRiskProfile profile) {
        if (profile == null) {
            return false;
        }

        int evidenceScore = 0;
        if (profile.getTotalAlerts() >= 1) {
            evidenceScore += 3;
        }
        if (profile.getSuspiciousAimSamples() >= 2) {
            evidenceScore += 2;
        }
        if (profile.getHighCpsSamples() >= 1) {
            evidenceScore += 2;
        }
        if (profile.getXraySamples() >= 1) {
            evidenceScore += 3;
        }
        if (profile.getHoverFlySamples() >= 1) {
            evidenceScore += 3;
        }
        if (profile.getScaffoldSamples() >= 1) {
            evidenceScore += 3;
        }
        if (profile.getChatSpamSamples() >= 2) {
            evidenceScore += 1;
        }
        if (profile.getSuspiciousMoveSamples() >= 3) {
            evidenceScore += 1;
        }
        if (profile.getHackConfidence() >= 45) {
            evidenceScore += 1;
        }
        if (profile.getSuspicion() >= plugin.getConfig().getInt("scan.suspicion_alert", 13)) {
            evidenceScore += 1;
        }
        return evidenceScore >= 4;
    }

    public ThreatLevel getThreatLevel(int suspicion) {
        RiskTier tier = getRiskTier(suspicion);
        if (tier == RiskTier.ALERT) {
            return ThreatLevel.MEDIUM;
        }
        if (tier == RiskTier.DANGER || tier == RiskTier.SEVERE) {
            return ThreatLevel.HIGH;
        }
        return ThreatLevel.LOW;
    }

    public int countAtOrAbove(RiskTier tier) {
        int count = 0;
        for (PlayerRiskProfile profile : profiles.values()) {
            if (getRiskTier(profile.getSuspicion()).ordinal() >= tier.ordinal()) {
                count++;
            }
        }
        return count;
    }

    public int countFlaggedIps() {
        int count = 0;
        for (IpProfile ipProfile : ipProfiles.values()) {
            if (ipProfile.flagged) {
                count++;
            }
        }
        return count;
    }

    public int getRecordedAlertCount() {
        return recordedAlertCount;
    }

    public void recordBlockInteract(Player player, Block block) {
        if (player == null || !shouldTrackBehavior(player)) {
            return;
        }
        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        profile.markMining(now);
        profile.setLastBehaviorState(BehaviorState.MINING);
    }

    public BehaviorState getBehaviorState(String playerName) {
        PlayerRiskProfile profile = getOrCreateProfile(playerName);
        Player online = Bukkit.getPlayerExact(playerName);
        return getBehaviorState(profile, online, System.currentTimeMillis());
    }

    public BehaviorState getBehaviorState(PlayerRiskProfile profile, Player player) {
        return getBehaviorState(profile, player, System.currentTimeMillis());
    }

    public String describeBehaviorState(String playerName, boolean english) {
        return describeBehaviorState(getBehaviorState(playerName), english);
    }

    public String describeBehaviorState(BehaviorState state, boolean english) {
        BehaviorState safeState = state == null ? BehaviorState.IDLE : state;
        if (english) {
            return safeState.name();
        }
        return switch (safeState) {
            case COMBAT -> "chiến đấu";
            case MINING -> "đào block";
            case BRIDGING -> "bắc cầu";
            case PARKOUR -> "parkour";
            case KNOCKBACK -> "knockback";
            case LAGGED -> "trạng thái lag";
            default -> "rảnh";
        };
    }

    public String getLatestEvidenceSummary(String playerName, boolean english) {
        PlayerRiskProfile profile = getOrCreateProfile(playerName);
        List<PlayerRiskProfile.EvidenceEntry> evidence = profile.getRecentEvidence(1);
        if (evidence.isEmpty()) {
            return english ? "none" : "không có";
        }
        PlayerRiskProfile.EvidenceEntry entry = evidence.get(0);
        return entry.getSource() + " | " + entry.getDetail();
    }

    public RiskTier getRiskTier(int suspicion) {
        int watch = plugin.getConfig().getInt("scan.suspicion_watch", 7);
        int alert = plugin.getConfig().getInt("scan.suspicion_alert", 13);
        int danger = plugin.getConfig().getInt("scan.suspicion_danger", 22);
        int severe = plugin.getConfig().getInt("scan.suspicion_severe", 32);

        if (suspicion >= severe) {
            return RiskTier.SEVERE;
        }
        if (suspicion >= danger) {
            return RiskTier.DANGER;
        }
        if (suspicion >= alert) {
            return RiskTier.ALERT;
        }
        if (suspicion >= watch) {
            return RiskTier.WATCH;
        }
        return RiskTier.CLEAR;
    }

    public String clampBanDuration(String configuredDuration) {
        String raw = configuredDuration == null ? "3d" : configuredDuration.trim().toLowerCase(Locale.ROOT);
        if (!raw.endsWith("d")) {
            return "3d";
        }
        try {
            int days = Integer.parseInt(raw.substring(0, raw.length() - 1));
            return Math.min(days, 45) + "d";
        } catch (NumberFormatException ex) {
            return "3d";
        }
    }

    private void addBehaviorSuspicion(PlayerRiskProfile profile, String source, int basePoints, String detail) {
        int adjusted = adjustPointsByLearning(profile, basePoints);
        profile.addSuspicion(adjusted, source + ": " + detail);
        profile.addEvidence(source, detail, adjusted, false, plugin.getActiveConfigProfile() == AIAdmin.ConfigProfile.ENGLISH);
        profile.boostHackConfidence(Math.max(1, adjusted));
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordSuspicion(profile.getName());
        }
    }

    private int adjustPointsByLearning(PlayerRiskProfile profile, int basePoints) {
        int safeBase = Math.max(1, basePoints);
        double modifier = 1.0D + ((double) (profile.getHackConfidence() - profile.getProConfidence()) / 220.0D);
        modifier = Math.max(0.55D, Math.min(1.15D, modifier));
        int adjusted = Math.max(1, (int) Math.round(safeBase * modifier));
        if (plugin.getLearningManager() != null) {
            adjusted = plugin.getLearningManager().applyAdaptivePoints(profile.getName(), adjusted);
        }
        return adjusted;
    }

    public boolean hasRecentCombatContext(Player player) {
        if (player == null) {
            return false;
        }
        PlayerRiskProfile profile = getOrCreateProfile(player.getName());
        long now = System.currentTimeMillis();
        long recentCombatWindow = Math.max(350L, getOptionInt("anticheat.behavior.recent_combat_click_window_ms", 700));
        return profile.getLastCombatMillis() > 0L && now - profile.getLastCombatMillis() <= recentCombatWindow;
    }

    public boolean isLookingAtPlayer(Player player, double range) {
        return isLookingAtTrackableTarget(player, range);
    }

    public boolean isLookingAtTrackableTarget(Player player, double range) {
        if (player == null || player.getWorld() == null) {
            return false;
        }
        try {
            Location eyeLocation = player.getEyeLocation();
            double safeRange = Math.max(1.0D, range);
            RayTraceResult blockTrace = player.getWorld().rayTraceBlocks(
                    eyeLocation,
                    eyeLocation.getDirection(),
                    safeRange,
                    FluidCollisionMode.NEVER,
                    false
            );
            double entityRange = safeRange;
            if (blockTrace != null && blockTrace.getHitPosition() != null) {
                entityRange = Math.min(entityRange, blockTrace.getHitPosition().distance(eyeLocation.toVector()));
            }
            if (entityRange <= 0.05D) {
                return false;
            }
            RayTraceResult trace = player.getWorld().rayTraceEntities(
                    eyeLocation,
                    eyeLocation.getDirection(),
                    entityRange,
                    entity -> isTrackableCombatTarget(entity, player)
            );
            return trace != null && isTrackableCombatTarget(trace.getHitEntity(), player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isTrackableCombatTarget(Entity entity, Player observer) {
        if (entity == null || entity == observer) {
            return false;
        }
        return entity instanceof Player || isBotEntity(entity);
    }

    public boolean isBotEntity(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains("aiadmin_bot");
    }

    private boolean shouldTrackBehavior(Player player) {
        if (player == null) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private void notifyStaff(String message) {
        String formatted = color("&c[AIAdmin] &f" + message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("aiadmin.admin")) {
                player.sendMessage(formatted);
            }
        }
        plugin.getLogger().info(message);
    }

    private void trimOldJoins(Deque<Long> joinTimes) {
        long now = Instant.now().toEpochMilli();
        long windowMillis = plugin.getConfig().getLong("scan.join_burst_window_seconds", 120L) * 1000L;
        while (!joinTimes.isEmpty() && now - joinTimes.peekFirst() > windowMillis) {
            joinTimes.removeFirst();
        }
    }

    private double angleDistance(float a, float b) {
        double delta = Math.abs(a - b) % 360.0D;
        return delta > 180.0D ? 360.0D - delta : delta;
    }

    private boolean hasMovementGrace(Player player, long elapsedMillis) {
        if (player == null) {
            return false;
        }
        if (elapsedMillis > 175L) {
            return true;
        }
        if (player.isInsideVehicle() || player.isGliding() || player.isFlying()) {
            return true;
        }
        if (player.getNoDamageTicks() >= Math.max(8, player.getMaximumNoDamageTicks() - 6)) {
            return true;
        }
        if (player.getVelocity() == null) {
            return false;
        }
        double horizontalSquared = (player.getVelocity().getX() * player.getVelocity().getX())
                + (player.getVelocity().getZ() * player.getVelocity().getZ());
        double vertical = Math.abs(player.getVelocity().getY());
        return vertical > 0.45D || (player.getNoDamageTicks() > 0 && horizontalSquared > 0.25D);
    }

    private BehaviorState getBehaviorState(PlayerRiskProfile profile, Player player, long now) {
        if (profile == null) {
            return BehaviorState.IDLE;
        }
        long knockbackWindow = Math.max(600L, getOptionInt("anticheat.behavior.knockback_context_ms", 1300));
        if (profile.getLastKnockbackMillis() > 0L && now - profile.getLastKnockbackMillis() <= knockbackWindow) {
            return BehaviorState.KNOCKBACK;
        }

        long lagWindow = Math.max(500L, getOptionInt("anticheat.behavior.lag_context_ms", 1500));
        if (profile.getLastLagMillis() > 0L && now - profile.getLastLagMillis() <= lagWindow) {
            return BehaviorState.LAGGED;
        }

        long combatWindow = Math.max(350L, getOptionInt("anticheat.behavior.combat_context_ms", 1600));
        if (profile.getLastCombatMillis() > 0L && now - profile.getLastCombatMillis() <= combatWindow) {
            return BehaviorState.COMBAT;
        }

        long miningWindow = Math.max(700L, getOptionInt("anticheat.behavior.mining_context_ms", 2200));
        if (profile.getLastMiningMillis() > 0L && now - profile.getLastMiningMillis() <= miningWindow) {
            return BehaviorState.MINING;
        }

        long bridgeWindow = Math.max(600L, getOptionInt("anticheat.behavior.bridging_context_ms", 1800));
        if (profile.getLastBlockPlaceMillis() > 0L && now - profile.getLastBlockPlaceMillis() <= bridgeWindow) {
            return BehaviorState.BRIDGING;
        }

        long parkourWindow = Math.max(500L, getOptionInt("anticheat.behavior.parkour_context_ms", 1600));
        if (profile.getLastParkourMillis() > 0L && now - profile.getLastParkourMillis() <= parkourWindow) {
            return BehaviorState.PARKOUR;
        }

        if (player != null && player.getNoDamageTicks() >= Math.max(8, player.getMaximumNoDamageTicks() - 6)) {
            return BehaviorState.KNOCKBACK;
        }
        return BehaviorState.IDLE;
    }

    private boolean consumeBehaviorCooldown(String playerName, String source, long cooldownMillis) {
        String key = playerName.toLowerCase(Locale.ROOT) + ":" + source.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long last = behaviorSignalCooldowns.getOrDefault(key, 0L);
        if (now - last < cooldownMillis) {
            return false;
        }
        behaviorSignalCooldowns.put(key, now);
        return true;
    }

    private boolean isXrayOre(Material material) {
        return material != null && XRAY_ORES.contains(material);
    }

    private int countExposedFaces(Block block) {
        if (block == null) {
            return 6;
        }
        int airFaces = 0;
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
        }) {
            Material type = block.getRelative(face).getType();
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                airFaces++;
            }
        }
        return airFaces;
    }

    private int getOptionInt(String path, int def) {
        return plugin.getOptionConfig() == null ? def : plugin.getOptionConfig().getInt(path, def);
    }

    private double getOptionDouble(String path, double def) {
        return plugin.getOptionConfig() == null ? def : plugin.getOptionConfig().getDouble(path, def);
    }

    private String round(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String color(String input) {
        return input.replace("&", "\u00A7");
    }
}
