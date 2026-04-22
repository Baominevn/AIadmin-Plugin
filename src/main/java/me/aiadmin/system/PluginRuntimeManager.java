package me.aiadmin.system;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.aiadmin.AIAdmin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PluginRuntimeManager {

    private static final String MODRINTH_API_BASE = "https://api.modrinth.com/v2";
    private static final int MAX_CHAT_MESSAGE_LENGTH = 240;
    private static final Gson GSON = new Gson();
    private static final Set<String> SAFE_HOTLOAD_KEYS = Set.of("anti-dupe", "ironwatch");
    private static final Map<String, CatalogEntry> BUILT_IN_LOOKUP = createBuiltInLookup();
    private static final Set<String> BUILT_IN_SUGGESTIONS = createBuiltInSuggestions();

    private final AIAdmin plugin;
    private HttpClient httpClient;

    public PluginRuntimeManager(AIAdmin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        long timeout = Math.max(10L, getTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isEnabled() {
        return plugin.getPluginSettingsConfig() == null
                || plugin.getPluginSettingsConfig().getBoolean("plugin_manager.enabled", true);
    }

    public List<String> describePlugins(boolean english) {
        List<String> lines = new ArrayList<>();
        lines.add(color(t(english, "&6[Plugin] Installed plugins:", "&6[Plugin] Danh sách plugin đã cài:")));

        File pluginsFolder = getPluginsFolder();
        List<File> jarFiles = getPluginJarFiles(pluginsFolder);
        if (jarFiles.isEmpty() && Bukkit.getPluginManager().getPlugins().length == 0) {
            lines.add(color(t(english, "&7No plugins found.", "&7Chưa tìm thấy plugin nào.")));
            return lines;
        }

        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            File file = resolvePluginFile(loaded);
            String version = loaded.getDescription() == null ? "unknown" : loaded.getDescription().getVersion();
            String fileName = file == null ? "unknown" : file.getName();
            lines.add(color("&a- &f" + sanitizeDisplayFragment(loaded.getName(), "UnknownPlugin")
                    + " &7v" + sanitizeDisplayFragment(version, "unknown")
                    + " &8[" + sanitizeDisplayFragment(fileName, "unknown.jar") + "]"));
        }

        for (File jarFile : jarFiles) {
            if (isLoadedPluginJar(jarFile)) {
                continue;
            }
            PluginJarInfo info = inspectPluginJar(jarFile);
            if (info == null) {
                lines.add(color("&8- &f" + sanitizeDisplayFragment(jarFile.getName(), "unknown.jar")
                        + " &8(" + t(english, "not a valid Bukkit/Paper plugin", "không phải plugin Bukkit/Paper hợp lệ") + ")"));
                continue;
            }
            String display = info.version.isBlank()
                    ? info.name + " [" + jarFile.getName() + "]"
                    : info.name + " v" + info.version + " [" + jarFile.getName() + "]";
            lines.add(color("&e- &f" + sanitizeDisplayFragment(display, jarFile.getName())
                    + " &8(" + t(english, "on disk only", "chỉ có trên ổ đĩa") + ")"));
        }
        return lines;
    }

    public List<String> getCatalogKeys() {
        TreeSet<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (isBuiltInCatalogEnabled()) {
            keys.addAll(BUILT_IN_SUGGESTIONS);
        }

        ConfigurationSection catalog = getCatalogSection();
        if (catalog != null) {
            keys.addAll(catalog.getKeys(false));
        }
        return new ArrayList<>(keys);
    }

    public void downloadPlugin(CommandSender sender, String spec) {
        boolean english = plugin.isEnglish(sender);
        if (!isEnabled()) {
            sender.sendMessage(color(t(english,
                    "&cPlugin manager is disabled in setting_plugin.yml.",
                    "&cPlugin manager đang bị tắt trong setting_plugin.yml.")));
            return;
        }
        if (spec == null || spec.isBlank()) {
            sender.sendMessage(color(t(english,
                    "&cUsage: /ai plugin download <plugin>",
                    "&cCách dùng: /ai plugin download <plugin>")));
            return;
        }

        String trimmedSpec = spec.trim();
        sender.sendMessage(color(t(english,
                "&e[Plugin] Preparing download for &f" + trimmedSpec + "&e...",
                "&e[Plugin] Đang chuẩn bị tải &f" + trimmedSpec + "&e...")));

        CompletableFuture.runAsync(() -> {
            try {
                ResolvedDownload resolved = resolveDownload(trimmedSpec, english);
                File pluginsFolder = getPluginsFolder();
                if (!pluginsFolder.exists() && !pluginsFolder.mkdirs()) {
                    throw new IOException("Cannot create plugins folder: " + pluginsFolder.getAbsolutePath());
                }

                File targetFile = new File(pluginsFolder, sanitizeFileName(resolved.fileName));
                Plugin loadedByDisplayName = Bukkit.getPluginManager().getPlugin(resolved.displayName);
                if (loadedByDisplayName != null) {
                    File loadedFile = resolvePluginFile(loadedByDisplayName);
                    if (loadedFile == null || !loadedFile.equals(targetFile)) {
                        sendLater(sender, t(english,
                                "&c" + resolved.displayName + " is already installed with another jar file. Replace it manually and restart the server.",
                                "&c" + resolved.displayName + " đã được cài bằng một file jar khác. Hãy thay thủ công rồi khởi động lại server."));
                        return;
                    }
                }
                if (targetFile.exists() && isLoadedPluginJar(targetFile)) {
                    sendLater(sender, t(english,
                            "&c" + resolved.displayName + " is already loaded. Replace it manually and restart the server.",
                            "&c" + resolved.displayName + " đang chạy rồi. Hãy thay jar thủ công rồi khởi động lại server."));
                    return;
                }

                HttpRequest request = HttpRequest.newBuilder(URI.create(resolved.url))
                        .timeout(Duration.ofSeconds(Math.max(10L, getTimeoutSeconds())))
                        .header("User-Agent", "AIAdmin/1.0.1")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new KnownDownloadProblem(t(english,
                            "Download failed with HTTP " + response.statusCode() + ".",
                            "Tải plugin thất bại với HTTP " + response.statusCode() + "."));
                }

                File tempFile = new File(pluginsFolder, targetFile.getName() + ".part");
                try (InputStream body = response.body()) {
                    Files.copy(body, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                PluginJarInfo downloadedInfo = inspectPluginJar(tempFile);
                if (downloadedInfo == null) {
                    Files.deleteIfExists(tempFile.toPath());
                    throw new KnownDownloadProblem(t(english,
                            "The downloaded file is not a valid Bukkit/Paper plugin jar.",
                            "File tải về không phải plugin Bukkit/Paper hợp lệ."));
                }
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                sendLater(sender, t(english,
                        "&a[Plugin] Downloaded &f" + resolved.displayName + "&a -> &f" + targetFile.getName(),
                        "&a[Plugin] Đã tải &f" + resolved.displayName + "&a -> &f" + targetFile.getName()));
                if (!resolved.versionSummary.isBlank()) {
                    sendLater(sender, "&7" + resolved.versionSummary);
                }
                if (!resolved.compatibilitySummary.isBlank()) {
                    sendLater(sender, "&7" + resolved.compatibilitySummary);
                }

                boolean autoEnable = plugin.getPluginSettingsConfig() != null
                        && plugin.getPluginSettingsConfig().getBoolean("plugin_manager.auto_enable_after_download", false);
                if (!autoEnable) {
                    sendLater(sender, t(english,
                            "&e[Plugin] Auto-enable is off. Restart the server to enable this plugin safely.",
                            "&e[Plugin] Chế độ tự bật plugin đang tắt. Hãy khởi động lại server để bật plugin an toàn."));
                    return;
                }

                if (!resolved.hotLoadSupported) {
                    sendLater(sender, t(english,
                            "&e[Plugin] This plugin is marked as restart-required. It was downloaded only, not hot-loaded.",
                            "&e[Plugin] Plugin này cần khởi động lại server. AIAdmin chỉ tải về, không bật nóng."));
                    return;
                }

                CompletableFuture<String> loadFuture = new CompletableFuture<>();
                Bukkit.getScheduler().runTask(plugin, () -> loadFuture.complete(tryLoadPlugin(targetFile, english)));
                sendLater(sender, loadFuture.join());
            } catch (KnownDownloadProblem problem) {
                sendLater(sender, "&c" + problem.getMessage());
            } catch (Exception exception) {
                String message = shortenForChat(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                sendLater(sender, t(english,
                        "&cFailed to download plugin: " + message,
                        "&cKhông thể tải plugin: " + message));
                plugin.getLogger().warning("Plugin download failed for '" + trimmedSpec + "': " + message);
            }
        });
    }

    public String removePlugin(CommandSender sender, String pluginNameOrFile) {
        boolean english = plugin.isEnglish(sender);
        if (!isEnabled()) {
            return t(english,
                    "&cPlugin manager is disabled in setting_plugin.yml.",
                    "&cPlugin manager đang bị tắt trong setting_plugin.yml.");
        }
        if (pluginNameOrFile == null || pluginNameOrFile.isBlank()) {
            return t(english,
                    "&cUsage: /ai plugin remove <plugin>",
                    "&cCách dùng: /ai plugin remove <plugin>");
        }

        Plugin loaded = Bukkit.getPluginManager().getPlugin(pluginNameOrFile);
        if (loaded != null && loaded.getName().equalsIgnoreCase(plugin.getName())) {
            return t(english,
                    "&cYou cannot remove AIAdmin while it is running.",
                    "&cBạn không thể gỡ AIAdmin khi plugin đang chạy.");
        }

        File jarFile = loaded != null ? resolvePluginFile(loaded) : resolvePluginJar(pluginNameOrFile);
        if (loaded == null && jarFile == null) {
            return t(english,
                    "&cCannot find that plugin in the loaded list or plugins folder.",
                    "&cKhông tìm thấy plugin đó trong danh sách đã load hoặc thư mục plugins.");
        }

        if (loaded != null) {
            try {
                Bukkit.getPluginManager().disablePlugin(loaded);
            } catch (Exception exception) {
                return t(english,
                        "&cCould not disable " + loaded.getName() + ": " + exception.getMessage(),
                        "&cKhông thể tắt " + loaded.getName() + ": " + exception.getMessage());
            }
            if (jarFile == null) {
                jarFile = resolvePluginFile(loaded);
            }
        }

        if (jarFile == null || !jarFile.exists()) {
            return t(english,
                    "&aPlugin disabled. No jar file was found to delete.",
                    "&aĐã tắt plugin. Không tìm thấy file jar để xóa.");
        }

        try {
            Files.delete(jarFile.toPath());
        } catch (IOException exception) {
            return t(english,
                    "&ePlugin file is locked. Delete " + jarFile.getName() + " manually after restart.",
                    "&eFile plugin đang bị khóa. Hãy xóa " + jarFile.getName() + " thủ công sau khi restart.");
        }

        return t(english,
                "&aRemoved plugin file: &f" + jarFile.getName(),
                "&aĐã gỡ file plugin: &f" + jarFile.getName());
    }

    private ResolvedDownload resolveDownload(String spec, boolean english) throws Exception {
        if (spec == null || spec.isBlank()) {
            throw new KnownDownloadProblem(t(english,
                    "Plugin name is blank.",
                    "Tên plugin đang trống."));
        }

        String trimmed = spec.trim();
        if (isDirectUrl(trimmed)) {
            if (plugin.getPluginSettingsConfig() == null
                    || !plugin.getPluginSettingsConfig().getBoolean("plugin_manager.allow_direct_urls", false)) {
                throw new KnownDownloadProblem(t(english,
                        "Direct URLs are disabled in setting_plugin.yml.",
                        "Liên kết trực tiếp đang bị tắt trong setting_plugin.yml."));
            }
            String fileName = extractFileNameFromUrl(trimmed);
            return new ResolvedDownload(
                    trimmed,
                    fileName,
                    fileName,
                    sanitizeCatalogKey(fileName),
                    false,
                    t(english, "Source: direct URL", "Nguồn: liên kết trực tiếp"),
                    t(english, "Compatibility must be checked manually.", "Bạn cần tự kiểm tra độ tương thích.")
            );
        }

        String normalized = sanitizeCatalogKey(trimmed);
        if (isBuiltInCatalogEnabled()) {
            CatalogEntry builtIn = BUILT_IN_LOOKUP.get(normalized);
            if (builtIn != null) {
                return resolveCatalogEntry(builtIn, english);
            }
        }

        ConfigurationSection catalog = getCatalogSection();
        if (catalog != null) {
            String actualKey = findCatalogKey(catalog, normalized);
            if (actualKey != null) {
                return resolveCatalogEntry(readCatalogEntry(actualKey, catalog.getConfigurationSection(actualKey)), english);
            }
        }

        throw new KnownDownloadProblem(t(english,
                "Unknown plugin preset. Available presets: " + String.join(", ", getCatalogKeys()),
                "Không có preset plugin này. Preset hiện có: " + String.join(", ", getCatalogKeys())));
    }

    private ResolvedDownload resolveCatalogEntry(CatalogEntry entry, boolean english) throws Exception {
        if (entry.manualOnly) {
            throw new KnownDownloadProblem(t(english,
                    entry.displayName + " must be downloaded manually from the official source: " + entry.officialUrl,
                    entry.displayName + " phải được tải thủ công từ nguồn chính chủ: " + entry.officialUrl));
        }

        if (entry.provider == CatalogProvider.DIRECT) {
            if (entry.downloadUrl.isBlank()) {
                throw new KnownDownloadProblem(t(english,
                        "Catalog entry '" + entry.key + "' is missing a download URL.",
                        "Entry '" + entry.key + "' trong catalog đang thiếu URL tải."));
            }
            return new ResolvedDownload(
                    entry.downloadUrl,
                    entry.suggestedFileName,
                    entry.displayName,
                    entry.key,
                    entry.hotLoadSupported,
                    t(english, "Source: direct catalog entry", "Nguồn: entry catalog trực tiếp"),
                    t(english, "Compatibility depends on the file you configured.", "Độ tương thích phụ thuộc vào file bạn cấu hình.")
            );
        }

        return resolveModrinthDownload(entry, english);
    }

    private ResolvedDownload resolveModrinthDownload(CatalogEntry entry, boolean english) throws Exception {
        ServerRuntimeProfile runtime = detectRuntimeProfile();
        URI requestUri = URI.create(MODRINTH_API_BASE
                + "/project/" + encodePath(entry.projectSlug)
                + "/version?loaders="
                + encodeQueryValue(GSON.toJson(runtime.loaderPreference))
                + "&include_changelog=false");

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(Math.max(10L, getTimeoutSeconds())))
                .header("User-Agent", "AIAdmin/1.0.1")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new KnownDownloadProblem(t(english,
                    "Cannot query Modrinth for " + entry.displayName + " (HTTP " + response.statusCode() + ").",
                    "Không thể truy vấn Modrinth cho " + entry.displayName + " (HTTP " + response.statusCode() + ")."));
        }

        JsonElement root = GSON.fromJson(response.body(), JsonElement.class);
        JsonArray versions = root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        ModrinthCandidate best = selectBestModrinthCandidate(entry, versions, runtime);
        if (best == null) {
            throw new KnownDownloadProblem(t(english,
                    "No compatible build was found for " + entry.displayName + " on " + runtime.minecraftVersion + ".",
                    "Không tìm thấy bản phù hợp cho " + entry.displayName + " trên " + runtime.minecraftVersion + "."));
        }

        String versionSummary = t(english,
                "Selected version: " + best.versionNumber,
                "Đã chọn phiên bản: " + best.versionNumber);
        String compatibilitySummary = t(english,
                "Matched loader: " + best.matchedLoader + " | game: " + best.matchedGameVersion,
                "Khớp loader: " + best.matchedLoader + " | game: " + best.matchedGameVersion);

        return new ResolvedDownload(
                best.downloadUrl,
                best.fileName,
                entry.displayName,
                entry.key,
                entry.hotLoadSupported,
                versionSummary,
                compatibilitySummary
        );
    }

    private ModrinthCandidate selectBestModrinthCandidate(CatalogEntry entry, JsonArray versions, ServerRuntimeProfile runtime) {
        ModrinthCandidate best = null;
        for (JsonElement element : versions) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }

            JsonObject version = element.getAsJsonObject();
            JsonObject file = selectBestJarFile(version.getAsJsonArray("files"));
            if (file == null) {
                continue;
            }

            MatchScore loaderMatch = scoreLoaderMatch(version.getAsJsonArray("loaders"), runtime.loaderPreference);
            if (loaderMatch.score < 0) {
                continue;
            }

            MatchScore gameMatch = scoreGameMatch(version.getAsJsonArray("game_versions"), runtime.minecraftVersion, runtime.minecraftLine);
            int releaseScore = scoreReleaseType(extractString(version, "version_type", "release"));
            long publishedAt = parsePublishedAt(extractString(version, "date_published", ""));
            int totalScore = loaderMatch.score + gameMatch.score + releaseScore;

            ModrinthCandidate candidate = new ModrinthCandidate(
                    entry.displayName,
                    extractString(version, "version_number", extractString(version, "name", entry.displayName)),
                    extractString(file, "url", ""),
                    extractString(file, "filename", entry.suggestedFileName),
                    loaderMatch.value,
                    gameMatch.value,
                    loaderMatch.score,
                    gameMatch.score,
                    totalScore,
                    publishedAt
            );

            if (candidate.downloadUrl.isBlank()) {
                continue;
            }

            if (best == null || candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private JsonObject selectBestJarFile(JsonArray files) {
        if (files == null || files.isEmpty()) {
            return null;
        }

        JsonObject primaryJar = null;
        JsonObject fallbackJar = null;
        for (JsonElement element : files) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            String fileName = extractString(file, "filename", "");
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (fallbackJar == null) {
                fallbackJar = file;
            }
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                primaryJar = file;
                break;
            }
        }
        return primaryJar != null ? primaryJar : fallbackJar;
    }

    private MatchScore scoreLoaderMatch(JsonArray loaders, List<String> preference) {
        if (loaders == null || loaders.isEmpty()) {
            return new MatchScore(-1, "unknown");
        }

        int bestScore = -1;
        String bestValue = "unknown";
        for (JsonElement element : loaders) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String loader = element.getAsString().toLowerCase(Locale.ROOT);
            int index = preference.indexOf(loader);
            if (index < 0) {
                continue;
            }
            int score = 300 - (index * 25);
            if (score > bestScore) {
                bestScore = score;
                bestValue = loader;
            }
        }
        return new MatchScore(bestScore, bestValue);
    }

    private MatchScore scoreGameMatch(JsonArray gameVersions, String serverVersion, String serverLine) {
        if (gameVersions == null || gameVersions.isEmpty()) {
            return new MatchScore(0, "unknown");
        }

        int bestScore = 0;
        String bestValue = "unknown";
        for (JsonElement element : gameVersions) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String candidate = normalizeGameVersion(element.getAsString());
            if (candidate.isBlank()) {
                continue;
            }

            int score = scoreSingleGameVersion(candidate, serverVersion, serverLine);
            if (score > bestScore) {
                bestScore = score;
                bestValue = candidate;
            }
        }
        return new MatchScore(bestScore, bestValue);
    }

    private int scoreSingleGameVersion(String candidate, String serverVersion, String serverLine) {
        if (candidate.equals(serverVersion)) {
            return 500;
        }
        if (candidate.contains(serverVersion)) {
            return 490;
        }
        if (candidate.equals(serverLine) || candidate.equals(serverLine + ".x")) {
            return 440;
        }
        if (candidate.contains(serverLine)) {
            return 430;
        }
        int dash = candidate.indexOf('-');
        if (dash > 0) {
            String start = candidate.substring(0, dash).trim();
            String end = candidate.substring(dash + 1).trim();
            if (start.contains(serverLine) || end.contains(serverVersion) || end.contains(serverLine)) {
                return 420;
            }
        }
        String serverMajor = serverLine.contains(".") ? serverLine.substring(0, serverLine.indexOf('.')) : serverLine;
        if (!serverMajor.isBlank() && candidate.startsWith(serverMajor + ".")) {
            return 250;
        }
        return 0;
    }

    private String normalizeGameVersion(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .trim();
    }

    private int scoreReleaseType(String releaseType) {
        String normalized = releaseType == null ? "" : releaseType.toLowerCase(Locale.ROOT);
        if (normalized.equals("release")) {
            return 30;
        }
        if (normalized.equals("beta")) {
            return 20;
        }
        if (normalized.equals("alpha")) {
            return 10;
        }
        return 0;
    }

    private long parsePublishedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private ServerRuntimeProfile detectRuntimeProfile() {
        String minecraftVersion = resolveMinecraftVersion();
        String minecraftLine = resolveMinecraftLine(minecraftVersion);
        String serverName = Bukkit.getName() == null ? "paper" : Bukkit.getName().toLowerCase(Locale.ROOT);
        List<String> loaderPreference = resolveLoaderPreference(serverName);
        return new ServerRuntimeProfile(serverName, minecraftVersion, minecraftLine, loaderPreference);
    }

    private String resolveMinecraftVersion() {
        try {
            String bukkitVersion = Bukkit.getBukkitVersion();
            if (bukkitVersion != null && !bukkitVersion.isBlank()) {
                int dashIndex = bukkitVersion.indexOf('-');
                return (dashIndex > 0 ? bukkitVersion.substring(0, dashIndex) : bukkitVersion).trim();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private String resolveMinecraftLine(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return "unknown";
        }
        String[] parts = minecraftVersion.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return minecraftVersion;
    }

    private List<String> resolveLoaderPreference(String serverName) {
        List<String> loaders = new ArrayList<>();
        String normalized = serverName == null ? "" : serverName.toLowerCase(Locale.ROOT);

        if (normalized.contains("folia")) {
            Collections.addAll(loaders, "folia", "paper", "spigot", "bukkit");
        } else if (normalized.contains("purpur")) {
            Collections.addAll(loaders, "purpur", "paper", "spigot", "bukkit");
        } else if (normalized.contains("paper")) {
            Collections.addAll(loaders, "paper", "spigot", "bukkit");
        } else if (normalized.contains("velocity")) {
            Collections.addAll(loaders, "velocity", "waterfall", "bungeecord");
        } else if (normalized.contains("waterfall")) {
            Collections.addAll(loaders, "waterfall", "bungeecord", "velocity");
        } else if (normalized.contains("bungee")) {
            Collections.addAll(loaders, "bungeecord", "waterfall", "velocity");
        } else {
            Collections.addAll(loaders, "spigot", "bukkit", "paper");
        }
        return loaders;
    }

    private String tryLoadPlugin(File jarFile, boolean english) {
        if (jarFile == null || !jarFile.exists()) {
            return t(english, "&cJar file is missing.", "&cFile jar không tồn tại.");
        }

        PluginJarInfo info = inspectPluginJar(jarFile);
        String pluginName = info == null ? null : info.name;
        if (pluginName != null && pluginName.equalsIgnoreCase(plugin.getName())) {
            return t(english,
                    "&cAIAdmin cannot hot-load or replace itself.",
                    "&cAIAdmin không thể tự hot-load hoặc tự thay chính nó.");
        }

        try {
            Plugin alreadyLoaded = pluginName == null ? null : Bukkit.getPluginManager().getPlugin(pluginName);
            if (alreadyLoaded != null) {
                if (alreadyLoaded.isEnabled()) {
                    return t(english,
                            "&ePlugin is already loaded and enabled.",
                            "&ePlugin đã được load và đang bật sẵn.");
                }
                Bukkit.getPluginManager().enablePlugin(alreadyLoaded);
                return t(english,
                        "&aPlugin was already present and is now enabled.",
                        "&aPlugin đã có sẵn và vừa được bật.");
            }

            Plugin loaded = Bukkit.getPluginManager().loadPlugin(jarFile);
            if (loaded == null) {
                return t(english,
                        "&cPaper returned null while loading the plugin. Restart the server to enable it safely.",
                        "&cPaper trả về null khi load plugin. Hãy khởi động lại server để bật plugin an toàn.");
            }

            Bukkit.getPluginManager().enablePlugin(loaded);
            if (loaded.isEnabled()) {
                return t(english,
                        "&aHot-load succeeded for &f" + loaded.getName() + "&a.",
                        "&aHot-load thành công cho &f" + loaded.getName() + "&a.");
            }

            return t(english,
                    "&ePlugin file was downloaded, but enable did not complete. Restart the server.",
                    "&ePlugin đã được tải về, nhưng chưa bật xong. Hãy khởi động lại server.");
        } catch (Throwable throwable) {
            String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
            plugin.getLogger().warning("Hot-load failed for " + jarFile.getName() + ": " + message);
            return t(english,
                    "&cHot-load failed: " + message + ". Restart the server before using this plugin.",
                    "&cHot-load thất bại: " + message + ". Hãy khởi động lại server trước khi dùng plugin này.");
        }
    }

    private File getPluginsFolder() {
        File dataFolder = plugin.getDataFolder();
        File parent = dataFolder == null ? null : dataFolder.getParentFile();
        return parent == null ? new File("plugins") : parent;
    }

    private List<File> getPluginJarFiles(File pluginsFolder) {
        if (pluginsFolder == null || !pluginsFolder.exists() || !pluginsFolder.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = pluginsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> jarFiles = new ArrayList<>();
        Collections.addAll(jarFiles, files);
        jarFiles.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return jarFiles;
    }

    private boolean isLoadedPluginJar(File jarFile) {
        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            File loadedFile = resolvePluginFile(loaded);
            if (loadedFile != null && loadedFile.equals(jarFile)) {
                return true;
            }
        }
        return false;
    }

    private File resolvePluginJar(String pluginNameOrFile) {
        if (pluginNameOrFile == null || pluginNameOrFile.isBlank()) {
            return null;
        }
        String normalized = pluginNameOrFile.trim().toLowerCase(Locale.ROOT);
        for (File jarFile : getPluginJarFiles(getPluginsFolder())) {
            String jarName = jarFile.getName().toLowerCase(Locale.ROOT);
            if (jarName.equals(normalized) || jarName.equals(normalized + ".jar")) {
                return jarFile;
            }
            PluginJarInfo info = inspectPluginJar(jarFile);
            if (info != null && info.name.equalsIgnoreCase(pluginNameOrFile)) {
                return jarFile;
            }
        }
        return null;
    }

    private File resolvePluginFile(Plugin loaded) {
        if (!(loaded instanceof JavaPlugin)) {
            return null;
        }
        try {
            Method method = JavaPlugin.class.getDeclaredMethod("getFile");
            method.setAccessible(true);
            Object value = method.invoke(loaded);
            return value instanceof File ? (File) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private PluginJarInfo inspectPluginJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return null;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry metadataEntry = jar.getJarEntry("plugin.yml");
            if (metadataEntry == null) {
                metadataEntry = jar.getJarEntry("paper-plugin.yml");
            }
            if (metadataEntry == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(metadataEntry)) {
                String yamlText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                YamlConfiguration configuration = new YamlConfiguration();
                configuration.loadFromString(yamlText);
                String name = sanitizeDisplayFragment(configuration.getString("name"), "");
                if (name.isBlank()) {
                    return null;
                }
                String version = sanitizeDisplayFragment(configuration.getString("version"), "");
                return new PluginJarInfo(name, version);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isBuiltInCatalogEnabled() {
        return plugin.getPluginSettingsConfig() == null
                || plugin.getPluginSettingsConfig().getBoolean("plugin_manager.builtin_catalog_enabled", true);
    }

    private ConfigurationSection getCatalogSection() {
        return plugin.getPluginSettingsConfig() == null
                ? null
                : plugin.getPluginSettingsConfig().getConfigurationSection("plugin_manager.catalog");
    }

    private boolean isDirectUrl(String spec) {
        String normalized = spec.toLowerCase(Locale.ROOT);
        return normalized.startsWith("https://") || normalized.startsWith("http://");
    }

    private long getTimeoutSeconds() {
        return plugin.getPluginSettingsConfig() == null
                ? 45L
                : Math.max(10L, plugin.getPluginSettingsConfig().getLong("plugin_manager.download_timeout_seconds", 45L));
    }

    private String sanitizeFileName(String fileName) {
        String fallback = "downloaded-plugin.jar";
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        String normalized = fileName.replace("\\", "_").replace("/", "_").trim();
        return normalized.toLowerCase(Locale.ROOT).endsWith(".jar") ? normalized : normalized + ".jar";
    }

    private String extractFileNameFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return "downloaded-plugin.jar";
            }
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return sanitizeFileName(name);
        } catch (Exception ignored) {
            return "downloaded-plugin.jar";
        }
    }

    private String sanitizeCatalogKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private String findCatalogKey(ConfigurationSection catalog, String normalizedKey) {
        if (catalog == null) {
            return null;
        }
        if (catalog.contains(normalizedKey)) {
            return normalizedKey;
        }
        for (String key : catalog.getKeys(false)) {
            if (key.equalsIgnoreCase(normalizedKey)) {
                return key;
            }
        }
        return null;
    }

    private CatalogEntry readCatalogEntry(String key, ConfigurationSection section) throws KnownDownloadProblem {
        if (section == null) {
            throw new KnownDownloadProblem("Catalog entry '" + key + "' is missing.");
        }

        String displayName = section.getString("display_name", key);
        String officialUrl = section.getString("official_url", "");
        boolean manualOnly = section.getBoolean("manual_only", false);
        boolean hotLoadSupported = section.getBoolean("hot_load_supported", false);
        String fileName = section.getString("file_name", key + ".jar");
        String directUrl = section.getString("url", "").trim();
        String providerRaw = section.getString("provider", "").trim().toLowerCase(Locale.ROOT);
        String projectSlug = firstNonBlank(
                section.getString("project_slug"),
                section.getString("slug"),
                section.getString("project")
        );

        if (!directUrl.isBlank()) {
            return CatalogEntry.direct(key, displayName, directUrl, fileName, hotLoadSupported);
        }

        if (manualOnly) {
            return CatalogEntry.manual(key, displayName, officialUrl);
        }

        if (providerRaw.isBlank() || providerRaw.equals("modrinth")) {
            if (projectSlug == null || projectSlug.isBlank()) {
                throw new KnownDownloadProblem("Catalog entry '" + key + "' is missing project_slug.");
            }
            return CatalogEntry.modrinth(key, displayName, projectSlug, hotLoadSupported);
        }

        throw new KnownDownloadProblem("Unsupported provider '" + providerRaw + "' for catalog entry '" + key + "'.");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String extractString(JsonObject object, String key, String fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void sendLater(CommandSender sender, String message) {
        if (sender == null || message == null || message.isBlank()) {
            return;
        }
        String safeMessage = shortenForChat(message);
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(color(safeMessage)));
    }

    private String t(boolean english, String englishText, String vietnameseText) {
        return english ? englishText : vietnameseText;
    }

    private String color(String input) {
        return input == null ? "" : input.replace("&", "\u00A7");
    }

    private String sanitizeDisplayFragment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (cleaned.isBlank()) {
            return fallback;
        }
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim() + "...";
        }
        return cleaned;
    }

    private String shortenForChat(String message) {
        if (message == null) {
            return "";
        }
        String cleaned = message
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (cleaned.length() > MAX_CHAT_MESSAGE_LENGTH) {
            return cleaned.substring(0, MAX_CHAT_MESSAGE_LENGTH).trim() + "...";
        }
        return cleaned;
    }

    private static Map<String, CatalogEntry> createBuiltInLookup() {
        Map<String, CatalogEntry> map = new LinkedHashMap<>();

        registerEntry(map, CatalogEntry.modrinth("advancedbanx", "AdvancedBanX", "advancedbanx", false));
        registerEntry(map, CatalogEntry.modrinth("anti-dupe", "Anti-Dupe", "anti-dupe", true));
        registerEntry(map, CatalogEntry.modrinth("geyser", "Geyser", "geyser", false));
        registerEntry(map, CatalogEntry.modrinth("grimac", "GrimAC", "grimac", false));
        registerEntry(map, CatalogEntry.modrinth("ironwatch", "IronWatch", "ironwatch", true));
        registerEntry(map, CatalogEntry.modrinth("libertybans", "LibertyBans", "libertybans", false));
        registerEntry(map, CatalogEntry.modrinth("luckperms", "LuckPerms", "luckperms", false));
        registerEntry(map, CatalogEntry.modrinth("packetevents", "PacketEvents", "packetevents", false));
        registerEntry(map, CatalogEntry.modrinth("placeholderapi", "PlaceholderAPI", "placeholderapi", false));
        registerEntry(map, CatalogEntry.modrinth("viabackwards", "ViaBackwards", "viabackwards", false));
        registerEntry(map, CatalogEntry.modrinth("viaversion", "ViaVersion", "viaversion", false));
        registerEntry(map, CatalogEntry.manual("litebans", "LiteBans", "https://litebans.net/"));

        registerAlias(map, "advancedban", "advancedbanx");
        registerAlias(map, "advantageban", "advancedbanx");
        registerAlias(map, "antidupe", "anti-dupe");
        registerAlias(map, "grim", "grimac");
        registerAlias(map, "papi", "placeholderapi");
        registerAlias(map, "packets", "packetevents");
        registerAlias(map, "vb", "viabackwards");
        registerAlias(map, "vv", "viaversion");

        return Collections.unmodifiableMap(map);
    }

    private static void registerEntry(Map<String, CatalogEntry> map, CatalogEntry entry) {
        map.put(entry.key, entry);
    }

    private static void registerAlias(Map<String, CatalogEntry> map, String alias, String targetKey) {
        CatalogEntry target = map.get(targetKey);
        if (target != null) {
            map.put(alias, target);
        }
    }

    private static Set<String> createBuiltInSuggestions() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("advancedbanx");
        keys.add("anti-dupe");
        keys.add("geyser");
        keys.add("grimac");
        keys.add("ironwatch");
        keys.add("libertybans");
        keys.add("litebans");
        keys.add("luckperms");
        keys.add("packetevents");
        keys.add("placeholderapi");
        keys.add("viabackwards");
        keys.add("viaversion");
        return Collections.unmodifiableSet(keys);
    }

    private enum CatalogProvider {
        MODRINTH,
        DIRECT
    }

    private static final class CatalogEntry {
        private final String key;
        private final String displayName;
        private final CatalogProvider provider;
        private final String projectSlug;
        private final String suggestedFileName;
        private final String officialUrl;
        private final String downloadUrl;
        private final boolean manualOnly;
        private final boolean hotLoadSupported;

        private CatalogEntry(String key,
                             String displayName,
                             CatalogProvider provider,
                             String projectSlug,
                             String suggestedFileName,
                             String officialUrl,
                             String downloadUrl,
                             boolean manualOnly,
                             boolean hotLoadSupported) {
            this.key = key;
            this.displayName = displayName;
            this.provider = provider;
            this.projectSlug = projectSlug;
            this.suggestedFileName = suggestedFileName;
            this.officialUrl = officialUrl;
            this.downloadUrl = downloadUrl;
            this.manualOnly = manualOnly;
            this.hotLoadSupported = hotLoadSupported;
        }

        private static CatalogEntry modrinth(String key, String displayName, String projectSlug, boolean hotLoadSupported) {
            return new CatalogEntry(
                    key.toLowerCase(Locale.ROOT),
                    displayName,
                    CatalogProvider.MODRINTH,
                    projectSlug,
                    key + ".jar",
                    "https://modrinth.com/plugin/" + projectSlug,
                    "",
                    false,
                    hotLoadSupported && SAFE_HOTLOAD_KEYS.contains(key.toLowerCase(Locale.ROOT))
            );
        }

        private static CatalogEntry manual(String key, String displayName, String officialUrl) {
            return new CatalogEntry(
                    key.toLowerCase(Locale.ROOT),
                    displayName,
                    CatalogProvider.MANUAL,
                    "",
                    key + ".jar",
                    officialUrl,
                    "",
                    true,
                    false
            );
        }

        private static CatalogEntry direct(String key, String displayName, String downloadUrl, String fileName, boolean hotLoadSupported) {
            return new CatalogEntry(
                    key.toLowerCase(Locale.ROOT),
                    displayName,
                    CatalogProvider.DIRECT,
                    "",
                    fileName,
                    downloadUrl,
                    downloadUrl,
                    false,
                    hotLoadSupported
            );
        }
    }

    private static final class ResolvedDownload {
        private final String url;
        private final String fileName;
        private final String displayName;
        private final String key;
        private final boolean hotLoadSupported;
        private final String versionSummary;
        private final String compatibilitySummary;

        private ResolvedDownload(String url,
                                 String fileName,
                                 String displayName,
                                 String key,
                                 boolean hotLoadSupported,
                                 String versionSummary,
                                 String compatibilitySummary) {
            this.url = Objects.requireNonNullElse(url, "");
            this.fileName = Objects.requireNonNullElse(fileName, "downloaded-plugin.jar");
            this.displayName = Objects.requireNonNullElse(displayName, this.fileName);
            this.key = Objects.requireNonNullElse(key, this.fileName.toLowerCase(Locale.ROOT));
            this.hotLoadSupported = hotLoadSupported;
            this.versionSummary = Objects.requireNonNullElse(versionSummary, "");
            this.compatibilitySummary = Objects.requireNonNullElse(compatibilitySummary, "");
        }
    }

    private static final class KnownDownloadProblem extends Exception {
        private KnownDownloadProblem(String message) {
            super(message);
        }
    }

    private static final class MatchScore {
        private final int score;
        private final String value;

        private MatchScore(int score, String value) {
            this.score = score;
            this.value = value == null || value.isBlank() ? "unknown" : value;
        }
    }

    private static final class ServerRuntimeProfile {
        private final String serverName;
        private final String minecraftVersion;
        private final String minecraftLine;
        private final List<String> loaderPreference;

        private ServerRuntimeProfile(String serverName, String minecraftVersion, String minecraftLine, List<String> loaderPreference) {
            this.serverName = serverName;
            this.minecraftVersion = minecraftVersion;
            this.minecraftLine = minecraftLine;
            this.loaderPreference = List.copyOf(loaderPreference);
        }
    }

    private static final class ModrinthCandidate implements Comparable<ModrinthCandidate> {
        private final String displayName;
        private final String versionNumber;
        private final String downloadUrl;
        private final String fileName;
        private final String matchedLoader;
        private final String matchedGameVersion;
        private final int loaderScore;
        private final int gameScore;
        private final int totalScore;
        private final long publishedAt;

        private ModrinthCandidate(String displayName,
                                  String versionNumber,
                                  String downloadUrl,
                                  String fileName,
                                  String matchedLoader,
                                  String matchedGameVersion,
                                  int loaderScore,
                                  int gameScore,
                                  int totalScore,
                                  long publishedAt) {
            this.displayName = displayName;
            this.versionNumber = versionNumber;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.matchedLoader = matchedLoader == null || matchedLoader.isBlank() ? "unknown" : matchedLoader;
            this.matchedGameVersion = matchedGameVersion == null || matchedGameVersion.isBlank() ? "unknown" : matchedGameVersion;
            this.loaderScore = loaderScore;
            this.gameScore = gameScore;
            this.totalScore = totalScore;
            this.publishedAt = publishedAt;
        }

        @Override
        public int compareTo(ModrinthCandidate other) {
            if (other == null) {
                return 1;
            }
            int scoreCompare = Integer.compare(this.totalScore, other.totalScore);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            int gameCompare = Integer.compare(this.gameScore, other.gameScore);
            if (gameCompare != 0) {
                return gameCompare;
            }
            int loaderCompare = Integer.compare(this.loaderScore, other.loaderScore);
            if (loaderCompare != 0) {
                return loaderCompare;
            }
            return Long.compare(this.publishedAt, other.publishedAt);
        }
    }

    private static final class PluginJarInfo {
        private final String name;
        private final String version;

        private PluginJarInfo(String name, String version) {
            this.name = name == null ? "" : name;
            this.version = version == null ? "" : version;
        }
    }
}
