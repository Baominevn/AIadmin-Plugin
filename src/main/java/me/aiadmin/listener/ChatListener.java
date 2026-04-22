package me.aiadmin.listener;

import me.aiadmin.AIAdmin;
import me.aiadmin.ai.AIChat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatListener implements Listener {
    private final AIAdmin plugin;
    private final AIChat aiChat;
    private final Map<UUID, Long> lastRequestTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicInteger activeRequests = new AtomicInteger();

    public ChatListener(AIAdmin plugin, AIChat aiChat) {
        this.plugin = plugin;
        this.aiChat = aiChat;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        String prefix = plugin.getConfig().getString("chat.trigger_prefix", "ai ");
        boolean aiTriggered = message.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
        if (!aiTriggered) {
            if (plugin.getSuspicionManager() != null) {
                Player chatter = event.getPlayer();
                Bukkit.getScheduler().runTask(plugin, () -> plugin.getSuspicionManager().recordChatMessage(chatter, message));
            }
            return;
        }
        if (!plugin.getConfig().getBoolean("chat.enable_chat_ai", true)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(color(plugin.tr(player,
                    "&eChat AI hiện đang tắt.",
                    "&eChat AI is currently disabled."))));
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        String prompt = message.substring(prefix.length()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> respond(player, prompt, prefix));
    }

    private void respond(Player player, String prompt, String prefix) {
        if (prompt.isEmpty()) {
            player.sendMessage(color(plugin.tr(player,
                    "&cHãy nhập nội dung sau '" + prefix + "'. Ví dụ: " + prefix + "server này có gì?",
                    "&cPlease enter a message after '" + prefix + "'. Example: " + prefix + "how do I play here")));
            return;
        }

        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        long cooldownMillis = plugin.getConfig().getLong("chat.request_cooldown_seconds", 5L) * 1000L;
        long lastRequest = lastRequestTimes.getOrDefault(playerId, 0L);
        if (cooldownMillis > 0 && now - lastRequest < cooldownMillis) {
            long waitSeconds = Math.max(1L, (cooldownMillis - (now - lastRequest) + 999L) / 1000L);
            player.sendMessage(color(plugin.tr(player,
                    "&cBạn hỏi hơi nhanh đó. Thử lại sau " + waitSeconds + "s nhé.",
                    "&cYou're asking a bit too fast. Try again in " + waitSeconds + "s.")));
            return;
        }

        if (pendingRequests.putIfAbsent(playerId, Boolean.TRUE) != null) {
            player.sendMessage(color(plugin.tr(player,
                    "&eGrox vẫn đang xử lý tin nhắn trước của bạn. Chờ xíu rồi hỏi tiếp nhé.",
                    "&eGrox is still processing your previous message. Please wait a moment.")));
            return;
        }

        int maxConcurrent = Math.max(1, plugin.getConfig().getInt("chat.max_concurrent_requests", 3));
        int currentRequests = activeRequests.incrementAndGet();
        if (currentRequests > maxConcurrent) {
            activeRequests.decrementAndGet();
            pendingRequests.remove(playerId);
            player.sendMessage(color(plugin.tr(player,
                    "&eGrox đang bận trả lời nhiều người cùng lúc. Thử lại sau ít giây nhé.",
                    "&eGrox is busy handling several requests right now. Please try again shortly.")));
            return;
        }

        lastRequestTimes.put(playerId, now);
        int maxPromptChars = Math.max(40, plugin.getConfig().getInt("chat.max_prompt_chars", 700));
        String normalizedPrompt = prompt.length() > maxPromptChars ? prompt.substring(0, maxPromptChars) : prompt;

        aiChat.answerPlayerQuestion(player, normalizedPrompt, false)
                .whenComplete((response, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingRequests.remove(playerId);
                    activeRequests.decrementAndGet();

                    if (throwable != null || response == null || response.isBlank()) {
                        player.sendMessage(color(plugin.tr(player,
                                "&cGrox tạm thời chưa phản hồi được. Thử lại sau nhé.",
                                "&cGrox is temporarily unavailable. Please try again later.")));
                        return;
                    }

                    String dmName = plugin.getConfig().getString("chat.admin_mode_name", "Grox");
                    player.sendMessage(color("&b" + dmName + " &7> &f" + aiChat.decorateResponse(response)));
                }));
    }

    private String color(String input) {
        return input == null ? "" : input.replace("&", "\u00A7");
    }
}
