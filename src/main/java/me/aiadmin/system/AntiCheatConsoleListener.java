package me.aiadmin.system;

import me.aiadmin.AIAdmin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AntiCheatConsoleListener extends Handler {

    private static final Pattern PLAYER_KEY_PATTERN = Pattern.compile("(?i)(?:player|for|on|user|target)\\s*[:= ]+([A-Za-z0-9_]{3,16})");
    private static final Pattern PLAYER_BEFORE_PATTERN = Pattern.compile("(?i)\\b([A-Za-z0-9_]{3,16})\\b.{0,48}(?:failed|flagged|detected|violation|vl|punish)");
    private static final Pattern PLAYER_AFTER_PATTERN = Pattern.compile("(?i)(?:failed|flagged|detected|violation|vl|punish).{0,48}\\b([A-Za-z0-9_]{3,16})\\b");
    private static final Pattern VL_PATTERN = Pattern.compile("(?i)\\b(?:vl|verbose|violations?)\\s*[:= ]\\s*([0-9]+(?:\\.[0-9]+)?)");

    private static final Map<String, String> CHECK_ALIASES = new LinkedHashMap<>();

    static {
        CHECK_ALIASES.put("killaura", "KillAura");
        CHECK_ALIASES.put("aimassist", "AimAssist");
        CHECK_ALIASES.put("aim assist", "AimAssist");
        CHECK_ALIASES.put("autoclicker", "AutoClicker");
        CHECK_ALIASES.put("auto clicker", "AutoClicker");
        CHECK_ALIASES.put("speed", "Speed");
        CHECK_ALIASES.put("reach", "Reach");
        CHECK_ALIASES.put("fly", "Fly");
        CHECK_ALIASES.put("scaffold", "Scaffold");
        CHECK_ALIASES.put("timer", "Timer");
        CHECK_ALIASES.put("velocity", "Velocity");
        CHECK_ALIASES.put("antikb", "Velocity");
        CHECK_ALIASES.put("anti kb", "Velocity");
        CHECK_ALIASES.put("xray", "Xray");
        CHECK_ALIASES.put("badpackets", "BadPackets");
        CHECK_ALIASES.put("bad packets", "BadPackets");
    }

    private final AIAdmin plugin;
    private final SuspicionManager suspicionManager;

    public AntiCheatConsoleListener(AIAdmin plugin, SuspicionManager suspicionManager) {
        this.plugin = plugin;
        this.suspicionManager = suspicionManager;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return;
        }

        String message = normalize(record.getMessage());
        if (message.isBlank()) {
            return;
        }

        String source = detectSource(record, message);
        String checkType = detectCheckType(message);
        if (checkType == null) {
            return;
        }

        String playerName = extractPlayerName(message, checkType, source);
        if (playerName == null || playerName.isBlank()) {
            return;
        }

        double vl = parseViolationLevel(message);
        String severity = resolveSeverity(message, vl);
        int points = resolvePoints(checkType, severity, source, vl);
        suspicionManager.recordAlert(playerName, source, checkType, points, severity + " | " + message);

        if (plugin.getConfig().getBoolean("console.debug_matches", false)) {
            plugin.getLogger().info("Parsed " + source + " anti-cheat alert for " + playerName + " | check=" + checkType + " | severity=" + severity + " | points=" + points);
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }

    private String detectSource(LogRecord record, String message) {
        String loggerName = record.getLoggerName() == null ? "" : record.getLoggerName().toLowerCase(Locale.ROOT);
        String lowered = message.toLowerCase(Locale.ROOT);
        if (loggerName.contains("grim") || lowered.contains("grimac") || lowered.contains("[grim")) {
            return "Grim";
        }
        if (loggerName.contains("matrix") || lowered.contains("[matrix")) {
            return "Matrix";
        }
        if (loggerName.contains("vulcan") || lowered.contains("[vulcan")) {
            return "Vulcan";
        }
        if (loggerName.contains("karhu") || lowered.contains("[karhu")) {
            return "Karhu";
        }
        if (loggerName.contains("spartan") || lowered.contains("[spartan")) {
            return "Spartan";
        }
        return "Console";
    }

    private String detectCheckType(String message) {
        String lowered = message.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : CHECK_ALIASES.entrySet()) {
            if (lowered.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String extractPlayerName(String message, String checkType, String source) {
        Matcher keywordMatcher = PLAYER_KEY_PATTERN.matcher(message);
        if (keywordMatcher.find()) {
            String candidate = keywordMatcher.group(1);
            if (isLikelyPlayer(candidate, checkType, source)) {
                return candidate;
            }
        }

        Matcher before = PLAYER_BEFORE_PATTERN.matcher(message);
        while (before.find()) {
            String candidate = before.group(1);
            if (isLikelyPlayer(candidate, checkType, source)) {
                return candidate;
            }
        }

        Matcher after = PLAYER_AFTER_PATTERN.matcher(message);
        while (after.find()) {
            String candidate = after.group(1);
            if (isLikelyPlayer(candidate, checkType, source)) {
                return candidate;
            }
        }

        String[] tokens = message.split("\\s+");
        for (String token : tokens) {
            String cleaned = token.replaceAll("[^A-Za-z0-9_]", "");
            if (isLikelyPlayer(cleaned, checkType, source)) {
                return cleaned;
            }
        }
        return null;
    }

    private boolean isLikelyPlayer(String candidate, String checkType, String source) {
        if (candidate == null || !candidate.matches("[A-Za-z0-9_]{3,16}")) {
            return false;
        }
        String lowered = candidate.toLowerCase(Locale.ROOT);
        if (lowered.equals(checkType.toLowerCase(Locale.ROOT)) || lowered.equals(source.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !lowered.equals("player")
                && !lowered.equals("failed")
                && !lowered.equals("flagged")
                && !lowered.equals("detected")
                && !lowered.equals("violation")
                && !lowered.equals("verbose")
                && !lowered.equals("console");
    }

    private double parseViolationLevel(String message) {
        Matcher matcher = VL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return 0D;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private String resolveSeverity(String message, double vl) {
        String lowered = message.toLowerCase(Locale.ROOT);
        if (lowered.contains("severe") || vl >= 20D) {
            return "SEVERE";
        }
        if (lowered.contains("high") || vl >= 10D) {
            return "HIGH";
        }
        if (lowered.contains("medium") || vl >= 5D) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private int resolvePoints(String checkType, String severity, String source, double vl) {
        String key = "suspicion.points." + checkType.toLowerCase(Locale.ROOT);
        int base = plugin.getConfig().getInt(key, plugin.getConfig().getInt("suspicion.points.default_alert", 5));
        int severityBonus = switch (severity.toUpperCase(Locale.ROOT)) {
            case "SEVERE" -> 3;
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            default -> 0;
        };
        int sourceBonus = "Grim".equalsIgnoreCase(source) || "Karhu".equalsIgnoreCase(source) ? 1 : 0;
        int vlBonus = vl >= 15D ? 2 : (vl >= 6D ? 1 : 0);
        return Math.max(1, base + severityBonus + sourceBonus + vlBonus);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
