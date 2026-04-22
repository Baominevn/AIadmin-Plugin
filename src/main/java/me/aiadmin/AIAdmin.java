package me.aiadmin;

import me.aiadmin.ai.AIChat;
import me.aiadmin.ai.OpenAIService;
import me.aiadmin.command.AdminCommand;
import me.aiadmin.listener.BlockBreakListener;
import me.aiadmin.listener.BlockPlaceListener;
import me.aiadmin.listener.ClickListener;
import me.aiadmin.listener.CombatListener;
import me.aiadmin.listener.ChatListener;
import me.aiadmin.listener.JoinListener;
import me.aiadmin.listener.MovementListener;
import me.aiadmin.listener.PlayerInteractListener;
import me.aiadmin.listener.QuitListener;
import me.aiadmin.placeholder.AIAdminPlaceholderExpansion;
import me.aiadmin.system.AntiCheatConsoleListener;
import me.aiadmin.system.BotManager;
import me.aiadmin.system.DatabaseManager;
import me.aiadmin.system.LagOptimizer;
import me.aiadmin.system.LearningManager;
import me.aiadmin.system.PluginRuntimeManager;
import me.aiadmin.system.ServerScanner;
import me.aiadmin.system.StatsManager;
import me.aiadmin.system.SuspicionManager;
import me.aiadmin.ui.SuspicionDashboard;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AIAdmin extends JavaPlugin {

    public enum ConfigProfile {
        ENGLISH("english"),
        VIETNAMESE("vietnamese");

        private final String folderName;

        ConfigProfile(String folderName) {
            this.folderName = folderName;
        }

        public String getFolderName() {
            return folderName;
        }

        public static ConfigProfile fromInput(String input) {
            if (input == null || input.isBlank()) {
                return null;
            }
            String normalized = input.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("en") || normalized.equals("eng") || normalized.equals("english")) {
                return ENGLISH;
            }
            if (normalized.equals("vi") || normalized.equals("vn") || normalized.equals("vietnam") || normalized.equals("vietnamese")) {
                return VIETNAMESE;
            }
            return null;
        }
    }

    private static AIAdmin instance;

    private static final String[] LOCALIZED_RESOURCES = new String[]{
            "config.yml",
            "ai_knowledge.yml",
            "option.yml",
            "ai_ban.yml",
            "database.yml",
            "learning.yml",
            "bot/bot.yml",
            "bot/bot_body.yml",
            "bot/bot_rule.yml"
    };

    private static final String[] SHARED_RESOURCES = new String[]{
            "setting_plugin.yml"
    };

    private static final String[] PROFILE_SHARED_RESOURCES = new String[]{
            "database.yml",
            "learning.yml",
            "bot/bot.yml",
            "bot/bot_body.yml",
            "bot/bot_rule.yml"
    };

    private static final Map<String, String[]> PROFILE_LOCALIZED_PATHS = Map.of(
            "config.yml", new String[]{
                    "use-config",
                    "actions.kick_message",
                    "actions.pipeline.ban_reason",
                    "actions.pipeline.hack_tempban_reason",
                    "ai_ban.ban_reason",
                    "openai.system_prompt"
            },
            "option.yml", new String[]{
                    "ai.language",
                    "ai.persona",
                    "ai.tone"
            },
            "ai_ban.yml", new String[]{
                    "ai_ban.default_reason"
            }
    );

    private SuspicionManager suspicionManager;
    private OpenAIService openAIService;
    private AIChat aiChat;
    private ServerScanner serverScanner;
    private AntiCheatConsoleListener antiCheatConsoleListener;
    private BotManager botManager;
    private boolean adminModeEnabled;
    private FileConfiguration optionConfig;
    private FileConfiguration pluginSettingsConfig;
    private FileConfiguration litebanConfig;
    private FileConfiguration ruleConfig;
    private FileConfiguration botRuleConfig;
    private DatabaseManager databaseManager;
    private LearningManager learningManager;
    private StatsManager statsManager;
    private SuspicionDashboard suspicionDashboard;
    private LagOptimizer lagOptimizer;
    private PluginRuntimeManager pluginRuntimeManager;
    private FileConfiguration mainConfig;
    private File languagePreferenceFile;
    private FileConfiguration languagePreferenceConfig;
    private volatile ConfigProfile activeConfigProfile;
    private final Map<UUID, ConfigProfile> playerLanguages = new ConcurrentHashMap<>();

    public static AIAdmin getInstance() {
        return instance;
    }

    public SuspicionManager getSuspicionManager() {
        return suspicionManager;
    }

    public AIChat getAiChat() {
        return aiChat;
    }

    public OpenAIService getOpenAIService() {
        return openAIService;
    }

    public ServerScanner getServerScanner() {
        return serverScanner;
    }

    public BotManager getBotManager() {
        return botManager;
    }

    public FileConfiguration getOptionConfig() {
        return optionConfig;
    }

    public FileConfiguration getPluginSettingsConfig() {
        return pluginSettingsConfig;
    }

    public FileConfiguration getLitebanConfig() {
        return litebanConfig;
    }

    public FileConfiguration getAiBanConfig() {
        return litebanConfig;
    }

    public FileConfiguration getRuleConfig() {
        return ruleConfig;
    }

    public FileConfiguration getBotRuleConfig() {
        return botRuleConfig;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public LearningManager getLearningManager() {
        return learningManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public SuspicionDashboard getSuspicionDashboard() {
        return suspicionDashboard;
    }

    public LagOptimizer getLagOptimizer() {
        return lagOptimizer;
    }

    public PluginRuntimeManager getPluginRuntimeManager() {
        return pluginRuntimeManager;
    }

    @Override
    public FileConfiguration getConfig() {
        return mainConfig != null ? mainConfig : super.getConfig();
    }

    @Override
    public void reloadConfig() {
        mainConfig = loadActiveConfiguration("config.yml", null);
    }

    public void reloadOptionConfig() {
        optionConfig = loadActiveConfiguration("option.yml", "option.yml");
        adminModeEnabled = optionConfig == null || optionConfig.getBoolean("admin_mode.relay_publicly", true);
    }

    public void reloadPluginSettingsConfig() {
        pluginSettingsConfig = loadSharedConfiguration("setting_plugin.yml");
    }

    public void reloadLitebanConfig() {
        litebanConfig = loadActiveConfiguration("ai_ban.yml", "liteban.yml");
    }

    public void reloadRuleConfig() {
        ruleConfig = loadActiveConfiguration("ai_knowledge.yml", "ai_knowledge.yml");
    }

    public void reloadBotRuleConfig() {
        botRuleConfig = loadActiveConfiguration("bot/bot_rule.yml", "bot_rule.yml");
    }

    public boolean isPluginIntegrationEnabled(String key) {
        if (pluginSettingsConfig == null || key == null || key.isBlank()) {
            return false;
        }
        if ("ai_ban".equalsIgnoreCase(key)) {
            if (pluginSettingsConfig.contains("plugins.ai_ban")) {
                return pluginSettingsConfig.getBoolean("plugins.ai_ban", false);
            }
            return pluginSettingsConfig.getBoolean("plugins.liteban", false);
        }
        return pluginSettingsConfig.getBoolean("plugins." + key, false);
    }

    public boolean isPluginIntegrationActive(String key, String pluginName) {
        return isPluginIntegrationEnabled(key) && getServer().getPluginManager().isPluginEnabled(pluginName);
    }

    public void reloadBotConfig() {
        if (botManager == null) {
            botManager = new BotManager(this);
            return;
        }
        botManager.reloadConfig();
    }

    public void reloadDatabaseConfig() {
        if (databaseManager == null) {
            databaseManager = new DatabaseManager(this);
        } else {
            databaseManager.reloadConfig();
        }
        databaseManager.initialize();
    }

    public void reloadLearningConfig() {
        if (learningManager == null) {
            learningManager = new LearningManager(this);
            return;
        }
        learningManager.reloadConfig();
    }

    public boolean isAdminModeEnabled() {
        return adminModeEnabled;
    }

    public void setAdminModeEnabled(boolean adminModeEnabled) {
        this.adminModeEnabled = adminModeEnabled;
    }

    public ConfigProfile getActiveConfigProfile() {
        if (activeConfigProfile == null) {
            activeConfigProfile = resolveActiveConfigProfile();
        }
        return activeConfigProfile;
    }

    public void refreshActiveConfigProfile() {
        activeConfigProfile = resolveActiveConfigProfile();
    }

    private ConfigProfile resolveActiveConfigProfile() {
        FileConfiguration englishConfig = loadLocaleConfiguration(ConfigProfile.ENGLISH, "config.yml", null);
        FileConfiguration vietnameseConfig = loadLocaleConfiguration(ConfigProfile.VIETNAMESE, "config.yml", null);

        boolean englishOn = englishConfig.getBoolean("use-config", false);
        boolean vietnameseOn = vietnameseConfig.getBoolean("use-config", false);

        if (englishOn == vietnameseOn) {
            englishConfig.set("use-config", false);
            vietnameseConfig.set("use-config", true);
            saveLocaleConfiguration(ConfigProfile.ENGLISH, "config.yml", englishConfig);
            saveLocaleConfiguration(ConfigProfile.VIETNAMESE, "config.yml", vietnameseConfig);
            return ConfigProfile.VIETNAMESE;
        }
        return englishOn ? ConfigProfile.ENGLISH : ConfigProfile.VIETNAMESE;
    }

    public boolean setActiveConfigProfile(ConfigProfile profile) {
        if (profile == null) {
            return false;
        }
        ConfigProfile currentProfile = getActiveConfigProfile();
        if (currentProfile != null && currentProfile != profile && !synchronizeProfiles(currentProfile, profile)) {
            return false;
        }
        FileConfiguration englishConfig = loadLocaleConfiguration(ConfigProfile.ENGLISH, "config.yml", null);
        FileConfiguration vietnameseConfig = loadLocaleConfiguration(ConfigProfile.VIETNAMESE, "config.yml", null);

        englishConfig.set("use-config", profile == ConfigProfile.ENGLISH);
        vietnameseConfig.set("use-config", profile == ConfigProfile.VIETNAMESE);

        boolean savedEnglish = saveLocaleConfiguration(ConfigProfile.ENGLISH, "config.yml", englishConfig);
        boolean savedVietnamese = saveLocaleConfiguration(ConfigProfile.VIETNAMESE, "config.yml", vietnameseConfig);
        if (savedEnglish && savedVietnamese) {
            activeConfigProfile = profile;
            return true;
        }
        return false;
    }

    private boolean synchronizeProfiles(ConfigProfile sourceProfile, ConfigProfile targetProfile) {
        boolean success = true;
        for (String relativePath : PROFILE_SHARED_RESOURCES) {
            success &= copyProfileConfiguration(sourceProfile, targetProfile, relativePath);
        }
        for (Map.Entry<String, String[]> entry : PROFILE_LOCALIZED_PATHS.entrySet()) {
            success &= copyProfileConfigurationPreservingLocalizedPaths(sourceProfile, targetProfile, entry.getKey(), entry.getValue());
        }
        return success;
    }

    private boolean copyProfileConfiguration(ConfigProfile sourceProfile, ConfigProfile targetProfile, String relativePath) {
        FileConfiguration source = loadLocaleConfiguration(sourceProfile, relativePath, relativePath);
        return saveLocaleConfiguration(targetProfile, relativePath, cloneConfiguration(source));
    }

    private boolean copyProfileConfigurationPreservingLocalizedPaths(ConfigProfile sourceProfile, ConfigProfile targetProfile, String relativePath, String[] localizedPaths) {
        FileConfiguration source = loadLocaleConfiguration(sourceProfile, relativePath, relativePath);
        FileConfiguration target = loadLocaleConfiguration(targetProfile, relativePath, relativePath);

        Map<String, Object> preserved = captureConfigurationValues(target, localizedPaths);
        YamlConfiguration merged = cloneConfiguration(source);
        applyConfigurationValues(merged, preserved);
        return saveLocaleConfiguration(targetProfile, relativePath, merged);
    }

    private YamlConfiguration cloneConfiguration(FileConfiguration source) {
        YamlConfiguration clone = new YamlConfiguration();
        if (source == null) {
            return clone;
        }
        for (Map.Entry<String, Object> entry : source.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                clone.set(entry.getKey(), entry.getValue());
            }
        }
        return clone;
    }

    private Map<String, Object> captureConfigurationValues(FileConfiguration configuration, String[] paths) {
        Map<String, Object> captured = new LinkedHashMap<>();
        if (configuration == null || paths == null) {
            return captured;
        }
        for (String path : paths) {
            if (path != null && !path.isBlank() && configuration.contains(path)) {
                captured.put(path, configuration.get(path));
            }
        }
        return captured;
    }

    private void applyConfigurationValues(FileConfiguration configuration, Map<String, Object> values) {
        if (configuration == null || values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            configuration.set(entry.getKey(), entry.getValue());
        }
    }

    public ConfigProfile getSenderLanguage(CommandSender sender) {
        if (sender instanceof Player) {
            return playerLanguages.getOrDefault(((Player) sender).getUniqueId(), getActiveConfigProfile());
        }
        return getActiveConfigProfile();
    }

    public boolean isEnglish(CommandSender sender) {
        return getSenderLanguage(sender) == ConfigProfile.ENGLISH;
    }

    public String tr(CommandSender sender, String vietnamese, String english) {
        return isEnglish(sender) ? english : vietnamese;
    }

    public String tr(ConfigProfile profile, String vietnamese, String english) {
        return profile == ConfigProfile.ENGLISH ? english : vietnamese;
    }

    public void setPlayerLanguage(Player player, ConfigProfile profile) {
        if (player == null || profile == null) {
            return;
        }
        playerLanguages.put(player.getUniqueId(), profile);
        if (languagePreferenceConfig == null) {
            reloadLanguagePreferences();
        }
        languagePreferenceConfig.set("players." + player.getUniqueId(), profile.name());
        saveLanguagePreferences();
    }

    public FileConfiguration loadLocaleConfiguration(ConfigProfile profile, String relativePath, String legacyName) {
        File file = ensureLocaleFile(profile, relativePath, legacyName);
        String resourcePath = profile.getFolderName() + "/" + relativePath.replace("\\", "/");
        FileConfiguration configuration = loadYamlConfigurationWithRecovery(profile, relativePath, file, resourcePath);
        InputStream defaultsStream = getResource(resourcePath);
        if (defaultsStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)
            );
            configuration.setDefaults(defaults);
        }
        return configuration;
    }

    private FileConfiguration loadYamlConfigurationWithRecovery(ConfigProfile profile, String relativePath, File file, String resourcePath) {
        maybeRestoreCorruptedLocaleFile(profile, relativePath, file, resourcePath);
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            if (file.exists()) {
                configuration.load(file);
            }
            return configuration;
        } catch (Exception ex) {
            getLogger().warning("Config lỗi tại " + profile.getFolderName() + "/" + relativePath + ": " + ex.getMessage());
            backupBrokenLocaleFile(file);
            restoreLocaleResource(file, resourcePath);

            YamlConfiguration recovered = new YamlConfiguration();
            try {
                if (file.exists()) {
                    recovered.load(file);
                }
            } catch (Exception retryEx) {
                getLogger().warning("Không thể nạp lại config để khôi phục " + profile.getFolderName() + "/" + relativePath + ": " + retryEx.getMessage());
            }
            return recovered;
        }
    }

    private void maybeRestoreCorruptedLocaleFile(ConfigProfile profile, String relativePath, File file, String resourcePath) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            String fileText = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (!looksLikeMojibake(fileText)) {
                return;
            }

            String resourceText = readInternalResource(resourcePath);
            if (resourceText == null || resourceText.isBlank() || looksLikeMojibake(resourceText)) {
                return;
            }

            getLogger().warning("Phát hiện lỗi mã hóa tại " + profile.getFolderName() + "/" + relativePath + ". Đang khôi phục bản chuẩn từ plugin.");
            backupBrokenLocaleFile(file);
            Files.writeString(file.toPath(), resourceText, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            getLogger().warning("Không thể kiểm tra lại mã hóa cho " + profile.getFolderName() + "/" + relativePath + ": " + ex.getMessage());
        }
    }

    private void backupBrokenLocaleFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        File backup = new File(parent, file.getName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            getLogger().warning("Không thể backup file lỗi " + file.getName() + ": " + ex.getMessage());
        }
    }

    private void restoreLocaleResource(File target, String resourcePath) {
        try {
            if (target == null) {
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            InputStream resource = getResource(resourcePath);
            if (resource == null) {
                getLogger().warning("Thiếu resource nội bộ để khôi phục: " + resourcePath);
                return;
            }
            try (InputStream input = resource) {
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            getLogger().warning("Không thể khôi phục resource " + resourcePath + ": " + ex.getMessage());
        }
    }

    private String readInternalResource(String resourcePath) {
        InputStream resource = getResource(resourcePath);
        if (resource == null) {
            return null;
        }
        try (InputStream input = resource) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            getLogger().warning("Không thể đọc resource nội bộ " + resourcePath + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean looksLikeMojibake(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.indexOf('\uFFFD') >= 0
                || text.indexOf('\u008F') >= 0
                || text.indexOf('\u00C3') >= 0
                || text.indexOf('\u00C6') >= 0
                || text.indexOf('\u00C4') >= 0
                || text.contains("\u00E1\u00BA")
                || text.contains("\u00E1\u00BB");
    }

    public FileConfiguration loadActiveConfiguration(String relativePath, String legacyName) {
        return loadLocaleConfiguration(getActiveConfigProfile(), relativePath, legacyName);
    }

    public File ensureActiveLocaleFile(String relativePath, String legacyName) {
        return ensureLocaleFile(getActiveConfigProfile(), relativePath, legacyName);
    }

    public boolean saveActiveConfiguration(String relativePath, FileConfiguration configuration) {
        return saveLocaleConfiguration(getActiveConfigProfile(), relativePath, configuration);
    }

    public String getActiveLocaleFolderName() {
        return getActiveConfigProfile().getFolderName();
    }

    @Override
    public void onEnable() {
        instance = this;
        ensureSharedResources();
        migrateLegacyAiKnowledgeFiles();
        migrateLegacyAiBanFiles();
        ensureLocaleResources();
        cleanupRemovedLocaleResources();
        reloadLanguagePreferences();
        refreshActiveConfigProfile();
        reloadConfig();
        reloadOptionConfig();
        reloadPluginSettingsConfig();
        reloadLitebanConfig();
        reloadRuleConfig();
        reloadBotRuleConfig();
        reloadDatabaseConfig();
        reloadLearningConfig();

        suspicionManager = new SuspicionManager(this);
        openAIService = new OpenAIService(this);
        aiChat = new AIChat(this, suspicionManager, openAIService);
        botManager = new BotManager(this);
        serverScanner = new ServerScanner(this, suspicionManager, aiChat);
        statsManager = new StatsManager(this);
        suspicionDashboard = new SuspicionDashboard(this, suspicionManager);
        lagOptimizer = new LagOptimizer(this, serverScanner, aiChat);
        pluginRuntimeManager = new PluginRuntimeManager(this);

        getServer().getPluginManager().registerEvents(new JoinListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new QuitListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this, aiChat), this);
        getServer().getPluginManager().registerEvents(new MovementListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new ClickListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new CombatListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new BlockPlaceListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(new PlayerInteractListener(suspicionManager), this);
        getServer().getPluginManager().registerEvents(suspicionDashboard, this);

        PluginCommand command = getCommand("aiadmin");
        if (command != null) {
            AdminCommand adminCommand = new AdminCommand(this, serverScanner, suspicionManager, aiChat);
            command.setExecutor(adminCommand);
            command.setTabCompleter(adminCommand);
        }

        antiCheatConsoleListener = new AntiCheatConsoleListener(this, suspicionManager);
        Logger logger = getServer().getLogger().getParent();
        if (logger == null) {
            logger = getServer().getLogger();
        }
        logger.addHandler(antiCheatConsoleListener);

        if (isPluginIntegrationActive("placeholder", "PlaceholderAPI")) {
            new AIAdminPlaceholderExpansion(this).register();
            getLogger().info("Đã đăng ký placeholder nội bộ của AIAdmin.");
        }

        serverScanner.startAutoScan();
        suspicionManager.startLearningTasks();
        lagOptimizer.start();
        if (openAIService.isEnabled()) {
            getLogger().info("Trả lời AI đã được bật.");
        } else {
            getLogger().info("Trả lời AI đang tắt. Hãy đặt " + openAIService.getConfiguredApiKeyEnv() + " hoặc openai.api_key để bật.");
        }
        logActiveConfigHint();
        logPluginIntegrationStatus();
        getLogger().info("AIAdmin đã khởi động cho nhánh 1.21.x.");
    }

    @Override
    public void onDisable() {
        Logger logger = getServer().getLogger().getParent();
        if (logger == null) {
            logger = getServer().getLogger();
        }
        if (antiCheatConsoleListener != null) {
            logger.removeHandler(antiCheatConsoleListener);
        }
        if (botManager != null) {
            botManager.shutdown();
        }
        if (serverScanner != null) {
            serverScanner.shutdown();
        }
        if (learningManager != null) {
            learningManager.shutdown();
        }
        if (suspicionManager != null) {
            suspicionManager.stopLearningTasks();
        }
        if (lagOptimizer != null) {
            lagOptimizer.stop();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private void logPluginIntegrationStatus() {
        if (pluginSettingsConfig != null && !pluginSettingsConfig.getBoolean("behavior.log_status_on_startup", true)) {
            return;
        }

        boolean aiBan = isPluginIntegrationEnabled("ai_ban");
        boolean tab = isPluginIntegrationEnabled("tab");
        boolean placeholder = isPluginIntegrationEnabled("placeholder");
        boolean citizens = isPluginIntegrationEnabled("citizens");
        boolean znpcs = isPluginIntegrationEnabled("znpcs");
        boolean velocity = isPluginIntegrationEnabled("velocity");

        getLogger().info("Plugin hooks => ai_ban=" + aiBan + ", tab=" + tab + ", placeholder=" + placeholder + ", citizens=" + citizens + ", znpcs=" + znpcs + ", velocity=" + velocity);
        if (aiBan && !getServer().getPluginManager().isPluginEnabled("LiteBans")) {
            getLogger().warning("AI_ban external mode is enabled but LiteBans is missing. Falling back to the internal ban flow.");
        }
        if (tab && !getServer().getPluginManager().isPluginEnabled("TAB")) {
            getLogger().warning("TAB integration is enabled but TAB is missing.");
        }
        if (placeholder && !getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().warning("PlaceholderAPI integration is enabled but PlaceholderAPI is missing.");
        }
        if (znpcs
                && !getServer().getPluginManager().isPluginEnabled("ZNPCs")
                && !getServer().getPluginManager().isPluginEnabled("ZNPCsPlus")) {
            getLogger().warning("ZNPC integration is enabled but ZNPCs/ZNPCsPlus is missing. Bot logic will fall back to mannequin mode.");
        }
        if (velocity) {
            getLogger().info("Velocity compatibility mode is enabled. Make sure Paper/Velocity forwarding is configured so player.getAddress() returns the real client IP.");
        }
    }

    private void logActiveConfigHint() {
        ConfigProfile profile = getActiveConfigProfile();
        if (profile == null) {
            return;
        }
        getLogger().info("Cấu hình đang dùng: " + profile.getFolderName() + "/config.yml");
        getLogger().info("Thiết lập plugin dùng chung: setting_plugin.yml");
    }

    public void reloadAllRuntimeConfigs() {
        refreshActiveConfigProfile();
        reloadConfig();
        reloadOptionConfig();
        reloadPluginSettingsConfig();
        reloadLitebanConfig();
        reloadRuleConfig();
        reloadBotRuleConfig();
        reloadBotConfig();
        reloadDatabaseConfig();
        reloadLearningConfig();
        if (statsManager != null) {
            statsManager.reload();
        }
        if (serverScanner != null) {
            serverScanner.reloadRuntimeConfig();
        }
        if (openAIService != null) {
            openAIService.reloadClient();
        }
        if (aiChat != null) {
            aiChat.reloadCustomChatConfig();
        }
        if (suspicionManager != null) {
            suspicionManager.startLearningTasks();
        }
        if (lagOptimizer != null) {
            lagOptimizer.start();
        }
        if (pluginRuntimeManager != null) {
            pluginRuntimeManager.reload();
        }
    }

    private void ensureLocaleResources() {
        for (ConfigProfile profile : ConfigProfile.values()) {
            for (String resource : LOCALIZED_RESOURCES) {
                String localizedResource = profile.getFolderName() + "/" + resource;
                if (getResource(localizedResource) != null) {
                    saveResource(localizedResource, false);
                } else {
                    getLogger().warning("Thiếu resource nội bộ: " + localizedResource);
                }
            }
        }
    }

    private void ensureSharedResources() {
        for (String resource : SHARED_RESOURCES) {
            ensureSharedFile(resource);
        }
    }

    private void cleanupRemovedLocaleResources() {
        for (ConfigProfile profile : ConfigProfile.values()) {
            deleteLegacyLocaleFile(profile, "guide.yml");
            deleteLegacyAiSplitFiles(profile);
            deleteLegacyLocaleFile(profile, "liteban.yml");
        }
    }

    private void migrateLegacyAiKnowledgeFiles() {
        for (ConfigProfile profile : ConfigProfile.values()) {
            File mergedFile = new File(getDataFolder(), profile.getFolderName() + File.separator + "ai_knowledge.yml");
            if (!mergedFile.exists()) {
                migrateLegacyAiKnowledge(profile, mergedFile);
            }
        }
    }

    private void migrateLegacyAiBanFiles() {
        for (ConfigProfile profile : ConfigProfile.values()) {
            File currentFile = new File(getDataFolder(), profile.getFolderName() + File.separator + "ai_ban.yml");
            if (currentFile.exists()) {
                continue;
            }
            File legacyFile = new File(getDataFolder(), profile.getFolderName() + File.separator + "liteban.yml");
            if (!legacyFile.exists()) {
                continue;
            }
            try {
                File parent = currentFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.copy(legacyFile.toPath(), currentFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                getLogger().warning("Không thể migrate liteban.yml sang ai_ban.yml cho " + profile.getFolderName() + ": " + ex.getMessage());
            }
        }
    }

    private void deleteLegacyAiSplitFiles(ConfigProfile profile) {
        File mergedFile = new File(getDataFolder(), profile.getFolderName() + File.separator + "ai_knowledge.yml");
        if (!mergedFile.exists()) {
            return;
        }
        deleteLegacyLocaleFile(profile, "aichat.yml");
        deleteLegacyLocaleFile(profile, "knowledge.yml");
        deleteLegacyLocaleFile(profile, "rule.yml");
    }

    private void deleteLegacyLocaleFile(ConfigProfile profile, String relativePath) {
        File legacyFile = new File(getDataFolder(), profile.getFolderName() + File.separator + relativePath.replace("/", File.separator));
        if (!legacyFile.exists()) {
            return;
        }
        try {
            Files.deleteIfExists(legacyFile.toPath());
        } catch (Exception ex) {
            getLogger().warning("Không thể xóa file cũ " + profile.getFolderName() + "/" + relativePath + ": " + ex.getMessage());
        }
    }

    private File ensureLocaleFile(ConfigProfile profile, String relativePath, String legacyName) {
        String safeRelative = relativePath.replace("\\", "/");
        File file = new File(getDataFolder(), profile.getFolderName() + File.separator + safeRelative.replace("/", File.separator));
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (!file.exists()) {
            boolean migrated = false;
            if ("ai_knowledge.yml".equals(safeRelative)) {
                migrated = migrateLegacyAiKnowledge(profile, file);
            }
            if (legacyName != null && !legacyName.isBlank()) {
                File localizedLegacy = new File(getDataFolder(), profile.getFolderName() + File.separator + legacyName.replace("/", File.separator));
                if (localizedLegacy.exists()) {
                    try {
                        Files.copy(localizedLegacy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        migrated = true;
                    } catch (Exception ex) {
                        getLogger().warning("Không thể migrate " + profile.getFolderName() + "/" + legacyName + " sang " + profile.getFolderName() + "/" + safeRelative + ": " + ex.getMessage());
                    }
                }
            }
            if (!migrated && profile == ConfigProfile.VIETNAMESE && legacyName != null && !legacyName.isBlank()) {
                File legacy = new File(getDataFolder(), legacyName);
                if (legacy.exists()) {
                    try {
                        Files.copy(legacy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        migrated = true;
                    } catch (Exception ex) {
                        getLogger().warning("Không thể migrate " + legacyName + " sang " + profile.getFolderName() + "/" + safeRelative + ": " + ex.getMessage());
                    }
                }
            }
            if (!migrated) {
                String resourcePath = profile.getFolderName() + "/" + safeRelative;
                if (getResource(resourcePath) != null) {
                    saveResource(resourcePath, false);
                } else {
                    getLogger().warning("Thiếu resource nội bộ: " + resourcePath);
                    ConfigProfile fallbackProfile = profile == ConfigProfile.ENGLISH
                            ? ConfigProfile.VIETNAMESE
                            : ConfigProfile.ENGLISH;
                    String fallbackResourcePath = fallbackProfile.getFolderName() + "/" + safeRelative;
                    File fallbackFile = new File(getDataFolder(), fallbackProfile.getFolderName() + File.separator + safeRelative.replace("/", File.separator));
                    try {
                        if (fallbackFile.exists()) {
                            Files.copy(fallbackFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else if (getResource(fallbackResourcePath) != null) {
                            restoreLocaleResource(file, fallbackResourcePath);
                        } else {
                            Files.writeString(file.toPath(), "", StandardCharsets.UTF_8);
                        }
                    } catch (Exception ex) {
                        getLogger().warning("Không thể tạo fallback cho " + profile.getFolderName() + "/" + safeRelative + ": " + ex.getMessage());
                    }
                }
            }
        }
        return file;
    }

    private boolean migrateLegacyAiKnowledge(ConfigProfile profile, File targetFile) {
        try {
            File localeFolder = new File(getDataFolder(), profile.getFolderName());
            File legacyAiChat = new File(localeFolder, "aichat.yml");
            File legacyKnowledge = new File(localeFolder, "knowledge.yml");
            File legacyRule = new File(localeFolder, "rule.yml");
            if (!legacyAiChat.exists() && !legacyKnowledge.exists() && !legacyRule.exists()) {
                return false;
            }

            YamlConfiguration merged = new YamlConfiguration();
            if (legacyAiChat.exists()) {
                YamlConfiguration aiChatConfig = YamlConfiguration.loadConfiguration(legacyAiChat);
                Object aichatSection = aiChatConfig.get("aichat");
                if (aichatSection != null) {
                    merged.set("aichat", aichatSection);
                }
            }
            if (legacyKnowledge.exists()) {
                YamlConfiguration knowledgeConfig = YamlConfiguration.loadConfiguration(legacyKnowledge);
                Object knowledgeSection = knowledgeConfig.get("knowledge");
                if (knowledgeSection != null) {
                    merged.set("knowledge", knowledgeSection);
                }
            }
            if (legacyRule.exists()) {
                YamlConfiguration ruleFile = YamlConfiguration.loadConfiguration(legacyRule);
                Object ruleSection = ruleFile.get("rule");
                if (ruleSection != null) {
                    merged.set("rule", ruleSection);
                }
            }

            if (merged.getKeys(false).isEmpty()) {
                return false;
            }

            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            merged.save(targetFile);
            getLogger().info("Đã gộp aichat.yml, knowledge.yml và rule.yml vào " + profile.getFolderName() + "/ai_knowledge.yml");
            return true;
        } catch (Exception ex) {
            getLogger().warning("Không thể gộp config AI cũ cho " + profile.getFolderName() + ": " + ex.getMessage());
            return false;
        }
    }

    private File ensureSharedFile(String relativePath) {
        String safeRelative = relativePath.replace("\\", "/");
        File file = new File(getDataFolder(), safeRelative.replace("/", File.separator));
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (!file.exists() && getResource(safeRelative) != null) {
            saveResource(safeRelative, false);
        }
        return file;
    }

    private FileConfiguration loadSharedConfiguration(String relativePath) {
        File file = ensureSharedFile(relativePath);
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            if (file.exists()) {
                configuration.load(file);
            }
        } catch (Exception ex) {
            getLogger().warning("Không thể nạp shared config " + relativePath + ": " + ex.getMessage());
        }

        InputStream defaultsStream = getResource(relativePath);
        if (defaultsStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)
            );
            configuration.setDefaults(defaults);
        }
        return configuration;
    }

    private boolean saveLocaleConfiguration(ConfigProfile profile, String relativePath, FileConfiguration configuration) {
        try {
            File file = ensureLocaleFile(profile, relativePath, null);
            configuration.save(file);
            return true;
        } catch (Exception ex) {
            getLogger().warning("Không thể lưu " + profile.getFolderName() + "/" + relativePath + ": " + ex.getMessage());
            return false;
        }
    }

    private void reloadLanguagePreferences() {
        languagePreferenceFile = new File(getDataFolder(), "player_language.yml");
        languagePreferenceConfig = YamlConfiguration.loadConfiguration(languagePreferenceFile);
        playerLanguages.clear();
        if (languagePreferenceConfig.getConfigurationSection("players") == null) {
            return;
        }
        for (String key : languagePreferenceConfig.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID uniqueId = UUID.fromString(key);
                ConfigProfile profile = ConfigProfile.fromInput(languagePreferenceConfig.getString("players." + key, ""));
                if (profile != null) {
                    playerLanguages.put(uniqueId, profile);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveLanguagePreferences() {
        try {
            if (languagePreferenceFile == null) {
                languagePreferenceFile = new File(getDataFolder(), "player_language.yml");
            }
            if (languagePreferenceConfig == null) {
                languagePreferenceConfig = new YamlConfiguration();
            }
            languagePreferenceConfig.save(languagePreferenceFile);
        } catch (Exception ex) {
            getLogger().warning("Không thể lưu player_language.yml: " + ex.getMessage());
        }
    }
}
