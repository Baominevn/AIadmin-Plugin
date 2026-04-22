package me.aiadmin.system;

import me.aiadmin.AIAdmin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {

    private final AIAdmin plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AIAdmin-Database");
        thread.setDaemon(true);
        return thread;
    });

    private FileConfiguration databaseConfig;
    private volatile boolean enabled;
    private volatile boolean writeAsync;
    private volatile String jdbcUrl;
    private volatile String username;
    private volatile String password;
    private volatile String tablePrefix;
    private volatile boolean autoCreateTables;

    private final Object analyticsLock = new Object();
    private final Set<String> analyticsPlayers = new HashSet<>();
    private final Map<String, Integer> analyticsChecks = new HashMap<>();
    private final Map<String, Integer> analyticsSources = new HashMap<>();
    private volatile int analyticsAlertCount;

    public DatabaseManager(AIAdmin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        databaseConfig = plugin.loadActiveConfiguration("database.yml", "database.yml");

        enabled = databaseConfig.getBoolean("database.enabled", false);
        writeAsync = databaseConfig.getBoolean("database.write_async", true);
        autoCreateTables = databaseConfig.getBoolean("database.auto_create_tables", true);

        String host = databaseConfig.getString("database.host", "127.0.0.1");
        int port = databaseConfig.getInt("database.port", 3306);
        String databaseName = databaseConfig.getString("database.name", "aiadmin");
        username = databaseConfig.getString("database.username", "root");
        password = databaseConfig.getString("database.password", "");
        tablePrefix = databaseConfig.getString("database.table_prefix", "aiadmin_");
        String params = databaseConfig.getString(
                "database.parameters",
                "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC"
        );

        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + databaseName + "?" + params;
    }

    public void initialize() {
        if (!enabled) {
            plugin.getLogger().info("MySQL connection is disabled.");
            clearAnalyticsCache();
            return;
        }

        submit(() -> {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                testConnection();
                if (autoCreateTables) {
                    createTables();
                }
                loadAnalyticsSnapshot();
                plugin.getLogger().info("MySQL connected successfully.");
            } catch (Exception ex) {
                plugin.getLogger().warning("MySQL initialization failed: " + ex.getMessage());
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getStatusSummary() {
        return "enabled=" + enabled + ", async=" + writeAsync + ", url=" + jdbcUrl;
    }

    public String getAnalyticsSummary() {
        synchronized (analyticsLock) {
            return "alerts=" + analyticsAlertCount
                    + ", players=" + analyticsPlayers.size()
                    + ", top_check=" + topKey(analyticsChecks)
                    + ", top_source=" + topKey(analyticsSources);
        }
    }

    public void logBanEvent(String player, String reason, String duration, String method, boolean blockedOp, String actor) {
        if (!enabled) {
            return;
        }
        submit(() -> {
            String sql = "INSERT INTO " + table("ban_logs")
                    + " (player_name, reason_text, duration_text, method_name, blocked_op, actor_name) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player);
                statement.setString(2, truncate(reason, 500));
                statement.setString(3, duration);
                statement.setString(4, method);
                statement.setBoolean(5, blockedOp);
                statement.setString(6, actor);
                statement.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().warning("MySQL ban log failed: " + ex.getMessage());
            }
        });
    }

    public void logBotEvent(String botName, String target, String reason, String eventType, String locationText) {
        if (!enabled) {
            return;
        }
        submit(() -> {
            String sql = "INSERT INTO " + table("bot_events")
                    + " (bot_name, target_name, reason_text, event_type, location_text) VALUES (?, ?, ?, ?, ?)";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, botName);
                statement.setString(2, target);
                statement.setString(3, truncate(reason, 500));
                statement.setString(4, eventType);
                statement.setString(5, truncate(locationText, 150));
                statement.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().warning("MySQL bot log failed: " + ex.getMessage());
            }
        });
    }

    public void logChatEvent(String player, String prompt, String reply, boolean customReply, boolean adminMode) {
        if (!enabled) {
            return;
        }
        submit(() -> {
            String sql = "INSERT INTO " + table("chat_logs")
                    + " (player_name, prompt_text, reply_text, custom_reply, admin_mode) VALUES (?, ?, ?, ?, ?)";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player);
                statement.setString(2, truncate(prompt, 1500));
                statement.setString(3, truncate(reply, 1500));
                statement.setBoolean(4, customReply);
                statement.setBoolean(5, adminMode);
                statement.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().warning("MySQL chat log failed: " + ex.getMessage());
            }
        });
    }

    public void logAlertEvent(String player, String source, String checkType, int points, String severity, String detail) {
        if (!enabled) {
            return;
        }
        submit(() -> {
            String sql = "INSERT INTO " + table("alert_events")
                    + " (player_name, source_name, check_type, points_value, severity_label, detail_text) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, truncate(player, 32));
                statement.setString(2, truncate(source, 64));
                statement.setString(3, truncate(checkType, 64));
                statement.setInt(4, points);
                statement.setString(5, truncate(severity, 32));
                statement.setString(6, truncate(detail, 1500));
                statement.executeUpdate();
                updateAnalyticsCache(player, source, checkType);
            } catch (SQLException ex) {
                plugin.getLogger().warning("MySQL alert log failed: " + ex.getMessage());
            }
        });
    }

    public void upsertLearningEntry(String fingerprint, String samplePrompt, String sampleReply, int uses, int score) {
        if (!enabled) {
            return;
        }
        submit(() -> {
            String sql = "INSERT INTO " + table("learning_entries")
                    + " (fingerprint, sample_prompt, sample_reply, uses_count, score_value) VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE sample_prompt=VALUES(sample_prompt), sample_reply=VALUES(sample_reply), "
                    + "uses_count=VALUES(uses_count), score_value=VALUES(score_value), updated_at=CURRENT_TIMESTAMP";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, truncate(fingerprint, 180));
                statement.setString(2, truncate(samplePrompt, 1500));
                statement.setString(3, truncate(sampleReply, 1500));
                statement.setInt(4, uses);
                statement.setInt(5, score);
                statement.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().warning("MySQL learning upsert failed: " + ex.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void testConnection() throws SQLException {
        try (Connection ignored = openConnection()) {
            // ok
        }
    }

    private void createTables() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("ban_logs") + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "player_name VARCHAR(32) NOT NULL,"
                    + "reason_text VARCHAR(500) NOT NULL,"
                    + "duration_text VARCHAR(32) NOT NULL,"
                    + "method_name VARCHAR(32) NOT NULL,"
                    + "blocked_op BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "actor_name VARCHAR(64) NOT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS " + table("bot_events") + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "bot_name VARCHAR(64) NOT NULL,"
                    + "target_name VARCHAR(32) NULL,"
                    + "reason_text VARCHAR(500) NULL,"
                    + "event_type VARCHAR(64) NOT NULL,"
                    + "location_text VARCHAR(150) NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS " + table("chat_logs") + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "player_name VARCHAR(32) NOT NULL,"
                    + "prompt_text TEXT NOT NULL,"
                    + "reply_text TEXT NOT NULL,"
                    + "custom_reply BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "admin_mode BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS " + table("learning_entries") + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "fingerprint VARCHAR(180) NOT NULL UNIQUE,"
                    + "sample_prompt TEXT NOT NULL,"
                    + "sample_reply TEXT NOT NULL,"
                    + "uses_count INT NOT NULL DEFAULT 0,"
                    + "score_value INT NOT NULL DEFAULT 0,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS " + table("alert_events") + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "player_name VARCHAR(32) NOT NULL,"
                    + "source_name VARCHAR(64) NOT NULL,"
                    + "check_type VARCHAR(64) NOT NULL,"
                    + "points_value INT NOT NULL DEFAULT 0,"
                    + "severity_label VARCHAR(32) NOT NULL,"
                    + "detail_text TEXT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")");
        }
    }

    private void submit(Runnable task) {
        if (!enabled) {
            return;
        }
        if (writeAsync) {
            executor.submit(task);
        } else {
            task.run();
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void loadAnalyticsSnapshot() throws SQLException {
        clearAnalyticsCache();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT player_name, source_name, check_type FROM " + table("alert_events"))) {
            while (rs.next()) {
                updateAnalyticsCache(
                        rs.getString("player_name"),
                        rs.getString("source_name"),
                        rs.getString("check_type")
                );
            }
        }
    }

    private void clearAnalyticsCache() {
        synchronized (analyticsLock) {
            analyticsPlayers.clear();
            analyticsChecks.clear();
            analyticsSources.clear();
            analyticsAlertCount = 0;
        }
    }

    private void updateAnalyticsCache(String player, String source, String checkType) {
        synchronized (analyticsLock) {
            analyticsAlertCount++;
            if (player != null && !player.isBlank()) {
                analyticsPlayers.add(player.toLowerCase(Locale.ROOT));
            }
            if (checkType != null && !checkType.isBlank()) {
                analyticsChecks.merge(checkType, 1, Integer::sum);
            }
            if (source != null && !source.isBlank()) {
                analyticsSources.merge(source, 1, Integer::sum);
            }
        }
    }

    private String topKey(Map<String, Integer> counts) {
        String bestKey = "none";
        int bestValue = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestValue) {
                bestValue = entry.getValue();
                bestKey = entry.getKey();
            }
        }
        return bestKey;
    }

    private String table(String name) {
        return tablePrefix + name;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
