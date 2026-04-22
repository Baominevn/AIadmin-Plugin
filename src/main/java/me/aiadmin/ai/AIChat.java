package me.aiadmin.ai;

import me.aiadmin.AIAdmin;
import me.aiadmin.system.SuspicionManager;
import me.aiadmin.system.SuspicionManager.PlayerRiskProfile;
import me.aiadmin.system.SuspicionManager.RiskTier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class AIChat {

    private static class KnowledgeMatch {
        private final String reply;
        private final String context;

        private KnowledgeMatch(String reply, String context) {
            this.reply = reply;
            this.context = context;
        }
    }

    private final AIAdmin plugin;
    private final SuspicionManager suspicionManager;
    private final OpenAIService openAIService;

    private final Map<AIAdmin.ConfigProfile, FileConfiguration> customChatConfigs = new EnumMap<>(AIAdmin.ConfigProfile.class);

    public AIChat(AIAdmin plugin, SuspicionManager suspicionManager, OpenAIService openAIService) {
        this.plugin = plugin;
        this.suspicionManager = suspicionManager;
        this.openAIService = openAIService;
        reloadCustomChatConfig();
    }

    public void reloadCustomChatConfig() {
        customChatConfigs.clear();
        for (AIAdmin.ConfigProfile profile : AIAdmin.ConfigProfile.values()) {
            customChatConfigs.put(profile, plugin.loadLocaleConfiguration(profile, "ai_knowledge.yml", "ai_knowledge.yml"));
        }
    }

    public CompletableFuture<String> answerPlayerQuestion(Player player, String prompt, boolean adminMode) {
        AIAdmin.ConfigProfile language = plugin.getSenderLanguage(player);
        FileConfiguration chatConfig = getChatConfig(language);
        String customReply = findCustomReply(chatConfig, player, prompt);
        KnowledgeMatch knowledgeMatch = findKnowledgeMatch(language, player, prompt);
        String knowledgeReply = knowledgeMatch == null ? null : knowledgeMatch.reply;
        String defaultReply = chatConfig == null ? "" : chatConfig.getString("aichat.default_reply", "");
        boolean customFirst = chatConfig != null && chatConfig.getBoolean("aichat.use_custom_first", false);
        boolean directKnowledge = knowledgeMatch != null
                && getKnowledgeConfig(language).getBoolean("knowledge.use_direct_answer_when_matched", true);
        boolean preferModel = adminMode || chatConfig == null || chatConfig.getBoolean("aichat.prefer_model_when_available", true);
        boolean modelAvailable = openAIService != null && openAIService.isEnabled();

        if (customReply != null && customFirst && (!modelAvailable || !preferModel)) {
            recordLearningAndLogs(player, prompt, customReply, true, adminMode);
            return CompletableFuture.completedFuture(customReply);
        }
        if (knowledgeReply != null && directKnowledge && (!modelAvailable || !preferModel)) {
            recordLearningAndLogs(player, prompt, knowledgeReply, false, adminMode);
            return CompletableFuture.completedFuture(knowledgeReply);
        }

        CompletableFuture<String> replyFuture;
        if (modelAvailable && (preferModel || customReply == null || !customFirst)) {
            String modelPrompt = appendKnowledgeContext(prompt, knowledgeMatch, language);
            replyFuture = openAIService.askAssistant(player, modelPrompt, adminMode, language)
                    .thenApply(reply -> {
                        if (reply != null && !reply.isBlank()) {
                            return reply;
                        }
                        if (customReply != null) {
                            return customReply;
                        }
                        if (knowledgeReply != null) {
                            return knowledgeReply;
                        }
                        if (defaultReply != null && !defaultReply.isBlank()) {
                            return applyPlaceholders(player, defaultReply, prompt);
                        }
                        return buildFallbackReply(player, prompt, language);
                    });
        } else {
            String resolved = customReply != null
                    ? customReply
                    : (knowledgeReply != null
                    ? knowledgeReply
                    : (defaultReply != null && !defaultReply.isBlank()
                    ? applyPlaceholders(player, defaultReply, prompt)
                    : buildFallbackReply(player, prompt, language)));
            replyFuture = CompletableFuture.completedFuture(resolved);
        }

        return replyFuture
                .thenApply(finalReply -> {
                    boolean usedCustom = customReply != null && customReply.equals(finalReply);
                    recordLearningAndLogs(player, prompt, finalReply, usedCustom, adminMode);
                    return finalReply;
                });
    }

    public String buildFallbackReply(Player player, String prompt) {
        return buildFallbackReply(player, prompt, plugin.getSenderLanguage(player));
    }

    public String buildFallbackReply(Player player, String prompt, AIAdmin.ConfigProfile profile) {
        String question = prompt.toLowerCase(Locale.ROOT).trim();
        boolean english = profile == AIAdmin.ConfigProfile.ENGLISH;

        if (question.isEmpty()) {
            return english
                    ? "Just type `ai <message>` and I'll help you out."
                    : "Cứ gõ `ai <nội dung>`, mình sẽ hỗ trợ ngay.";
        }
        if (containsAny(question, "help", "lenh", "lầnh", "command")) {
            return english
                    ? "If you're staff, use `/ai help` to see the full command list. Players can ask me about rules, gameplay, and server info."
                    : "Nếu bạn là staff thì dùng `/ai help` để xem đầy đủ lệnh. Người chơi có thể hỏi mình về luật, cách chơi và thông tin server.";
        }
        if (containsAny(question, "rule", "luat", "luật")) {
            return english
                    ? "Play fair, respect staff, and do not use hacks, alt-farming, or bug exploits."
                    : "Hãy chơi công bằng, tôn trọng staff, không dùng hack, không farm alt và không lợi dụng bug.";
        }
        if (containsAny(question, "server", "info", "thong tin", "thông tin")) {
            return english
                    ? "AIAdmin helps staff monitor alerts, scan players, and answer basic server questions."
                    : "AIAdmin hỗ trợ staff theo dõi cảnh báo, quét người chơi và trả lời các câu hỏi cơ bản về server.";
        }
        if (containsAny(question, "ban", "kick", "hack")) {
            return english
                    ? "If the system sees multiple suspicious signs, staff will review the case before taking action."
                    : "Nếu hệ thống thấy nhiều dấu hiệu bất thường, staff sẽ kiểm tra kỹ hơn trước khi xử lý.";
        }
        if (containsAny(question, "ping", "online")) {
            return english
                    ? "There are currently " + Bukkit.getOnlinePlayers().size() + " players online."
                    : "Hiện có " + Bukkit.getOnlinePlayers().size() + " người chơi đang online.";
        }
        if (containsAny(question, "toi la ai", "tôi là ai", "who am i")) {
            return english ? "You're " + player.getName() + "." : "Bạn là " + player.getName() + ".";
        }

        return english
                ? "I'm the server admin assistant. Ask me about rules, how to play, or general server info."
                : "Mình là trợ lý admin của server. Bạn cứ hỏi về luật, cách chơi hoặc thông tin chung nhé.";
    }

    public String buildPlayerAnalysis(String playerName) {
        return buildPlayerAnalysis(playerName, plugin.getActiveConfigProfile());
    }

    public String buildPlayerAnalysis(String playerName, AIAdmin.ConfigProfile language) {
        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(playerName);
        RiskTier riskTier = suspicionManager.getRiskTier(profile.getSuspicion());
        String behaviorState = suspicionManager.describeBehaviorState(playerName, language == AIAdmin.ConfigProfile.ENGLISH);
        String latestEvidence = suspicionManager.getLatestEvidenceSummary(playerName, language == AIAdmin.ConfigProfile.ENGLISH);
        if (language == AIAdmin.ConfigProfile.ENGLISH) {
            return playerName + ": risk=" + riskTier.name()
                    + ", threat=" + suspicionManager.getThreatLevel(profile.getSuspicion()).name()
                    + ", skill=" + suspicionManager.getSkillClass(profile).name()
                    + ", state=" + behaviorState
                    + ", suspicion=" + profile.getSuspicion()
                    + ", alerts=" + profile.getTotalAlerts()
                    + ", last_ip=" + safe(profile.getLastKnownIp(), language)
                    + ", latest_evidence=" + latestEvidence
                    + ", movement=" + profile.describeMovement(true)
                    + ", learning=" + buildLearningSnapshot(profile, true);
        }
        return playerName + ": mức rủi ro=" + riskTier.name()
                + ", cấp độ=" + suspicionManager.getThreatLevel(profile.getSuspicion()).name()
                + ", phân loại kỹ năng=" + suspicionManager.getSkillClass(profile).name()
                + ", trạng thái=" + behaviorState
                + ", điểm nghi ngờ=" + profile.getSuspicion()
                + ", cảnh báo=" + profile.getTotalAlerts()
                + ", IP gần nhất=" + safe(profile.getLastKnownIp(), language)
                + ", bằng chứng mới nhất=" + latestEvidence
                + ", dữ liệu di chuyển=" + profile.describeMovement(false)
                + ", học quan sát=" + buildLearningSnapshot(profile, false);
    }

    public String buildServerReport() {
        return buildServerReport(plugin.getActiveConfigProfile());
    }

    public String buildServerReport(AIAdmin.ConfigProfile profile) {
        List<PlayerRiskProfile> topProfiles = suspicionManager.getTopProfiles(5);
        List<String> entries = new ArrayList<>();
        for (PlayerRiskProfile riskProfile : topProfiles) {
            entries.add(riskProfile.getName() + "=" + riskProfile.getSuspicion());
        }

        if (profile == AIAdmin.ConfigProfile.ENGLISH) {
            return "online=" + Bukkit.getOnlinePlayers().size()
                    + ", suspicious_players=" + suspicionManager.countAtOrAbove(RiskTier.WATCH)
                    + ", low=" + countThreat(SuspicionManager.ThreatLevel.LOW)
                    + ", medium=" + countThreat(SuspicionManager.ThreatLevel.MEDIUM)
                    + ", high=" + countThreat(SuspicionManager.ThreatLevel.HIGH)
                    + ", flagged_ips=" + suspicionManager.countFlaggedIps()
                    + ", total_alerts=" + suspicionManager.getRecordedAlertCount()
                    + ", AI={" + openAIService.getStatusSummary() + "}"
                    + ", top=" + (entries.isEmpty() ? "none" : String.join(", ", entries));
        }
        return "online=" + Bukkit.getOnlinePlayers().size()
                + ", người bị nghi ngờ=" + suspicionManager.countAtOrAbove(RiskTier.WATCH)
                + ", low=" + countThreat(SuspicionManager.ThreatLevel.LOW)
                + ", medium=" + countThreat(SuspicionManager.ThreatLevel.MEDIUM)
                + ", high=" + countThreat(SuspicionManager.ThreatLevel.HIGH)
                + ", IP bị gắn cờ=" + suspicionManager.countFlaggedIps()
                + ", tổng cảnh báo=" + suspicionManager.getRecordedAlertCount()
                + ", AI={" + openAIService.getStatusSummary() + "}"
                + ", top=" + (entries.isEmpty() ? "không có" : String.join(", ", entries));
    }

    public void sendStaffNotice(String message) {
        String formatted = color("&c[AIAdmin] &f" + message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("aiadmin.staff") || player.hasPermission("aiadmin.admin")) {
                player.sendMessage(formatted);
            }
        }
        plugin.getLogger().info("[StaffNotice] " + message);
    }

    public void relayAsAdmin(String response) {
        String fallbackName = plugin.getConfig().getString("chat.admin_mode_name", "Grox");
        String prefix = plugin.getConfig().getString("chat.admin_relay.prefix", "&c&l[ADMIN] ");
        String name = plugin.getConfig().getString("chat.admin_relay.name", "&f" + fallbackName);
        String separator = plugin.getConfig().getString("chat.admin_relay.separator", "&f: ");
        String messageFormat = plugin.getConfig().getString("chat.admin_relay.message", "&b{message}");
        String message = messageFormat.replace("{message}", decorateResponse(response));
        Bukkit.broadcastMessage(color(prefix + name + separator + message));
    }

    public CompletableFuture<String> generateServerAnnouncement(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            return CompletableFuture.completedFuture("");
        }

        String fallback = buildFallbackAnnouncement(input);
        if (openAIService == null || !openAIService.isEnabled()) {
            return CompletableFuture.completedFuture(fallback);
        }

        return openAIService.rewriteAnnouncement(input)
                .thenApply(reply -> {
                    if (reply == null || reply.isBlank()) {
                        return fallback;
                    }
                    return decorateResponse(reply);
                })
                .exceptionally(ex -> fallback);
    }

    public void sendActionPlan(CommandSender sender, String playerName) {
        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(playerName);
        RiskTier tier = suspicionManager.getRiskTier(profile.getSuspicion());
        sender.sendMessage(color("&6[AIAdmin] &f" + recommendAction(playerName, tier, profile.getAlertCounts(), plugin.getSenderLanguage(sender))));
    }

    public boolean isOpenAIEnabled() {
        return openAIService.isEnabled();
    }

    public String getApiKeyEnvName() {
        return openAIService.getConfiguredApiKeyEnv();
    }

    public String decorateResponse(String response) {
        if (response == null || response.isBlank()) {
            return response;
        }
        if (plugin.getOptionConfig() == null) {
            return response;
        }
        boolean expressionEnabled = plugin.getOptionConfig().getBoolean("ai.expressions.enabled", false);
        if (!expressionEnabled) {
            return response;
        }
        String prefix = plugin.getOptionConfig().getString("ai.expressions.prefix", "");
        String suffix = plugin.getOptionConfig().getString("ai.expressions.suffix", "");
        return (prefix == null ? "" : prefix) + response + (suffix == null ? "" : suffix);
    }

    private String buildFallbackAnnouncement(String input) {
        String cleaned = input.trim();
        String lowered = cleaned.toLowerCase(Locale.ROOT);
        AIAdmin.ConfigProfile profile = plugin.getActiveConfigProfile();
        if (lowered.startsWith("thông báo ")) {
            cleaned = cleaned.substring("thông báo ".length()).trim();
        } else if (lowered.startsWith("thong bao ")) {
            cleaned = cleaned.substring("thong bao ".length()).trim();
        } else if (lowered.startsWith("announce ")) {
            cleaned = cleaned.substring("announce ".length()).trim();
        }
        if (cleaned.isBlank()) {
            return profile == AIAdmin.ConfigProfile.ENGLISH
                    ? "Hey everyone, a new admin announcement is coming soon."
                    : "Mọi người ơi, sắp có thông báo mới từ admin nha.";
        }
        return profile == AIAdmin.ConfigProfile.ENGLISH
                ? "Hey everyone, " + ensureSentenceEnding(cleaned)
                : "Mọi người ơi, " + softenAnnouncement(cleaned);
    }

    private String findCustomReply(FileConfiguration chatConfig, Player player, String prompt) {
        if (chatConfig == null) {
            return null;
        }
        ConfigurationSection rulesSection = chatConfig.getConfigurationSection("aichat.rules");
        if (rulesSection == null) {
            return null;
        }

        String normalizedPrompt = prompt.toLowerCase(Locale.ROOT).trim();
        for (String ruleKey : rulesSection.getKeys(false)) {
            ConfigurationSection rule = rulesSection.getConfigurationSection(ruleKey);
            if (rule == null) {
                continue;
            }

            String reply = pickRuleReply(rule);
            if (reply.isBlank()) {
                continue;
            }

            String mode = rule.getString("mode", "contains").toLowerCase(Locale.ROOT);
            List<String> matches = rule.getStringList("match");
            if (matches.isEmpty()) {
                String singleMatch = rule.getString("match", "");
                if (!singleMatch.isBlank()) {
                    matches.add(singleMatch);
                }
            }

            for (String pattern : matches) {
                if (matchesRule(normalizedPrompt, pattern, mode)) {
                    return applyPlaceholders(player, reply, prompt);
                }
            }
        }
        return null;
    }

    private KnowledgeMatch findKnowledgeMatch(AIAdmin.ConfigProfile profile, Player player, String prompt) {
        FileConfiguration knowledgeConfig = getKnowledgeConfig(profile);
        if (knowledgeConfig == null || !knowledgeConfig.getBoolean("knowledge.enabled", true)) {
            return null;
        }

        ConfigurationSection entries = knowledgeConfig.getConfigurationSection("knowledge.entries");
        if (entries == null) {
            return null;
        }

        String normalizedPrompt = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).trim();
        int bestScore = 0;
        String bestReply = null;
        String bestTitle = null;

        for (String key : entries.getKeys(false)) {
            ConfigurationSection entry = entries.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }

            int score = 0;
            List<String> keywords = entry.getStringList("keywords");
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank() && normalizedPrompt.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            if (score <= 0) {
                continue;
            }

            String answer = entry.getString("answer", "");
            if (answer.isBlank()) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestReply = applyPlaceholders(player, answer, prompt);
                bestTitle = entry.getString("title", key);
            }
        }

        if (bestReply == null) {
            return null;
        }
        String context = (bestTitle == null ? "knowledge" : bestTitle) + ": " + bestReply;
        return new KnowledgeMatch(bestReply, context);
    }

    private String appendKnowledgeContext(String prompt, KnowledgeMatch knowledgeMatch, AIAdmin.ConfigProfile profile) {
        if (knowledgeMatch == null || knowledgeMatch.context == null || knowledgeMatch.context.isBlank()) {
            return prompt;
        }
        boolean english = profile == AIAdmin.ConfigProfile.ENGLISH;
        return prompt + "\n\n" + (english ? "Known server context:\n" : "Ngữ cảnh server đã biết:\n") + knowledgeMatch.context;
    }

    private FileConfiguration getKnowledgeConfig(AIAdmin.ConfigProfile profile) {
        return getChatConfig(profile);
    }

    private String pickRuleReply(ConfigurationSection rule) {
        List<String> replies = rule.getStringList("replies");
        if (!replies.isEmpty()) {
            int index = ThreadLocalRandom.current().nextInt(replies.size());
            String candidate = replies.get(index);
            return candidate == null ? "" : candidate;
        }
        return rule.getString("reply", "");
    }

    private boolean matchesRule(String normalizedPrompt, String patternText, String mode) {
        if (patternText == null || patternText.isBlank()) {
            return false;
        }
        String pattern = patternText.toLowerCase(Locale.ROOT).trim();
        switch (mode) {
            case "equals":
                return normalizedPrompt.equals(pattern);
            case "starts_with":
            case "startswith":
                return normalizedPrompt.startsWith(pattern);
            case "ends_with":
            case "endswith":
                return normalizedPrompt.endsWith(pattern);
            case "regex":
                try {
                    return Pattern.compile(patternText, Pattern.CASE_INSENSITIVE).matcher(normalizedPrompt).find();
                } catch (PatternSyntaxException ex) {
                    plugin.getLogger().warning("Invalid regex in ai_knowledge.yml: " + patternText);
                    return false;
                }
            case "contains":
            default:
                return normalizedPrompt.contains(pattern);
        }
    }

    private String applyPlaceholders(Player player, String text, String prompt) {
        return text
                .replace("{player}", player.getName())
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{prompt}", prompt);
    }

    private String recommendAction(String playerName, RiskTier tier, Map<String, Integer> alertCounts, AIAdmin.ConfigProfile profile) {
        boolean english = profile == AIAdmin.ConfigProfile.ENGLISH;
        if (tier == RiskTier.CLEAR) {
            return english
                    ? playerName + " is low risk right now. Keep an eye on them during regular scans."
                    : playerName + " đang ở mức rủi ro thấp. Cứ tiếp tục theo dõi qua các lượt scan định kỳ.";
        }
        if (tier == RiskTier.WATCH) {
            return english
                    ? playerName + " shows light suspicious signs. Run `/ai observe " + playerName + " on` and compare alert history."
                    : playerName + " có dấu hiệu bất thường nhẹ. Nên dùng `/ai observe " + playerName + " on` rồi đối chiếu lịch sử cảnh báo.";
        }
        if (tier == RiskTier.ALERT) {
            return english
                    ? playerName + " is at a high alert level. Staff should review " + summarizeAlerts(alertCounts, profile) + " and watch closely."
                    : playerName + " đang ở mức cảnh báo cao. Nên gọi staff đang online vào kiểm tra kỹ và đối chiếu "
                    + summarizeAlerts(alertCounts, profile) + ".";
        }
        if (tier == RiskTier.DANGER) {
            return english
                    ? playerName + " is in the danger zone. Consider a kick first, then review whether a temp-ban is warranted."
                    : playerName + " đang ở mức nguy hiểm. Có thể kick trước để chặn ảnh hưởng rồi xem xét termban.";
        }
        return english
                ? playerName + " shows clear violations. Verify quickly and move to a ban if staff confirms it."
                : playerName + " có dấu hiệu vi phạm rất rõ. Nên xác minh nhanh rồi xử lý ban nếu staff xác nhận.";
    }

    private String summarizeAlerts(Map<String, Integer> alertCounts, AIAdmin.ConfigProfile profile) {
        if (alertCounts.isEmpty()) {
            return profile == AIAdmin.ConfigProfile.ENGLISH ? "movement data" : "dữ liệu vận động";
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : alertCounts.entrySet()) {
            entries.add(entry.getKey() + " x" + entry.getValue());
        }
        return String.join(", ", entries);
    }

    private String buildLearningSnapshot(PlayerRiskProfile profile, boolean english) {
        int currentCps = profile.getCurrentCps();
        int peakCps = profile.getPeakCps();
        if (english) {
            return "hack_conf=" + profile.getHackConfidence()
                    + ", pro_conf=" + profile.getProConfidence()
                    + ", aim=" + profile.getSuspiciousAimSamples()
                    + ", cps_now=" + currentCps
                    + ", cps_peak_recent=" + peakCps
                    + ", cps_flags=" + profile.getHighCpsSamples()
                    + ", fly=" + profile.getHoverFlySamples()
                    + ", scaffold=" + profile.getScaffoldSamples()
                    + ", xray=" + profile.getXraySamples()
                    + ", spam=" + profile.getChatSpamSamples()
                    + ", legit_combat=" + profile.getLegitCombatSamples();
        }
        return "hack_conf=" + profile.getHackConfidence()
                + ", pro_conf=" + profile.getProConfidence()
                + ", aim=" + profile.getSuspiciousAimSamples()
                + ", cps_hien_tai=" + currentCps
                + ", cps_peak_gan_day=" + peakCps
                + ", cps_flags=" + profile.getHighCpsSamples()
                + ", fly=" + profile.getHoverFlySamples()
                + ", scaffold=" + profile.getScaffoldSamples()
                + ", xray=" + profile.getXraySamples()
                + ", spam=" + profile.getChatSpamSamples()
                + ", combat_legit=" + profile.getLegitCombatSamples();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value, AIAdmin.ConfigProfile profile) {
        if (value == null || value.isEmpty()) {
            return profile == AIAdmin.ConfigProfile.ENGLISH ? "unknown" : "không rõ";
        }
        return value;
    }

    private String ensureSentenceEnding(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return trimmed;
        }
        return trimmed + ".";
    }

    private String softenAnnouncement(String text) {
        if (text == null || text.isBlank()) {
            return "Sắp có thông báo mới từ admin nha.";
        }
        String trimmed = text.trim();
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return trimmed;
        }
        return trimmed + " nha.";
    }

    private int countThreat(SuspicionManager.ThreatLevel level) {
        int count = 0;
        for (PlayerRiskProfile profile : suspicionManager.getTopProfiles(500)) {
            if (suspicionManager.getRiskTier(profile.getSuspicion()) == RiskTier.CLEAR) {
                continue;
            }
            if (suspicionManager.getThreatLevel(profile.getSuspicion()) == level) {
                count++;
            }
        }
        return count;
    }

    private void recordLearningAndLogs(Player player, String prompt, String reply, boolean customReply, boolean adminMode) {
        if (plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().logChatEvent(player.getName(), prompt, reply, customReply, adminMode);
        }
    }

    private FileConfiguration getChatConfig(AIAdmin.ConfigProfile profile) {
        FileConfiguration config = customChatConfigs.get(profile);
        if (config != null) {
            return config;
        }
        return customChatConfigs.get(plugin.getActiveConfigProfile());
    }

    private String color(String input) {
        return input.replace("&", "\u00A7");
    }
}
