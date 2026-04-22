package me.aiadmin.command;

import me.aiadmin.AIAdmin;
import me.aiadmin.ai.AIChat;
import me.aiadmin.system.BotManager;
import me.aiadmin.system.PluginRuntimeManager;
import me.aiadmin.system.ServerScanner;
import me.aiadmin.system.SuspicionManager;
import me.aiadmin.system.SuspicionManager.PlayerRiskProfile;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AdminCommand implements CommandExecutor, TabCompleter {

    private final AIAdmin plugin;
    private final ServerScanner scanner;
    private final SuspicionManager suspicionManager;
    private final AIChat aiChat;

    public AdminCommand(AIAdmin plugin, ServerScanner scanner, SuspicionManager suspicionManager, AIChat aiChat) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.suspicionManager = suspicionManager;
        this.aiChat = aiChat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean useAccess = hasUseAccess(sender);
        boolean staffAccess = hasStaffAccess(sender);
        boolean adminAccess = hasAdminAccess(sender);

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, useAccess, staffAccess, adminAccess);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "lang":
                return handleLanguageCommand(sender, args);
            case "scan":
                if (!useAccess) {
                    sendRestriction(sender, useAccess, staffAccess, adminAccess);
                    return true;
                }
                return handleScanCommand(sender, args);
            case "dashboard":
                if (!useAccess) {
                    sendRestriction(sender, useAccess, staffAccess, adminAccess);
                    return true;
                }
                plugin.getSuspicionDashboard().openDashboard(sender, 0);
                return true;
            case "set":
            case "plugin":
                if (!adminAccess) {
                    sendRestriction(sender, useAccess, staffAccess, adminAccess);
                    return true;
                }
                return sub.equals("set") ? handleSetCommand(sender, args) : handlePluginCommand(sender, args);
            case "use":
                if (!staffAccess) {
                    sendRestriction(sender, useAccess, staffAccess, adminAccess);
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("config")) {
                    return handleUseConfigCommand(sender, args);
                }
                sendHelp(sender, useAccess, staffAccess, adminAccess);
                return true;
            default:
                if (!staffAccess) {
                    sendRestriction(sender, useAccess, staffAccess, adminAccess);
                    return true;
                }
                return handleStaffCommand(sender, sub, args);
        }
    }

    private boolean handleStaffCommand(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "status":
                return handleStatusCommand(sender);
            case "reload":
                plugin.reloadAllRuntimeConfigs();
                sender.sendMessage(color(t(sender,
                        "&aĐã nạp lại toàn bộ config đang dùng.",
                        "&aReloaded the active runtime config set.")));
                return true;
            case "optimize":
                if (plugin.getLagOptimizer() == null) {
                    sender.sendMessage(color(t(sender,
                            "&cLag optimizer hiện chưa khả dụng.",
                            "&cLag optimizer is not available right now.")));
                    return true;
                }
                plugin.getLagOptimizer().runManualOptimize(sender);
                return true;
            case "check":
                return handleCheckCommand(sender, args);
            case "checkgui":
                return handleCheckGuiCommand(sender, args);
            case "suspicion":
                return handleSuspicionCommand(sender, args);
            case "addsus":
                return handleAddSuspicionCommand(sender, args);
            case "flag":
                return handleFlagCommand(sender, args);
            case "observe":
            case "watch":
                return handleObserveCommand(sender, args);
            case "kick":
                return handleKickCommand(sender, args);
            case "ban":
                return handleBanCommand(sender, args);
            case "termban":
                return handleTempBanCommand(sender, args);
            case "thongbao":
            case "announce":
                return handleAnnouncementCommand(sender, args);
            case "createbot":
                return handleCreateBotCommand(sender, args);
            case "choose":
                return handleChooseBotCommand(sender, args);
            case "bot":
                return handleBotCommand(sender, args);
            case "admode":
            case "adminmode":
                return handleAdminModeCommand(sender, args);
            default:
                sendHelp(sender, hasUseAccess(sender), hasStaffAccess(sender), hasAdminAccess(sender));
                return true;
        }
    }

    private boolean handleScanCommand(CommandSender sender, String[] args) {
        if (args.length >= 2 && (args[1].equalsIgnoreCase("dashboard") || args[1].equalsIgnoreCase("gui"))) {
            if (plugin.getSuspicionDashboard() == null) {
                sender.sendMessage(color(t(sender,
                        "&cScan dashboard hiện chưa khả dụng.",
                        "&cThe scan dashboard is not available right now.")));
                return true;
            }
            plugin.getSuspicionDashboard().openScanDashboard(sender);
            return true;
        }

        ServerScanner.ScanSnapshot snapshot = scanner.scanServer(true);
        if (plugin.getSuspicionDashboard() != null && sender instanceof Player) {
            plugin.getSuspicionDashboard().openScanDashboard(sender, snapshot);
            return true;
        }

        sender.sendMessage(color(t(sender,
                "&6[AIAdmin] &fQuét hoàn tất: &e" + snapshot.getScannedPlayers()
                        + " &7/ &f" + snapshot.getOnlinePlayers()
                        + " &8(thấp=" + snapshot.getLowCount()
                        + ", trung bình=" + snapshot.getMediumCount()
                        + ", cao=" + snapshot.getHighCount() + ")",
                "&6[AIAdmin] &fScan finished: &e" + snapshot.getScannedPlayers()
                        + " &7/ &f" + snapshot.getOnlinePlayers()
                        + " &8(low=" + snapshot.getLowCount()
                        + ", medium=" + snapshot.getMediumCount()
                        + ", high=" + snapshot.getHighCount() + ")")));
        return true;
    }

    private boolean handleStatusCommand(CommandSender sender) {
        sender.sendMessage(color("&6[AIAdmin] &fAI API: " + (aiChat.isOpenAIEnabled()
                ? t(sender, "SẴN SÀNG", "READY")
                : t(sender, "ĐANG TẮT", "DISABLED"))));
        sender.sendMessage(color(t(sender,
                "&7Dùng " + aiChat.getApiKeyEnvName() + " hoặc config openai.api_key để bật kết nối.",
                "&7Use " + aiChat.getApiKeyEnvName() + " or config openai.api_key to enable the connection.")));
        sender.sendMessage(color("&7AI_ban: &f" + integrationState(sender, "ai_ban", "LiteBans")));
        sender.sendMessage(color("&7TAB: &f" + integrationState(sender, "tab", "TAB")));
        sender.sendMessage(color("&7PlaceholderAPI: &f" + integrationState(sender, "placeholder", "PlaceholderAPI")));
        sender.sendMessage(color("&7Velocity: &f" + proxyState(sender)));
        sender.sendMessage(color("&7Database: &f" + (plugin.getDatabaseManager() != null
                ? plugin.getDatabaseManager().getStatusSummary()
                : "disabled")));
        sender.sendMessage(color("&7DB Analytics: &f" + (plugin.getDatabaseManager() != null
                ? plugin.getDatabaseManager().getAnalyticsSummary()
                : "disabled")));
        sender.sendMessage(color("&7Learning: &f" + (plugin.getLearningManager() != null
                ? plugin.getLearningManager().getStatusSummary()
                : "disabled")));
        sender.sendMessage(color("&7LagOptimizer: &f" + (plugin.getLagOptimizer() != null
                ? plugin.getLagOptimizer().getStatusSummary()
                : "disabled")));
        sender.sendMessage(color(t(sender,
                "&7Bộ ngôn ngữ đang dùng: &f" + plugin.getActiveLocaleFolderName(),
                "&7Active language profile: &f" + plugin.getActiveLocaleFolderName())));
        return true;
    }

    private boolean handleCheckCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai check <player> [gui|observe]",
                    "&cUsage: /ai check <player> [gui|observe]")));
            return true;
        }

        String playerName = args[1];
        sender.sendMessage(color("&6[AIAdmin] &f" + aiChat.buildPlayerAnalysis(playerName, plugin.getSenderLanguage(sender))));
        aiChat.sendActionPlan(sender, playerName);

        if (args.length >= 3) {
            String mode = args[2].toLowerCase(Locale.ROOT);
            if (mode.equals("gui")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(color(t(sender,
                            "&cChỉ người chơi mới mở được GUI check.",
                            "&cOnly players can open the check GUI.")));
                    return true;
                }
                plugin.getSuspicionDashboard().openPlayerCheck(sender, playerName);
                return true;
            }
            if (mode.equals("observe") || mode.equals("watch")) {
                if (plugin.getStatsManager() != null) {
                    plugin.getStatsManager().recordCheck(playerName);
                }
                scanner.observePlayer(sender, playerName, "manual-check", true);
                return true;
            }
        }

        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordCheck(playerName);
        }
        return true;
    }

    private boolean handleCheckGuiCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai checkgui <player>",
                    "&cUsage: /ai checkgui <player>")));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(color(t(sender,
                    "&cLệnh này chỉ dùng trong game.",
                    "&cThis command can only be used in-game.")));
            return true;
        }
        plugin.getSuspicionDashboard().openPlayerCheck(sender, args[1]);
        return true;
    }

    private boolean handleSuspicionCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai suspicion <player>",
                    "&cUsage: /ai suspicion <player>")));
            return true;
        }

        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(args[1]);
        sender.sendMessage(color(t(sender,
                "&eĐiểm nghi ngờ của " + profile.getName() + ": &f" + profile.getSuspicion(),
                "&eSuspicion score of " + profile.getName() + ": &f" + profile.getSuspicion())));
        sender.sendMessage(color(t(sender,
                "&7Mức: &f" + suspicionManager.getRiskTier(profile.getSuspicion()).name() + " &7Cảnh báo: &f" + profile.getTotalAlerts(),
                "&7Tier: &f" + suspicionManager.getRiskTier(profile.getSuspicion()).name() + " &7Alerts: &f" + profile.getTotalAlerts())));
        sender.sendMessage(color(t(sender,
                "&7Vị trí nghi ngờ gần nhất: &f" + profile.getLastSuspiciousLocationSummary(false),
                "&7Last suspicious location: &f" + profile.getLastSuspiciousLocationSummary(true))));
        return true;
    }

    private boolean handleAddSuspicionCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai addsus <player> <amount>",
                    "&cUsage: /ai addsus <player> <amount>")));
            return true;
        }

        Integer amount = parseInteger(args[2]);
        if (amount == null) {
            sender.sendMessage(color(t(sender,
                    "&cAmount phải là số.",
                    "&cAmount must be a number.")));
            return true;
        }

        suspicionManager.addSuspicion(args[1], amount, "manual", "Staff update");
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordSuspicion(args[1]);
        }
        sender.sendMessage(color(t(sender,
                "&aĐã cộng điểm nghi ngờ cho " + args[1] + ". Mức mới: " + suspicionManager.getSuspicion(args[1]),
                "&aAdded suspicion to " + args[1] + ". New score: " + suspicionManager.getSuspicion(args[1]))));
        return true;
    }

    private boolean handleFlagCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai flag <player> <type> [points] [details]",
                    "&cUsage: /ai flag <player> <type> [points] [details]")));
            return true;
        }

        int points = 5;
        int detailsIndex = 3;
        if (args.length >= 4) {
            try {
                points = Integer.parseInt(args[3]);
                detailsIndex = 4;
            } catch (NumberFormatException ignored) {
                detailsIndex = 3;
            }
        }

        String details = args.length > detailsIndex
                ? String.join(" ", Arrays.copyOfRange(args, detailsIndex, args.length))
                : "manual anti-cheat alert";
        suspicionManager.recordAlert(args[1], "manual", args[2], points, details);
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordReport(args[1]);
        }
        sender.sendMessage(color(t(sender,
                "&aĐã ghi nhận cảnh báo cho " + args[1] + ".",
                "&aAlert recorded for " + args[1] + ".")));
        scanner.observePlayer(sender, args[1], "manual-flag-" + args[2], true);
        return true;
    }

    private boolean handleObserveCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai observe <player> <on/off/mute>",
                    "&cUsage: /ai observe <player> <on/off/mute>")));
            return true;
        }

        String mode = args[2].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "on":
                scanner.observePlayer(sender, args[1], "manual-observe", true);
                return true;
            case "off":
                scanner.stopObserving(sender, args[1], true);
                return true;
            case "mute":
                scanner.muteFromAutoScan(sender, args[1]);
                return true;
            default:
                sender.sendMessage(color(t(sender,
                        "&cTham số cuối phải là on, off hoặc mute.",
                        "&cThe last argument must be on, off, or mute.")));
                return true;
        }
    }

    private boolean handleSetCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendSetHelp(sender);
            return true;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        switch (target) {
            case "max-players-per-scan":
                return updateConfigInteger(sender, "scan.max_players_per_scan", args[2], 1, 500, "max_players_per_scan");
            case "max-acc-per-ip":
                return updateConfigInteger(sender, "scan.max_accounts_per_ip", args[2], 1, 50, "max_accounts_per_ip");
            case "time-scan":
                return updateConfigInteger(sender, "scan.interval_minutes", args[2], 1, 720, "interval_minutes");
            case "suspicion":
                return handleSetSuspicionCommand(sender, args);
            case "openai":
                return handleSetOpenAICommand(sender, args);
            case "ai_ban":
            case "litebans":
                return handleSetAiBanCommand(sender, args);
            default:
                sendSetHelp(sender);
                return true;
        }
    }

    private boolean handleSetOpenAICommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai set openai <enable|model|api_key> <value>",
                    "&cUsage: /ai set openai <enable|model|api_key> <value>")));
            return true;
        }

        String option = args[2].toLowerCase(Locale.ROOT);
        switch (option) {
            case "enable": {
                Boolean enabled = parseBooleanToken(args[3]);
                if (enabled == null) {
                    sender.sendMessage(color(t(sender,
                            "&cEnable chỉ nhận true hoặc false.",
                            "&cEnable only accepts true or false.")));
                    return true;
                }
                plugin.getConfig().set("openai.enabled", enabled);
                if (!saveAndReloadActiveConfig()) {
                    sender.sendMessage(color(t(sender,
                            "&cKhông thể lưu config OpenAI.",
                            "&cCould not save the OpenAI config.")));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&aĐã cập nhật openai.enabled thành &f" + enabled + "&a.",
                        "&aUpdated openai.enabled to &f" + enabled + "&a.")));
                return true;
            }
            case "model": {
                String model = parseValueToken(args, 3);
                if (model.isBlank()) {
                    sender.sendMessage(color(t(sender,
                            "&cModel không được để trống.",
                            "&cModel cannot be blank.")));
                    return true;
                }
                plugin.getConfig().set("openai.model", model);
                if (!saveAndReloadActiveConfig()) {
                    sender.sendMessage(color(t(sender,
                            "&cKhông thể lưu model OpenAI.",
                            "&cCould not save the OpenAI model.")));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&aĐã cập nhật openai.model thành &f" + model + "&a.",
                        "&aUpdated openai.model to &f" + model + "&a.")));
                return true;
            }
            case "api_key": {
                String apiKey = parseValueToken(args, 3);
                if (apiKey.isBlank()) {
                    sender.sendMessage(color(t(sender,
                            "&cAPI key không được để trống.",
                            "&cAPI key cannot be blank.")));
                    return true;
                }
                plugin.getConfig().set("openai.api_key", apiKey);
                if (!saveAndReloadActiveConfig()) {
                    sender.sendMessage(color(t(sender,
                            "&cKhông thể lưu API key.",
                            "&cCould not save the API key.")));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&aĐã cập nhật OpenAI API key trực tiếp trong config đang dùng.",
                        "&aUpdated the OpenAI API key in the active config.")));
                return true;
            }
            default:
                sender.sendMessage(color(t(sender,
                        "&cOpenAI option không hợp lệ. Dùng enable, model hoặc api_key.",
                        "&cInvalid OpenAI option. Use enable, model, or api_key.")));
                return true;
        }
    }
    private boolean handleSetAiBanCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai set ai_ban <enable|ban_duration> <value>",
                    "&cUsage: /ai set ai_ban <enable|ban_duration> <value>")));
            return true;
        }

        String option = args[2].toLowerCase(Locale.ROOT);
        switch (option) {
            case "enable": {
                Boolean enabled = parseBooleanToken(args[3]);
                if (enabled == null) {
                    sender.sendMessage(color(t(sender,
                            "&cEnable chỉ nhận true hoặc false.",
                            "&cEnable only accepts true or false.")));
                    return true;
                }
                plugin.getConfig().set("ai_ban.enabled", enabled);
                if (!saveAndReloadActiveConfig()) {
                    sender.sendMessage(color(t(sender,
                            "&cKhông thể lưu config AI_ban.",
                            "&cCould not save the AI_ban config.")));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&aĐã cập nhật ai_ban.enabled thành &f" + enabled + "&a.",
                        "&aUpdated ai_ban.enabled to &f" + enabled + "&a.")));
                return true;
            }
            case "ban_duration": {
                String raw = parseValueToken(args, 3);
                if (!scanner.isDurationToken(raw)) {
                    sender.sendMessage(color(t(sender,
                            "&cBan duration phải có dạng như 1s, 30m, 12h, 3d...",
                            "&cBan duration must look like 1s, 30m, 12h, 3d...")));
                    return true;
                }
                String duration = scanner.normalizeDuration(raw);
                plugin.getConfig().set("ai_ban.ban_duration", duration);
                if (plugin.getAiBanConfig() != null) {
                    plugin.getAiBanConfig().set("ai_ban.default_duration", duration);
                }
                if (!saveAndReloadActiveAndAiBanConfig()) {
                    sender.sendMessage(color(t(sender,
                            "&cKhông thể lưu thời gian ban.",
                            "&cCould not save the ban duration.")));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&aĐã cập nhật ai_ban.ban_duration thành &f" + duration + "&a.",
                        "&aUpdated ai_ban.ban_duration to &f" + duration + "&a.")));
                return true;
            }
            default:
                sender.sendMessage(color(t(sender,
                        "&cAI_ban option không hợp lệ. Dùng enable hoặc ban_duration.",
                        "&cInvalid AI_ban option. Use enable or ban_duration.")));
                return true;
        }
    }

    private boolean handleSetSuspicionCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai set suspicion <watch|alert|danger|severe> <value>",
                    "&cUsage: /ai set suspicion <watch|alert|danger|severe> <value>")));
            return true;
        }

        String tier = args[2].toLowerCase(Locale.ROOT);
        String path;
        String label;
        switch (tier) {
            case "watch":
                path = "scan.suspicion_watch";
                label = "suspicion_watch";
                break;
            case "alert":
                path = "scan.suspicion_alert";
                label = "suspicion_alert";
                break;
            case "danger":
                path = "scan.suspicion_danger";
                label = "suspicion_danger";
                break;
            case "severe":
                path = "scan.suspicion_severe";
                label = "suspicion_severe";
                break;
            default:
                sender.sendMessage(color(t(sender,
                        "&cMức suspicion không hợp lệ. Dùng watch, alert, danger hoặc severe.",
                        "&cInvalid suspicion tier. Use watch, alert, danger, or severe.")));
                return true;
        }

        Integer parsed = parseInteger(args[3]);
        if (parsed == null) {
            sender.sendMessage(color(t(sender,
                    "&cGiá trị phải là số nguyên.",
                    "&cValue must be an integer.")));
            return true;
        }
        int value = parsed;
        if (value < 0 || value > 500) {
            sender.sendMessage(color(t(sender,
                    "&cGiá trị phải nằm trong khoảng 0 đến 500.",
                    "&cValue must be between 0 and 500.")));
            return true;
        }

        FileConfiguration configuration = plugin.getConfig();
        int watch = configuration.getInt("scan.suspicion_watch", 30);
        int alert = configuration.getInt("scan.suspicion_alert", 50);
        int danger = configuration.getInt("scan.suspicion_danger", 70);
        int severe = configuration.getInt("scan.suspicion_severe", 90);

        switch (tier) {
            case "watch":
                watch = value;
                break;
            case "alert":
                alert = value;
                break;
            case "danger":
                danger = value;
                break;
            case "severe":
                severe = value;
                break;
            default:
                break;
        }

        if (!(watch < alert && alert < danger && danger < severe)) {
            sender.sendMessage(color(t(sender,
                    "&cCác mức suspicion phải theo thứ tự: watch < alert < danger < severe.",
                    "&cSuspicion thresholds must stay ordered: watch < alert < danger < severe.")));
            return true;
        }

        configuration.set(path, value);
        if (!saveAndReloadActiveConfig()) {
            sender.sendMessage(color(t(sender,
                    "&cKhông thể lưu config mới.",
                    "&cCould not save the updated config.")));
            return true;
        }

        sender.sendMessage(color(t(sender,
                "&aĐã cập nhật &f" + label + "&a thành &f" + value + "&a.",
                "&aUpdated &f" + label + "&a to &f" + value + "&a.")));
        return true;
    }

    private boolean updateConfigInteger(CommandSender sender, String path, String rawValue, int min, int max, String label) {
        Integer parsed = parseInteger(rawValue);
        if (parsed == null) {
            sender.sendMessage(color(t(sender,
                    "&cGiá trị phải là số nguyên.",
                    "&cValue must be an integer.")));
            return true;
        }
        int value = parsed;
        if (value < min || value > max) {
            sender.sendMessage(color(t(sender,
                    "&cGiá trị phải nằm trong khoảng &f" + min + "&c đến &f" + max + "&c.",
                    "&cValue must be between &f" + min + "&c and &f" + max + "&c.")));
            return true;
        }
        plugin.getConfig().set(path, value);
        if (!saveAndReloadActiveConfig()) {
            sender.sendMessage(color(t(sender,
                    "&cKhông thể lưu config mới.",
                    "&cCould not save the updated config.")));
            return true;
        }
        sender.sendMessage(color(t(sender,
                "&aĐã cập nhật &f" + label + "&a thành &f" + value + "&a.",
                "&aUpdated &f" + label + "&a to &f" + value + "&a.")));
        return true;
    }

    private boolean saveAndReloadActiveConfig() {
        boolean saved = plugin.saveActiveConfiguration("config.yml", plugin.getConfig());
        if (!saved) {
            return false;
        }
        plugin.reloadAllRuntimeConfigs();
        return true;
    }

    private boolean saveAndReloadActiveAndAiBanConfig() {
        boolean savedMain = plugin.saveActiveConfiguration("config.yml", plugin.getConfig());
        boolean savedAiBan = plugin.getAiBanConfig() == null
                || plugin.saveActiveConfiguration("ai_ban.yml", plugin.getAiBanConfig());
        if (!savedMain || !savedAiBan) {
            return false;
        }
        plugin.reloadAllRuntimeConfigs();
        return true;
    }

    private Integer parseInteger(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Boolean parseBooleanToken(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("on")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("off")) {
            return false;
        }
        return null;
    }

    private String parseValueToken(String[] args, int fromIndex) {
        if (args == null || fromIndex >= args.length) {
            return "";
        }
        String value = String.join(" ", Arrays.copyOfRange(args, fromIndex, args.length)).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private void sendSetHelp(CommandSender sender) {
        sender.sendMessage(color(t(sender,
                "&e/ai set max-players-per-scan <value> &7- Chỉnh số player quét mỗi lần",
                "&e/ai set max-players-per-scan <value> &7- Set max players per scan")));
        sender.sendMessage(color(t(sender,
                "&e/ai set max-acc-per-ip <value> &7- Chỉnh số account tối đa trên một IP",
                "&e/ai set max-acc-per-ip <value> &7- Set max accounts per IP")));
        sender.sendMessage(color(t(sender,
                "&e/ai set time-scan <value> &7- Chỉnh phút giữa các lần scan",
                "&e/ai set time-scan <value> &7- Set minutes between scans")));
        sender.sendMessage(color(t(sender,
                "&e/ai set suspicion <watch|alert|danger|severe> <value> &7- Chỉnh các mức suspicion",
                "&e/ai set suspicion <watch|alert|danger|severe> <value> &7- Set suspicion thresholds")));
        sender.sendMessage(color(t(sender,
                "&e/ai set openai <enable|model|api_key> <value> &7- Chỉnh OpenAI/Groq runtime",
                "&e/ai set openai <enable|model|api_key> <value> &7- Update OpenAI/Groq runtime settings")));
        sender.sendMessage(color(t(sender,
                "&e/ai set ai_ban <enable|ban_duration> <value> &7- Chỉnh AI_ban runtime",
                "&e/ai set ai_ban <enable|ban_duration> <value> &7- Update AI_ban runtime settings")));
    }

    private boolean handleKickCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai kick <player> [reason]",
                    "&cUsage: /ai kick <player> [reason]")));
            return true;
        }

        String reason = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : t(sender,
                "AIAdmin phát hiện hành vi bất thường. Vui lòng chờ staff kiểm tra.",
                "AIAdmin detected unusual behavior. Please wait for staff review.");
        ServerScanner.BanResult result = scanner.kickPlayerByName(args[1], reason);
        if (result == ServerScanner.BanResult.BLOCKED_OP) {
            sender.sendMessage(color(t(sender,
                    "&eKhông thể kick người chơi OP: " + args[1],
                    "&eCannot kick OP player: " + args[1])));
            return true;
        }
        if (result == ServerScanner.BanResult.FAILED) {
            sender.sendMessage(color(t(sender,
                    "&cKick thất bại. Chỉ kick được người chơi đang online.",
                    "&cKick failed. Only online players can be kicked.")));
            return true;
        }
        sender.sendMessage(color(t(sender,
                "&aĐã kick " + args[1] + ".",
                "&aKicked " + args[1] + ".")));
        return true;
    }

    private boolean handleBanCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai ban <player> [reason]",
                    "&cUsage: /ai ban <player> [reason]")));
            return true;
        }

        String reason = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : t(sender, "AIAdmin manual ban", "AIAdmin manual ban");
        ServerScanner.BanResult result = scanner.banPlayerByName(args[1], reason);
        if (result == ServerScanner.BanResult.BLOCKED_OP) {
            sender.sendMessage(color(t(sender,
                    "&eKhông thể ban người chơi OP: " + args[1],
                    "&eCannot ban OP player: " + args[1])));
            return true;
        }
        if (result == ServerScanner.BanResult.FAILED) {
            sender.sendMessage(color(t(sender,
                    "&cBan thất bại cho " + args[1] + ". Kiểm tra config plugin.",
                    "&cBan failed for " + args[1] + ". Check plugin config.")));
            return true;
        }
        sender.sendMessage(color(t(sender,
                "&aĐã xử lý ban cho " + args[1] + ".",
                "&aBan handled for " + args[1] + ".")));
        return true;
    }
    private boolean handleTempBanCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai termban <player> <reason> <time>",
                    "&cUsage: /ai termban <player> <reason> <time>")));
            return true;
        }

        String playerName = args[1];
        String duration = null;
        String reason = t(sender,
                "AIAdmin phát hiện hành vi bất thường",
                "AIAdmin detected suspicious behavior");

        if (scanner.isDurationToken(args[args.length - 1])) {
            duration = scanner.normalizeDuration(args[args.length - 1]);
            if (args.length > 3) {
                reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length - 1));
            }
        } else if (scanner.isDurationToken(args[2])) {
            duration = scanner.normalizeDuration(args[2]);
            if (args.length > 3) {
                reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            }
        }

        if (duration == null) {
            sender.sendMessage(color(t(sender,
                    "&cThiếu thời gian hợp lệ. Ví dụ: 1s, 30m, 12h, 3d.",
                    "&cMissing a valid duration. Example: 1s, 30m, 12h, 3d.")));
            return true;
        }

        ServerScanner.BanResult result = scanner.tempBanPlayerByName(playerName, duration, reason);
        if (result == ServerScanner.BanResult.BLOCKED_OP) {
            sender.sendMessage(color(t(sender,
                    "&eKhông thể termban người chơi OP: " + playerName,
                    "&eCannot temp-ban OP player: " + playerName)));
            return true;
        }
        if (result == ServerScanner.BanResult.FAILED) {
            sender.sendMessage(color(t(sender,
                    "&cTermban thất bại cho " + playerName + ".",
                    "&cTemp-ban failed for " + playerName + ".")));
            return true;
        }
        sender.sendMessage(color(t(sender,
                "&aĐã termban " + playerName + " trong &f" + duration + "&a.",
                "&aTemp-banned " + playerName + " for &f" + duration + "&a.")));
        return true;
    }

    private boolean handleAnnouncementCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai thongbao <nội dung>",
                    "&cUsage: /ai announce <message>")));
            return true;
        }

        String announcement = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (announcement.isBlank()) {
            sender.sendMessage(color(t(sender,
                    "&cNội dung thông báo trống.",
                    "&cAnnouncement text is empty.")));
            return true;
        }

        sender.sendMessage(color(t(sender,
                "&eAI đang soạn thông báo...",
                "&eAI is drafting the announcement...")));
        aiChat.generateServerAnnouncement(announcement)
                .thenAccept(reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                    String finalReply = (reply == null || reply.isBlank()) ? announcement : reply;
                    aiChat.relayAsAdmin(finalReply);
                    sender.sendMessage(color(t(sender,
                            "&aĐã gửi thông báo đến toàn server.",
                            "&aAnnouncement sent to the whole server.")));
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(color(t(sender,
                            "&cKhông thể tạo thông báo AI lúc này.",
                            "&cCould not generate an AI announcement right now."))));
                    return null;
                });
        return true;
    }

    private boolean handleCreateBotCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai createbot <name>",
                    "&cUsage: /ai createbot <name>")));
            return true;
        }
        BotManager botManager = plugin.getBotManager();
        if (botManager == null) {
            sender.sendMessage(color(t(sender,
                    "&cBot manager hiện chưa khả dụng.",
                    "&cBot manager is not available.")));
            return true;
        }
        sender.sendMessage(color(botManager.createBotBody(sender, args[1])));
        return true;
    }

    private boolean handleChooseBotCommand(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("bot")) {
            sender.sendMessage(color(t(sender,
                    "&cCách dùng: /ai choose bot <name>",
                    "&cUsage: /ai choose bot <name>")));
            return true;
        }
        BotManager botManager = plugin.getBotManager();
        if (botManager == null) {
            sender.sendMessage(color(t(sender,
                    "&cBot manager hiện chưa khả dụng.",
                    "&cBot manager is not available.")));
            return true;
        }
        sender.sendMessage(color(botManager.chooseBot(args[2])));
        return true;
    }

    private boolean handleLanguageCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color(t(sender,
                    "&cChỉ người chơi mới đổi được ngôn ngữ cá nhân.",
                    "&cOnly players can change personal language.")));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color(t(sender,
                    "&eCách dùng: /ai lang <english/vietnam>",
                    "&eUsage: /ai lang <english/vietnam>")));
            return true;
        }

        AIAdmin.ConfigProfile profile = AIAdmin.ConfigProfile.fromInput(args[1]);
        if (profile == null) {
            sender.sendMessage(color(t(sender,
                    "&cNgôn ngữ không hợp lệ. Dùng english hoặc vietnam.",
                    "&cInvalid language. Use english or vietnam.")));
            return true;
        }

        plugin.setPlayerLanguage((Player) sender, profile);
        sender.sendMessage(color(profile == AIAdmin.ConfigProfile.ENGLISH
                ? "&aYour personal AI language is now English."
                : "&aAI cá nhân của bạn đã chuyển sang tiếng Việt."));
        return true;
    }

    private boolean handleUseConfigCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color(t(sender,
                    "&eCách dùng: /ai use config <english/vietnam>",
                    "&eUsage: /ai use config <english/vietnam>")));
            return true;
        }

        AIAdmin.ConfigProfile profile = AIAdmin.ConfigProfile.fromInput(args[2]);
        if (profile == null) {
            sender.sendMessage(color(t(sender,
                    "&cBộ config không hợp lệ. Dùng english hoặc vietnam.",
                    "&cInvalid config set. Use english or vietnam.")));
            return true;
        }

        if (!plugin.setActiveConfigProfile(profile)) {
            sender.sendMessage(color(t(sender,
                    "&cKhông thể đổi bộ config.",
                    "&cCould not switch config set.")));
            return true;
        }

        plugin.reloadAllRuntimeConfigs();
        sender.sendMessage(color(profile == AIAdmin.ConfigProfile.ENGLISH
                ? "&aSwitched the server language profile to English."
                : "&aĐã chuyển bộ ngôn ngữ của server sang tiếng Việt."));
        return true;
    }

    private boolean handleAdminModeCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            boolean toggled = !plugin.isAdminModeEnabled();
            plugin.setAdminModeEnabled(toggled);
            sender.sendMessage(color(t(sender,
                    "&eAdmode hiện là &f" + (toggled ? "ON" : "OFF"),
                    "&eAdmode is now &f" + (toggled ? "ON" : "OFF"))));
            return true;
        }

        if (args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(color(t(sender,
                    "&eAdmode hiện là &f" + (plugin.isAdminModeEnabled() ? "ON" : "OFF"),
                    "&eAdmode is currently &f" + (plugin.isAdminModeEnabled() ? "ON" : "OFF"))));
            return true;
        }

        if (args[1].equalsIgnoreCase("on")) {
            plugin.setAdminModeEnabled(true);
            sender.sendMessage(color(t(sender,
                    "&aĐã bật admode.",
                    "&aAdmode has been enabled.")));
            return true;
        }

        if (args[1].equalsIgnoreCase("off")) {
            plugin.setAdminModeEnabled(false);
            sender.sendMessage(color(t(sender,
                    "&aĐã tắt admode.",
                    "&aAdmode has been disabled.")));
            return true;
        }

        sender.sendMessage(color(t(sender,
                "&eCách dùng: /ai admode <on|off|status>",
                "&eUsage: /ai admode <on|off|status>")));
        return true;
    }

    private boolean handleBotCommand(CommandSender sender, String[] args) {
        BotManager botManager = plugin.getBotManager();
        if (botManager == null) {
            sender.sendMessage(color(t(sender,
                    "&cBot manager hiện chưa khả dụng.",
                    "&cBot manager is not available.")));
            return true;
        }

        if (args.length < 2 || args[1].equalsIgnoreCase("help")) {
            sendBotHelp(sender, botManager);
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                for (String line : botManager.listBots()) {
                    sender.sendMessage(color(line));
                }
                return true;
            case "report_chat":
                if (args.length < 3) {
                    sender.sendMessage(color(t(sender,
                            "&cCách dùng: /ai bot report_chat <on|off>",
                            "&cUsage: /ai bot report_chat <on|off>")));
                    return true;
                }
                sender.sendMessage(color(botManager.setReportChat(args[2])));
                return true;
            case "remove":
                sender.sendMessage(color(botManager.removeBotBody()));
                return true;
            case "tp":
                if (args.length >= 4) {
                    sender.sendMessage(color(botManager.teleportBotToPlayer(args[2], args[3])));
                    return true;
                }
                if (args.length >= 3) {
                    sender.sendMessage(color(botManager.teleportSenderToBot(sender, args[2])));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&cCách dùng: /ai bot tp <bot_name> [player]",
                        "&cUsage: /ai bot tp <bot_name> [player]")));
                return true;
            case "setup":
                if (args.length == 2) {
                    for (String line : botManager.describeSetup()) {
                        sender.sendMessage(color(line));
                    }
                    return true;
                }
                if (args.length < 4) {
                    sender.sendMessage(color(t(sender,
                            "&cCách dùng: /ai bot setup <key> <value>",
                            "&cUsage: /ai bot setup <key> <value>")));
                    return true;
                }
                sender.sendMessage(color(botManager.setupBot(args[2], parseValueToken(args, 3))));
                return true;
            case "action":
                if (args.length >= 4 && args[2].equalsIgnoreCase("add")) {
                    String[] params = args.length > 4 ? Arrays.copyOfRange(args, 4, args.length) : new String[0];
                    sender.sendMessage(color(botManager.addBotAction(args[3], params)));
                    return true;
                }
                sender.sendMessage(color(t(sender,
                        "&cCách dùng: /ai bot action add <action> <params...>",
                        "&cUsage: /ai bot action add <action> <params...>")));
                return true;
            default:
                sendBotHelp(sender, botManager);
                return true;
        }
    }

    private void sendBotHelp(CommandSender sender, BotManager botManager) {
        for (String line : botManager.getBotHelpLines()) {
            sender.sendMessage(color(line));
        }
        for (String line : botManager.describeSetup()) {
            sender.sendMessage(color(line));
        }
    }

    private boolean handlePluginCommand(CommandSender sender, String[] args) {
        PluginRuntimeManager runtimeManager = plugin.getPluginRuntimeManager();
        if (runtimeManager == null) {
            sender.sendMessage(color(t(sender,
                    "&cPlugin manager hiện chưa khả dụng.",
                    "&cPlugin manager is not available.")));
            return true;
        }

        if (args.length < 2 || args[1].equalsIgnoreCase("help")) {
            sendPluginHelp(sender);
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                for (String line : runtimeManager.describePlugins(plugin.isEnglish(sender))) {
                    sender.sendMessage(line);
                }
                return true;
            case "download":
                if (args.length < 3) {
                    sender.sendMessage(color(t(sender,
                            "&cCách dùng: /ai plugin download <plugin>",
                            "&cUsage: /ai plugin download <plugin>")));
                    return true;
                }
                runtimeManager.downloadPlugin(sender, parseValueToken(args, 2));
                return true;
            case "remove":
            case "delete":
                if (args.length < 3) {
                    sender.sendMessage(color(t(sender,
                            "&cCách dùng: /ai plugin remove <plugin>",
                            "&cUsage: /ai plugin remove <plugin>")));
                    return true;
                }
                sender.sendMessage(color(runtimeManager.removePlugin(sender, parseValueToken(args, 2))));
                return true;
            default:
                sendPluginHelp(sender);
                return true;
        }
    }

    private void sendPluginHelp(CommandSender sender) {
        PluginRuntimeManager runtimeManager = plugin.getPluginRuntimeManager();
        List<String> keys = runtimeManager == null ? Collections.emptyList() : runtimeManager.getCatalogKeys();
        String keyLine = keys.isEmpty() ? "-" : String.join(", ", keys);
        sender.sendMessage(color(t(sender,
                "&6===== Lệnh Plugin =====",
                "&6===== Plugin Commands =====")));
        sender.sendMessage(color(t(sender,
                "&e/ai plugin list &7- Xem danh sách plugin đã cài",
                "&e/ai plugin list &7- Show installed plugins")));
        sender.sendMessage(color(t(sender,
                "&e/ai plugin download <plugin> &7- Tải plugin từ preset có sẵn, tự chọn bản phù hợp với server",
                "&e/ai plugin download <plugin> &7- Download a built-in plugin preset and auto-match the server build")));
        sender.sendMessage(color(t(sender,
                "&7Các preset hiện có: &f" + keyLine,
                "&7Built-in presets: &f" + keyLine)));
        sender.sendMessage(color(t(sender,
                "&e/ai plugin remove <plugin> &7- Gỡ plugin khỏi thư mục plugins",
                "&e/ai plugin remove <plugin> &7- Remove an installed plugin")));
    }

    private void sendHelp(CommandSender sender, boolean useAccess, boolean staffAccess, boolean adminAccess) {
        sender.sendMessage(color(t(sender,
                "&6===== Lệnh AIAdmin =====",
                "&6===== AIAdmin Commands =====")));
        sender.sendMessage(color(t(sender,
                "&eai <nội dung> &7- Nhắn riêng với Grox",
                "&eai <message> &7- Send a private message to Grox")));
        sender.sendMessage(color(t(sender,
                "&e/ai lang <english/vietnam> &7- Đổi ngôn ngữ cá nhân",
                "&e/ai lang <english/vietnam> &7- Change your personal language")));

        if (useAccess) {
            sender.sendMessage(color(t(sender,
                    "&e/ai scan &7- Quét server ngay",
                    "&e/ai scan &7- Run a server scan now")));
            sender.sendMessage(color(t(sender,
                    "&e/ai scan dashboard|gui &7- Mở dashboard scan mới nhất",
                    "&e/ai scan dashboard|gui &7- Open the latest scan dashboard")));
            sender.sendMessage(color(t(sender,
                    "&e/ai dashboard &7- Mở dashboard nghi vấn",
                    "&e/ai dashboard &7- Open the suspicion dashboard")));
        }

        if (staffAccess) {
            sender.sendMessage(color(t(sender,
                    "&e/ai status &7- Xem trạng thái hệ thống",
                    "&e/ai status &7- View system status")));
            sender.sendMessage(color(t(sender,
                    "&e/ai optimize &7- Tối ưu theo trạng thái server hiện tại",
                    "&e/ai optimize &7- Run server optimization")));
            sender.sendMessage(color(t(sender,
                    "&e/ai check <player> [gui|observe] &7- Phân tích người chơi",
                    "&e/ai check <player> [gui|observe] &7- Analyze a player")));
            sender.sendMessage(color(t(sender,
                    "&e/ai checkgui <player> &7- Mở GUI kiểm tra chi tiết",
                    "&e/ai checkgui <player> &7- Open the detailed check GUI")));
            sender.sendMessage(color(t(sender,
                    "&e/ai suspicion <player> &7- Xem điểm nghi ngờ",
                    "&e/ai suspicion <player> &7- Show suspicion score")));
            sender.sendMessage(color(t(sender,
                    "&e/ai addsus <player> <amount> &7- Cộng điểm nghi ngờ thủ công",
                    "&e/ai addsus <player> <amount> &7- Add suspicion manually")));
            sender.sendMessage(color(t(sender,
                    "&e/ai flag <player> <type> [points] [details] &7- Ghi nhận cảnh báo",
                    "&e/ai flag <player> <type> [points] [details] &7- Register a suspicious flag")));
            sender.sendMessage(color(t(sender,
                    "&e/ai observe <player> <on|off|mute> &7- Bật, tắt hoặc mute theo dõi",
                    "&e/ai observe <player> <on|off|mute> &7- Enable/disable/mute tracking")));
            sender.sendMessage(color(t(sender,
                    "&e/ai kick <player> [reason] &7- Kick người chơi",
                    "&e/ai kick <player> [reason] &7- Kick a player")));
            sender.sendMessage(color(t(sender,
                    "&e/ai termban <player> <reason> <time> &7- Termban 1s, 30m, 12h, 3d...",
                    "&e/ai termban <player> <reason> <time> &7- Temp-ban for 1s, 30m, 12h, 3d...")));
            sender.sendMessage(color(t(sender,
                    "&e/ai ban <player> [reason] &7- Ban qua AI_ban hoặc hệ thống nội bộ",
                    "&e/ai ban <player> [reason] &7- Ban through AI_ban or internal fallback")));
            sender.sendMessage(color(t(sender,
                    "&e/ai thongbao <nội dung> &7- Để AI viết lại và thông báo toàn server",
                    "&e/ai announce <message> &7- Let AI rewrite and announce to the whole server")));
            sender.sendMessage(color(t(sender,
                    "&e/ai admode <on|off|status> &7- Bật hoặc tắt chế độ Grox chat công khai",
                    "&e/ai admode <on|off|status> &7- Toggle Grox public relay mode")));
            sender.sendMessage(color(t(sender,
                    "&e/ai use config <english/vietnam> &7- Chuyển bộ ngôn ngữ của server",
                    "&e/ai use config <english/vietnam> &7- Switch the server language pack")));
            sender.sendMessage(color(t(sender,
                    "&e/ai bot help &7- Xem hướng dẫn bot",
                    "&e/ai bot help &7- Show bot commands")));
        }

        if (adminAccess) {
            sender.sendMessage(color(t(sender,
                    "&e/ai set <options> &7- Chỉnh cấu hình runtime",
                    "&e/ai set <options> &7- Update runtime config values")));
            sender.sendMessage(color(t(sender,
                    "&e/ai plugin <options> &7- Quản lý plugin runtime",
                    "&e/ai plugin <options> &7- Manage runtime plugins")));
        }
    }

    private void sendRestriction(CommandSender sender, boolean useAccess, boolean staffAccess, boolean adminAccess) {
        if (!useAccess && !staffAccess && !adminAccess) {
            sender.sendMessage(color(t(sender,
                    "&cBạn không có quyền dùng lệnh AIAdmin này.",
                    "&cYou do not have permission to use this AIAdmin command.")));
            return;
        }
        if (!adminAccess) {
            sender.sendMessage(color(t(sender,
                    "&cLệnh này cần quyền staff hoặc admin cao hơn.",
                    "&cThis command requires staff or admin permission.")));
        }
    }

    private String integrationState(CommandSender sender, String key, String pluginName) {
        boolean enabled = plugin.isPluginIntegrationEnabled(key);
        boolean installed = pluginName != null && !pluginName.isBlank()
                && Bukkit.getPluginManager().isPluginEnabled(pluginName);

        if ("ai_ban".equalsIgnoreCase(key)) {
            if (enabled && installed) {
                return color(t(sender, "&aBật &7(external LiteBans)", "&aEnabled &7(external LiteBans)"));
            }
            if (enabled) {
                return color(t(sender, "&eBật &7(fallback nội bộ)", "&eEnabled &7(internal fallback)"));
            }
            return color(t(sender, "&7Tắt &8(nội bộ)", "&7Disabled &8(internal mode)"));
        }

        if (enabled && installed) {
            return color(t(sender, "&aBật &7(" + pluginName + ")", "&aEnabled &7(" + pluginName + ")"));
        }
        if (enabled) {
            return color(t(sender, "&eBật nhưng chưa có plugin", "&eEnabled but plugin not detected"));
        }
        return color(t(sender, "&7Tắt", "&7Disabled"));
    }

    private String proxyState(CommandSender sender) {
        boolean enabled = plugin.isPluginIntegrationEnabled("velocity");
        return enabled
                ? color(t(sender, "&aBật &7(proxy-compatible mode)", "&aEnabled &7(proxy-compatible mode)"))
                : color(t(sender, "&7Tắt &8(direct mode)", "&7Disabled &8(direct mode)"));
    }

    private String t(CommandSender sender, String vietnamese, String english) {
        return plugin.tr(sender, vietnamese, english);
    }

    private String color(String input) {
        return input == null ? "" : input.replace("&", "\u00A7");
    }

    private boolean hasUseAccess(CommandSender sender) {
        return hasStaffAccess(sender) || sender.hasPermission("aiadmin.use");
    }

    private boolean hasStaffAccess(CommandSender sender) {
        return hasAdminAccess(sender) || sender.hasPermission("aiadmin.staff");
    }

    private boolean hasAdminAccess(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("aiadmin.admin") || sender.isOp();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean useAccess = hasUseAccess(sender);
        boolean staffAccess = hasStaffAccess(sender);
        boolean adminAccess = hasAdminAccess(sender);

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(List.of("help", "lang"));
            if (useAccess) {
                suggestions.addAll(List.of("scan", "dashboard"));
            }
            if (staffAccess) {
                suggestions.addAll(List.of(
                        "status", "reload", "optimize", "check", "checkgui", "suspicion", "addsus",
                        "flag", "observe", "watch", "kick", "ban", "termban", "thongbao",
                        "announce", "createbot", "choose", "bot", "admode", "adminmode", "use"
                ));
            }
            if (adminAccess) {
                suggestions.addAll(List.of("set", "plugin"));
            }
            return filterSuggestions(args[0], suggestions);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "lang":
                    return filterSuggestions(args[1], List.of("english", "vietnam"));
                case "scan":
                    return filterSuggestions(args[1], List.of("dashboard", "gui"));
                case "check":
                    return filterSuggestions(args[1], onlinePlayerNames());
                case "checkgui":
                case "suspicion":
                case "kick":
                case "ban":
                case "observe":
                case "watch":
                    return filterSuggestions(args[1], onlinePlayerNames());
                case "flag":
                    return filterSuggestions(args[1], onlinePlayerNames());
                case "termban":
                    return filterSuggestions(args[1], onlinePlayerNames());
                case "choose":
                    return filterSuggestions(args[1], List.of("bot"));
                case "bot":
                    return filterSuggestions(args[1], List.of("help", "list", "remove", "tp", "setup", "action", "report_chat"));
                case "admode":
                case "adminmode":
                    return filterSuggestions(args[1], List.of("on", "off", "status"));
                case "use":
                    return filterSuggestions(args[1], List.of("config"));
                case "set":
                    if (!adminAccess) {
                        return Collections.emptyList();
                    }
                    return filterSuggestions(args[1], List.of(
                            "max-players-per-scan", "max-acc-per-ip", "time-scan",
                            "suspicion", "openai", "ai_ban"
                    ));
                case "plugin":
                    if (!adminAccess) {
                        return Collections.emptyList();
                    }
                    return filterSuggestions(args[1], List.of("help", "list", "download", "remove"));
                default:
                    return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            switch (sub) {
                case "check":
                    return filterSuggestions(args[2], List.of("gui", "observe"));
                case "flag":
                    return filterSuggestions(args[2], List.of(
                            "killaura", "speed", "reach", "fly", "scaffold", "xray", "timer", "velocity", "spam"
                    ));
                case "observe":
                case "watch":
                    return filterSuggestions(args[2], List.of("on", "off", "mute"));
                case "bot":
                    if (args[1].equalsIgnoreCase("tp")) {
                        return filterSuggestions(args[2], onlinePlayerNames());
                    }
                    if (args[1].equalsIgnoreCase("setup")) {
                        return filterSuggestions(args[2], List.of(
                                "name", "follow", "look", "walk", "jump", "hit", "turn_ground",
                                "invulnerable", "tier", "observe", "ai", "ai_llm", "ai_interval", "ai_report_chat"
                        ));
                    }
                    if (args[1].equalsIgnoreCase("report_chat")) {
                        return filterSuggestions(args[2], List.of("on", "off"));
                    }
                    if (args[1].equalsIgnoreCase("action")) {
                        return filterSuggestions(args[2], List.of("add"));
                    }
                    return Collections.emptyList();
                case "use":
                    if (args[1].equalsIgnoreCase("config")) {
                        return filterSuggestions(args[2], List.of("english", "vietnam"));
                    }
                    return Collections.emptyList();
                case "set":
                    if (!adminAccess) {
                        return Collections.emptyList();
                    }
                    if (args[1].equalsIgnoreCase("suspicion")) {
                        return filterSuggestions(args[2], List.of("watch", "alert", "danger", "severe"));
                    }
                    if (args[1].equalsIgnoreCase("openai")) {
                        return filterSuggestions(args[2], List.of("enable", "model", "api_key"));
                    }
                    if (args[1].equalsIgnoreCase("ai_ban") || args[1].equalsIgnoreCase("litebans")) {
                        return filterSuggestions(args[2], List.of("enable", "ban_duration"));
                    }
                    return Collections.emptyList();
                case "plugin":
                    if (!adminAccess) {
                        return Collections.emptyList();
                    }
                    if (args[1].equalsIgnoreCase("download")) {
                        PluginRuntimeManager runtimeManager = plugin.getPluginRuntimeManager();
                        return runtimeManager == null
                                ? Collections.emptyList()
                                : filterSuggestions(args[2], runtimeManager.getCatalogKeys());
                    }
                    if (args[1].equalsIgnoreCase("remove")) {
                        return filterSuggestions(args[2], loadedPluginNames());
                    }
                    return Collections.emptyList();
                default:
                    return Collections.emptyList();
            }
        }

        if (args.length == 4) {
            if (sub.equals("bot") && args[1].equalsIgnoreCase("tp")) {
                return filterSuggestions(args[3], onlinePlayerNames());
            }
            if (sub.equals("bot") && args[1].equalsIgnoreCase("action") && args[2].equalsIgnoreCase("add")) {
                return filterSuggestions(args[3], List.of("move"));
            }
            if (sub.equals("set") && adminAccess) {
                if (args[1].equalsIgnoreCase("openai") && args[2].equalsIgnoreCase("enable")) {
                    return filterSuggestions(args[3], List.of("true", "false"));
                }
                if (args[1].equalsIgnoreCase("openai") && args[2].equalsIgnoreCase("model")) {
                    return filterSuggestions(args[3], List.of(
                            "gpt-5-mini",
                            "gpt-5-nano",
                            "llama-3.3-70b-versatile",
                            "llama-3.1-8b-instant",
                            "mixtral-8x7b-32768",
                            "deepseek-r1-distill-llama-70b"
                    ));
                }
                if ((args[1].equalsIgnoreCase("ai_ban") || args[1].equalsIgnoreCase("litebans"))
                        && args[2].equalsIgnoreCase("enable")) {
                    return filterSuggestions(args[3], List.of("true", "false"));
                }
            }
        }

        return Collections.emptyList();
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<String> loadedPluginNames() {
        List<String> names = new ArrayList<>();
        Bukkit.getPluginManager().getPlugins();
        Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(plugin -> plugin.getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        return names;
    }

    private List<String> filterSuggestions(String token, Collection<String> values) {
        String prefix = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> results = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (prefix.isBlank() || value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                results.add(value);
            }
        }
        results.sort(String.CASE_INSENSITIVE_ORDER);
        return results;
    }
}
