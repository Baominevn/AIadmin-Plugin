package me.aiadmin.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.aiadmin.AIAdmin;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class OpenAIService {

    private final AIAdmin plugin;
    private final Gson gson;
    private HttpClient httpClient;

    public OpenAIService(AIAdmin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
        reloadClient();
    }

    public void reloadClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(plugin.getConfig().getLong("openai.connect_timeout_seconds", 10L)))
                .build();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("openai.enabled", false) && !getApiKey().isBlank();
    }

    public String getStatusSummary() {
        return "enabled=" + isEnabled()
                + ", model=" + plugin.getConfig().getString("openai.model", "gpt-5-mini")
                + ", style=" + resolveApiStyle()
                + ", endpoint=" + resolveEndpoint();
    }

    public String getConfiguredApiKeyEnv() {
        return plugin.getConfig().getString("openai.api_key_env", "OPENAI_API_KEY");
    }

    public CompletableFuture<String> askAssistant(Player player, String userPrompt, boolean adminMode) {
        return askAssistant(player, userPrompt, adminMode, plugin.getSenderLanguage(player));
    }

    public CompletableFuture<String> askAssistant(Player player, String userPrompt, boolean adminMode, AIAdmin.ConfigProfile languageProfile) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture("");
        }
        int maxOutputTokens = plugin.getConfig().getInt("openai.max_output_tokens", 180);
        return requestModel(buildInstructions(player, adminMode, languageProfile), buildInput(player, userPrompt, adminMode), maxOutputTokens);
    }

    public CompletableFuture<String> rewriteAnnouncement(String rawText) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture("");
        }
        String cleaned = rawText == null ? "" : rawText.trim();
        if (cleaned.isBlank()) {
            return CompletableFuture.completedFuture("");
        }

        int maxOutputTokens = Math.max(80, plugin.getConfig().getInt("openai.max_output_tokens", 180));
        AIAdmin.ConfigProfile profile = plugin.getActiveConfigProfile();
        String instructions = buildAnnouncementInstructions(profile);
        String input = profile == AIAdmin.ConfigProfile.ENGLISH
                ? "Admin request: " + cleaned + "\nRewrite this into one short, natural English server announcement. "
                + "Do not include markdown, do not include prefix labels, output only one line."
                : "Yêu cầu từ admin: " + cleaned + "\nHãy viết lại thành một câu thông báo ngắn, tự nhiên bằng tiếng Việt cho toàn server. "
                + "Không dùng markdown, không thêm prefix, chỉ trả ra một dòng.";
        return requestModel(instructions, input, maxOutputTokens);
    }

    private CompletableFuture<String> requestModel(String instructions, String input, int maxOutputTokens) {
        String apiStyle = resolveApiStyle();
        JsonObject payload = "chat_completions".equals(apiStyle)
                ? buildChatCompletionsPayload(instructions, input, maxOutputTokens)
                : buildResponsesPayload(instructions, input, maxOutputTokens);
        String body = gson.toJson(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveEndpoint(apiStyle)))
                .timeout(Duration.ofSeconds(plugin.getConfig().getLong("openai.request_timeout_seconds", 20L)))
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::validateResponse)
                .thenApply(responseBody -> extractText(responseBody, apiStyle))
                .thenApply(this::sanitizeReply)
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("OpenAI request failed: " + throwable.getMessage());
                    return "";
                });
    }

    private JsonObject buildResponsesPayload(String instructions, String input, int maxOutputTokens) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", plugin.getConfig().getString("openai.model", "gpt-5-mini"));
        payload.addProperty("instructions", instructions);
        payload.addProperty("input", input);
        payload.addProperty("max_output_tokens", maxOutputTokens);

        boolean includeReasoning = plugin.getConfig().getBoolean("openai.include_reasoning_payload", true);
        String reasoningEffort = plugin.getConfig().getString("openai.reasoning_effort", "low");
        if (includeReasoning && reasoningEffort != null && !reasoningEffort.isBlank()) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", reasoningEffort.toLowerCase(Locale.ROOT));
            payload.add("reasoning", reasoning);
        }

        boolean includeTextPayload = plugin.getConfig().getBoolean("openai.include_text_payload", true);
        if (includeTextPayload) {
            JsonObject text = new JsonObject();
            text.addProperty("verbosity", plugin.getConfig().getString("openai.verbosity", "low"));
            payload.add("text", text);
        }
        return payload;
    }

    private JsonObject buildChatCompletionsPayload(String instructions, String input, int maxOutputTokens) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", plugin.getConfig().getString("openai.model", "gpt-5-mini"));
        payload.addProperty("max_tokens", maxOutputTokens);
        payload.addProperty("temperature", plugin.getConfig().getDouble("openai.temperature", 0.9D));
        payload.addProperty("top_p", plugin.getConfig().getDouble("openai.top_p", 1.0D));

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", instructions);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", input);
        messages.add(user);

        payload.add("messages", messages);
        return payload;
    }

    private String buildAnnouncementInstructions(AIAdmin.ConfigProfile profile) {
        String base = plugin.getConfig().getString("openai.system_prompt",
                "You are AIAdmin, a Minecraft server admin assistant.");
        StringBuilder builder = new StringBuilder(base);
        builder.append(" You are now writing a public server announcement for all players. ");
        builder.append(profile == AIAdmin.ConfigProfile.ENGLISH
                ? " Always respond in English. "
                : " Always respond in Vietnamese. ");
        builder.append(" Sound like a real human admin, not a system template. ");
        builder.append(" Keep it friendly, natural, concise, and easy to read. ");
        builder.append(" Limit to one short sentence when possible. ");
        builder.append(" Never mention policy text, safety notes, or internal reasoning. ");
        appendRuleInstructions(builder);
        return builder.toString();
    }

    private String buildInstructions(Player player, boolean adminMode, AIAdmin.ConfigProfile languageProfile) {
        String base = plugin.getConfig().getString("openai.system_prompt",
                "You are AIAdmin, a Minecraft server admin assistant. Answer briefly, safely, and only about server help, rules, general info, or admin support.");
        String language = languageProfile == AIAdmin.ConfigProfile.ENGLISH ? "en" : "vi";
        String persona = "smart, clear, useful";
        String intelligenceMode = "balanced";
        String tone = "natural";
        String slangLevel = "medium";
        boolean humorEnabled = true;
        int maxSentences = 4;
        FileConfiguration optionConfig = plugin.loadLocaleConfiguration(languageProfile, "option.yml", "option.yml");
        if (optionConfig != null) {
            language = optionConfig.getString("ai.language", language);
            persona = optionConfig.getString("ai.persona", persona);
            intelligenceMode = optionConfig.getString("ai.intelligence_mode", intelligenceMode);
            tone = optionConfig.getString("ai.tone", tone);
            slangLevel = optionConfig.getString("ai.slang_level", slangLevel);
            humorEnabled = optionConfig.getBoolean("ai.humor.enabled", humorEnabled);
            maxSentences = Math.max(1, optionConfig.getInt("ai.response_style.max_sentences", maxSentences));
        }

        StringBuilder builder = new StringBuilder(base);
        builder.append(" Always respond in ").append("vi".equalsIgnoreCase(language) ? "Vietnamese" : "English").append(". ");
        builder.append(" Persona: ").append(persona).append(". ");
        builder.append(" Intelligence mode: ").append(intelligenceMode).append(". ");
        builder.append(" Tone: ").append(tone).append(". ");
        builder.append(" Slang level: ").append(slangLevel).append(". ");
        builder.append(" Keep replies short, max ").append(maxSentences).append(" sentences. ");
        builder.append(" Speak naturally like a real player, not robotic. ");
        builder.append(" Start with the direct answer instead of a long intro. ");
        builder.append(" Prefer useful and concrete answers over generic filler. ");
        builder.append(" If the player asks about commands, include exact command examples when relevant. ");
        builder.append(" If the player asks about rules or setup, summarize clearly and give 1 or 2 practical details. ");
        builder.append(" If the question is vague, make the best helpful assumption instead of refusing. ");
        builder.append(" If needed, ask only one short follow-up question. ");
        builder.append(" Avoid repeating the same stock sentence too often. ");
        builder.append(" You can use light Gen Z style and a small joke sometimes when context fits. ");
        if (humorEnabled) {
            builder.append(" Humor is allowed but keep it clean. ");
        } else {
            builder.append(" Avoid jokes. ");
        }
        builder.append(" Never spam chat. Never claim actions you cannot verify. ");
        builder.append(" If unsure, say staff should review. ");

        if (adminMode) {
            builder.append("You are speaking publicly on behalf of staff right now. ");
            builder.append("Be clear and calm, but still human and natural. ");
            builder.append("Prefer one or two short sentences. ");
        } else {
            builder.append("You are replying privately to a player who explicitly called you with 'ai <message>'. ");
            builder.append("Do not pretend the whole server can hear this reply. ");
        }

        appendRuleInstructions(builder);

        builder.append("Player name: ").append(player.getName()).append(". ");
        builder.append("Online player count: ").append(plugin.getServer().getOnlinePlayers().size()).append(". ");
        builder.append("Active config profile: ").append(languageProfile.getFolderName()).append(". ");
        return builder.toString();
    }

    private String buildInput(Player player, String userPrompt, boolean adminMode) {
        StringBuilder builder = new StringBuilder();
        builder.append("Player: ").append(player.getName()).append("\n");
        builder.append("Mode: ").append(adminMode ? "admin-broadcast" : "direct-player-help").append("\n");
        builder.append("Question: ").append(userPrompt);
        return builder.toString();
    }

    private String validateResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        throw new CompletionException(new IOException("OpenAI API returned HTTP " + status + ": " + response.body()));
    }

    private String extractText(String body, String apiStyle) {
        if ("chat_completions".equals(apiStyle)) {
            return extractChatCompletionsText(body);
        }
        return extractResponsesText(body);
    }

    private String extractResponsesText(String body) {
        JsonObject json = gson.fromJson(body, JsonObject.class);
        if (json == null) {
            return "";
        }

        JsonElement outputText = json.get("output_text");
        if (outputText != null && !outputText.isJsonNull()) {
            String text = outputText.getAsString();
            if (!text.isBlank()) {
                return text;
            }
        }

        JsonArray output = json.getAsJsonArray("output");
        if (output == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonElement item : output) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonArray content = item.getAsJsonObject().getAsJsonArray("content");
            if (content == null) {
                continue;
            }
            for (JsonElement contentItem : content) {
                if (!contentItem.isJsonObject()) {
                    continue;
                }
                JsonObject contentObject = contentItem.getAsJsonObject();
                JsonElement text = contentObject.get("text");
                if (text != null && !text.isJsonNull()) {
                    builder.append(text.getAsString()).append(' ');
                }
            }
        }
        return builder.toString().trim();
    }

    private String extractChatCompletionsText(String body) {
        JsonObject json = gson.fromJson(body, JsonObject.class);
        if (json == null) {
            return "";
        }

        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return "";
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        if (firstChoice == null || !firstChoice.has("message")) {
            return "";
        }

        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            return "";
        }

        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonElement item : content.getAsJsonArray()) {
            if (item != null && item.isJsonObject()) {
                JsonElement text = item.getAsJsonObject().get("text");
                if (text != null && !text.isJsonNull()) {
                    builder.append(text.getAsString()).append(' ');
                }
            }
        }
        return builder.toString().trim();
    }

    private String sanitizeReply(String raw) {
        if (raw == null) {
            return "";
        }
        String clean = applyRuleFilters(raw.replaceAll("\\s+", " ").trim());
        clean = clean.replaceAll("(?i)^\\[admin\\]\\s*grox\\s*:\\s*", "");
        clean = clean.replaceAll("(?i)^grox\\s*:\\s*", "");
        int limit = plugin.getConfig().getInt("openai.max_reply_chars", 320);
        if (clean.length() > limit) {
            clean = clean.substring(0, limit).trim() + "...";
        }
        return clean;
    }

    private String getApiKey() {
        String configKey = plugin.getConfig().getString("openai.api_key", "");
        if (configKey != null && !configKey.isBlank()) {
            return configKey.trim();
        }
        String preferredEnv = getConfiguredApiKeyEnv();
        String envKey = preferredEnv == null || preferredEnv.isBlank() ? null : System.getenv(preferredEnv.trim());
        if (envKey != null) {
            return envKey.trim();
        }
        String fallbackOpenAI = System.getenv("OPENAI_API_KEY");
        if (fallbackOpenAI != null && !fallbackOpenAI.isBlank()) {
            return fallbackOpenAI.trim();
        }
        String fallbackGroq = System.getenv("GROQ_API_KEY");
        if (fallbackGroq != null && !fallbackGroq.isBlank()) {
            return fallbackGroq.trim();
        }
        return "";
    }

    private String resolveEndpoint() {
        return resolveEndpoint(resolveApiStyle());
    }

    private String resolveEndpoint(String apiStyle) {
        String configured = plugin.getConfig().getString("openai.endpoint", "https://api.openai.com/v1/responses");
        if (configured == null || configured.isBlank()) {
            return "chat_completions".equals(apiStyle)
                    ? "https://api.openai.com/v1/chat/completions"
                    : "https://api.openai.com/v1/responses";
        }
        String endpoint = configured.trim();
        if ("chat_completions".equals(apiStyle)) {
            if (endpoint.endsWith("/chat/completions")) {
                return endpoint;
            }
            if (endpoint.endsWith("/")) {
                return endpoint + "chat/completions";
            }
            if (endpoint.endsWith("/v1")) {
                return endpoint + "/chat/completions";
            }
            if (endpoint.contains("/openai/v1")) {
                return endpoint + "/chat/completions";
            }
            if (Objects.equals(endpoint, "https://api.openai.com/v1")) {
                return endpoint + "/chat/completions";
            }
            return endpoint;
        }
        if (endpoint.endsWith("/responses")) {
            return endpoint;
        }
        if (endpoint.endsWith("/")) {
            return endpoint + "responses";
        }
        if (endpoint.endsWith("/v1")) {
            return endpoint + "/responses";
        }
        if (endpoint.contains("/openai/v1")) {
            return endpoint + "/responses";
        }
        if (Objects.equals(endpoint, "https://api.openai.com/v1")) {
            return endpoint + "/responses";
        }
        return endpoint;
    }

    private String resolveApiStyle() {
        String configuredStyle = plugin.getConfig().getString("openai.api_style", "auto");
        String normalizedStyle = configuredStyle == null ? "auto" : configuredStyle.trim().toLowerCase(Locale.ROOT);
        if (normalizedStyle.equals("chat") || normalizedStyle.equals("chat_completions") || normalizedStyle.equals("chat-completions")) {
            return "chat_completions";
        }
        if (normalizedStyle.equals("responses") || normalizedStyle.equals("response")) {
            return "responses";
        }

        String endpoint = plugin.getConfig().getString("openai.endpoint", "");
        String normalizedEndpoint = endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT);
        if (normalizedEndpoint.contains("groq.com") || normalizedEndpoint.endsWith("/chat/completions")) {
            return "chat_completions";
        }
        return "responses";
    }

    private void appendRuleInstructions(StringBuilder builder) {
        if (plugin.getRuleConfig() == null || !plugin.getRuleConfig().getBoolean("rule.enabled", true)) {
            return;
        }

        List<String> bannedTopics = getRuleList("rule.blocked_topics");
        if (!bannedTopics.isEmpty()) {
            builder.append(" Never discuss these topics: ")
                    .append(String.join(", ", bannedTopics))
                    .append(". ");
        }

        List<String> bannedPhrases = getRuleList("rule.blocked_phrases");
        if (!bannedPhrases.isEmpty()) {
            builder.append(" Never output these phrases or close variants: ")
                    .append(String.join(", ", bannedPhrases))
                    .append(". ");
        }

        String safeReply = plugin.getRuleConfig().getString(
                "rule.safe_reply",
                "Mình không hỗ trợ phần đó. Hỏi mình phần khác nha."
        );
        builder.append(" If user asks forbidden content, respond exactly with: ").append(safeReply).append(". ");
    }

    private String applyRuleFilters(String input) {
        if (plugin.getRuleConfig() == null || !plugin.getRuleConfig().getBoolean("rule.enabled", true)) {
            return input;
        }
        String safeReply = plugin.getRuleConfig().getString(
                "rule.safe_reply",
                "Mình không hỗ trợ phần đó. Hỏi mình phần khác nha."
        );

        for (String phrase : getRuleList("rule.blocked_phrases")) {
            if (!phrase.isBlank() && containsIgnoreCase(input, phrase)) {
                return safeReply;
            }
        }
        for (String topic : getRuleList("rule.blocked_topics")) {
            if (!topic.isBlank() && containsIgnoreCase(input, topic)) {
                return safeReply;
            }
        }
        return input;
    }

    private List<String> getRuleList(String path) {
        if (plugin.getRuleConfig() == null) {
            return List.of();
        }
        List<String> raw = plugin.getRuleConfig().getStringList(path);
        List<String> cleaned = new ArrayList<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
