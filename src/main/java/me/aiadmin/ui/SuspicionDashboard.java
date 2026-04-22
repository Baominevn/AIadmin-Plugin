package me.aiadmin.ui;

import me.aiadmin.AIAdmin;
import me.aiadmin.system.ServerScanner;
import me.aiadmin.system.SuspicionManager;
import me.aiadmin.system.SuspicionManager.PlayerRiskProfile;
import me.aiadmin.system.SuspicionManager.RiskTier;
import me.aiadmin.system.SuspicionManager.SkillClass;
import me.aiadmin.system.SuspicionManager.ThreatLevel;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SuspicionDashboard implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int SCAN_PAGE_SIZE = 36;

    private final AIAdmin plugin;
    private final SuspicionManager suspicionManager;

    public SuspicionDashboard(AIAdmin plugin, SuspicionManager suspicionManager) {
        this.plugin = plugin;
        this.suspicionManager = suspicionManager;
    }

    public void openDashboard(CommandSender sender, int page) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color(t(sender,
                    "&cChỉ người chơi mới mở được dashboard GUI.",
                    "&cOnly players can open the dashboard GUI.")));
            return;
        }

        Player viewer = (Player) sender;
        List<PlayerRiskProfile> suspicious = suspicionManager.getSuspiciousProfiles(200);
        int totalPages = Math.max(1, (int) Math.ceil((double) suspicious.size() / PAGE_SIZE));
        int safePage = Math.max(0, Math.min(totalPages - 1, page));
        int start = safePage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, suspicious.size());
        List<PlayerRiskProfile> slice = suspicious.isEmpty() ? Collections.emptyList() : suspicious.subList(start, end);

        DashboardHolder holder = new DashboardHolder(safePage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&c&lAIAdmin Dashboard"));
        holder.inventory = inventory;

        int slot = 0;
        for (PlayerRiskProfile profile : slice) {
            inventory.setItem(slot++, buildProfileItem(viewer, profile));
        }
        if (slice.isEmpty()) {
            inventory.setItem(22, summaryItem(
                    Material.LIME_STAINED_GLASS_PANE,
                    t(viewer, "&aChưa có người chơi nghi vấn", "&aNo suspicious players yet"),
                    t(viewer, "&7Hệ thống chưa ghi nhận mục tiêu nào cần theo dõi", "&7The system has not flagged anyone for review yet")
            ));
        }

        inventory.setItem(45, button(Material.ARROW,
                t(viewer, "&eTrang trước", "&ePrevious page"),
                t(viewer, "&7Về trang trước", "&7Go to the previous page")));
        inventory.setItem(46, summaryItem(
                Material.YELLOW_STAINED_GLASS_PANE,
                t(viewer, "&eMức LOW", "&eLOW tier"),
                "&7" + t(viewer, "Số lượng", "Count") + ": &f" + countThreat(ThreatLevel.LOW)
        ));
        inventory.setItem(47, button(Material.PAPER,
                t(viewer, "&fTrang", "&fPage"),
                "&7" + (safePage + 1) + " / " + totalPages));
        inventory.setItem(48, summaryItem(
                Material.ORANGE_STAINED_GLASS_PANE,
                t(viewer, "&6Mức MEDIUM", "&6MEDIUM tier"),
                "&7" + t(viewer, "Số lượng", "Count") + ": &f" + countThreat(ThreatLevel.MEDIUM)
        ));
        inventory.setItem(49, button(Material.CLOCK,
                t(viewer, "&bLàm mới", "&bRefresh"),
                t(viewer, "&7Tải lại danh sách nghi vấn", "&7Reload the suspicious list")));
        inventory.setItem(50, summaryItem(
                Material.RED_STAINED_GLASS_PANE,
                t(viewer, "&cMức HIGH", "&cHIGH tier"),
                "&7" + t(viewer, "Số lượng", "Count") + ": &f" + countThreat(ThreatLevel.HIGH)
        ));
        inventory.setItem(51, summaryItem(
                Material.ENDER_EYE,
                t(viewer, "&dIP bị gắn cờ", "&dFlagged IPs"),
                "&7IP: &f" + suspicionManager.countFlaggedIps()
        ));
        inventory.setItem(52, summaryItem(
                Material.BOOK,
                t(viewer, "&bTổng cảnh báo", "&bTotal alerts"),
                "&7" + t(viewer, "Số cảnh báo", "Alert count") + ": &f" + suspicionManager.getRecordedAlertCount()
        ));
        inventory.setItem(53, button(Material.ARROW,
                t(viewer, "&eTrang sau", "&eNext page"),
                t(viewer, "&7Sang trang kế tiếp", "&7Go to the next page")));

        viewer.openInventory(inventory);
    }

    public void openScanDashboard(CommandSender sender) {
        ServerScanner scanner = plugin.getServerScanner();
        ServerScanner.ScanSnapshot snapshot = scanner == null
                ? ServerScanner.ScanSnapshot.empty(false, System.currentTimeMillis())
                : scanner.getLatestScanSnapshot();
        openScanDashboard(sender, snapshot);
    }

    public void openScanDashboard(CommandSender sender, ServerScanner.ScanSnapshot snapshot) {
        openScanDashboard(sender, snapshot, 0);
    }

    private void openScanDashboard(CommandSender sender, ServerScanner.ScanSnapshot snapshot, int page) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color(t(sender,
                    "&cChỉ người chơi mới mở được scan dashboard.",
                    "&cOnly players can open the scan dashboard.")));
            return;
        }

        Player viewer = (Player) sender;
        ServerScanner.ScanSnapshot safeSnapshot = snapshot == null
                ? ServerScanner.ScanSnapshot.empty(false, System.currentTimeMillis())
                : snapshot;
        List<ServerScanner.ScanEntry> entries = safeSnapshot.getEntries();
        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / SCAN_PAGE_SIZE));
        int safePage = Math.max(0, Math.min(totalPages - 1, page));
        int start = safePage * SCAN_PAGE_SIZE;
        int end = Math.min(start + SCAN_PAGE_SIZE, entries.size());
        List<ServerScanner.ScanEntry> slice = entries.isEmpty() ? Collections.emptyList() : entries.subList(start, end);

        ScanHolder holder = new ScanHolder(safeSnapshot, safePage, totalPages);
        String title = safeSnapshot.isManual()
                ? t(viewer, "&b&lScan thủ công", "&b&lManual Scan")
                : t(viewer, "&b&lQuét Tự Động", "&b&lAuto Scan");
        Inventory inventory = Bukkit.createInventory(holder, 54, color(title));
        holder.inventory = inventory;

        int slot = 0;
        for (ServerScanner.ScanEntry entry : slice) {
            inventory.setItem(slot++, buildScanEntryItem(viewer, entry));
        }
        if (slice.isEmpty()) {
            inventory.setItem(22, summaryItem(
                    Material.GRAY_STAINED_GLASS_PANE,
                    t(viewer, "&7Chưa có dữ liệu scan", "&7No scan data yet"),
                    t(viewer, "&7Hãy chạy /ai scan để tạo bản quét đầu tiên", "&7Run /ai scan to generate the first snapshot")
            ));
        }

        inventory.setItem(36, summaryItem(Material.COMPASS,
                t(viewer, "&eĐã quét", "&eScanned"),
                "&f" + safeSnapshot.getScannedPlayers() + " &7/ &f" + safeSnapshot.getOnlinePlayers() + " &8(giới hạn " + safeSnapshot.getMaxPlayersPerScan() + ")"));
        inventory.setItem(37, summaryItem(Material.YELLOW_STAINED_GLASS_PANE, t(viewer, "&eTHẤP", "&eLOW"), "&7" + t(viewer, "Số lượng", "Count") + ": &f" + safeSnapshot.getLowCount()));
        inventory.setItem(38, summaryItem(Material.ORANGE_STAINED_GLASS_PANE, t(viewer, "&6TRUNG BÌNH", "&6MEDIUM"), "&7" + t(viewer, "Số lượng", "Count") + ": &f" + safeSnapshot.getMediumCount()));
        inventory.setItem(39, summaryItem(Material.RED_STAINED_GLASS_PANE, t(viewer, "&cCAO", "&cHIGH"), "&7" + t(viewer, "Số lượng", "Count") + ": &f" + safeSnapshot.getHighCount()));
        inventory.setItem(40, summaryItem(Material.ENDER_EYE, t(viewer, "&dĐang theo dõi", "&dObserving"), "&7" + t(viewer, "Số lượng", "Count") + ": &f" + safeSnapshot.getObservingCount()));
        inventory.setItem(41, summaryItem(Material.CLOCK, t(viewer, "&bThời gian", "&bTimestamp"), "&7" + formatTimestamp(safeSnapshot.getFinishedAtMillis())));
        inventory.setItem(42, summaryItem(Material.BOOK, t(viewer, "&aChế độ", "&aMode"),
                safeSnapshot.isManual() ? t(viewer, "&7Quét thủ công", "&7Manual scan") : t(viewer, "&7Quét tự động định kỳ", "&7Periodic auto scan")));
        inventory.setItem(43, summaryItem(Material.SPYGLASS, t(viewer, "&6Mở hồ sơ", "&6Open check"),
                t(viewer, "&7Bấm vào một player ở trên để xem chi tiết", "&7Click a player above to open the detail view")));
        inventory.setItem(44, button(Material.NETHER_STAR,
                t(viewer, "&dDashboard nghi vấn", "&dSuspicion dashboard"),
                t(viewer, "&7Mở dashboard nghi vấn tổng hợp", "&7Open the global suspicious players dashboard")));
        inventory.setItem(45, button(Material.ARROW, t(viewer, "&eTrang trước", "&ePrevious page"), t(viewer, "&7Về trang trước", "&7Go to the previous page")));
        inventory.setItem(49, button(Material.CLOCK, t(viewer, "&bLàm mới bản quét", "&bRefresh snapshot"), t(viewer, "&7Tải lại kết quả quét mới nhất", "&7Reload the latest scan snapshot")));
        inventory.setItem(53, button(Material.ARROW, t(viewer, "&eTrang sau", "&eNext page"), t(viewer, "&7Sang trang kế tiếp", "&7Go to the next page")));

        viewer.openInventory(inventory);
    }

    public void openPlayerCheck(CommandSender sender, String playerName) {
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordCheck(playerName);
        }

        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(playerName);
        ThreatLevel level = suspicionManager.getThreatLevel(profile.getSuspicion());
        SkillClass skillClass = suspicionManager.getSkillClass(profile);

        if (!(sender instanceof Player)) {
            boolean english = plugin.isEnglish(sender);
            sender.sendMessage(color("&6[Check] &f" + profile.getName()
                    + " | " + t(sender, "điểm nghi ngờ", "suspicion") + "=" + profile.getSuspicion()
                    + " | tier=" + suspicionManager.getRiskTier(profile.getSuspicion()).name()
                    + " | " + t(sender, "mức nguy cơ", "threat") + "=" + level.name()
                    + " | " + t(sender, "phân loại kỹ năng", "skill class") + "=" + skillClass.name()
                    + " | " + t(sender, "vị trí nghi ngờ", "last suspicious location") + "="
                    + profile.getLastSuspiciousLocationSummary(english)));
            return;
        }

        openPlayerCheck((Player) sender, playerName, null, false);
    }

    private void openPlayerCheck(Player viewer, String playerName, Integer backPage, boolean backToScan) {
        PlayerRiskProfile profile = suspicionManager.getOrCreateProfile(playerName);
        ThreatLevel level = suspicionManager.getThreatLevel(profile.getSuspicion());
        SkillClass skillClass = suspicionManager.getSkillClass(profile);

        CheckHolder holder = new CheckHolder(profile.getName(), backPage, backToScan);
        Inventory inventory = Bukkit.createInventory(holder, 54, color(t(viewer, "&b&lKiểm tra: &f", "&b&lCheck: &f") + profile.getName()));
        holder.inventory = inventory;

        fillCheckBackground(inventory);
        inventory.setItem(4, buildProfileItem(viewer, profile));
        inventory.setItem(19, buildSuspiciousLocationCard(viewer, profile));
        inventory.setItem(21, buildCurrentLocationCard(viewer, profile.getName()));
        inventory.setItem(23, buildThreatCard(viewer, profile, level, skillClass));
        inventory.setItem(25, buildLearningCard(viewer, profile));
        inventory.setItem(31, buildActionCard(viewer, level, skillClass));
        inventory.setItem(37, buildBehaviorCard(viewer, profile));
        inventory.setItem(39, buildEvidenceCard(viewer, profile));
        inventory.setItem(41, buildDatabaseAnalyticsCard(viewer));
        inventory.setItem(45, button(Material.ENDER_EYE,
                t(viewer, "&dBắt đầu quan sát", "&dStart observing"),
                t(viewer, "&7Cho AI và bot theo dõi người chơi này", "&7Let AI and the bot observe this player")));
        inventory.setItem(46, statCard(viewer, Material.REDSTONE, t(viewer, "&cĐiểm nghi ngờ", "&cSuspicion score"),
                "&f" + profile.getSuspicion(),
                "&7" + t(viewer, "Cảnh báo", "Alerts") + ": &f" + profile.getTotalAlerts()));
        inventory.setItem(47, statCard(viewer, Material.BLAZE_POWDER, t(viewer, "&6Hack / Pro", "&6Hack / Pro"),
                "&f" + profile.getHackConfidence() + " / " + profile.getProConfidence(),
                "&7" + t(viewer, "Phân loại kỹ năng", "Skill class") + ": &f" + skillClass.name()));
        inventory.setItem(48, statCard(viewer, Material.CLOCK, t(viewer, "&bDữ liệu theo dõi", "&bTelemetry"),
                "&fAim: " + profile.getSuspiciousAimSamples(),
                "&fMove: " + profile.getSuspiciousMoveSamples()));
        inventory.setItem(49, backPage != null
                ? button(Material.ARROW, t(viewer, "&eQuay lại", "&eBack"), t(viewer, "&7Trở về dashboard trước đó", "&7Return to the previous dashboard"))
                : button(Material.BARRIER, t(viewer, "&cĐóng", "&cClose"), t(viewer, "&7Đóng giao diện kiểm tra", "&7Close this check interface")));
        inventory.setItem(53, button(Material.CLOCK,
                t(viewer, "&bLàm mới dữ liệu", "&bRefresh data"),
                t(viewer, "&7Tải lại hồ sơ người chơi này", "&7Reload this player's profile")));

        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof DashboardHolder) && !(holder instanceof CheckHolder) && !(holder instanceof ScanHolder)) {
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory().getType() == InventoryType.PLAYER) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player viewer = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (holder instanceof DashboardHolder) {
            handleDashboardClick(viewer, (DashboardHolder) holder, slot, event.getCurrentItem());
            return;
        }
        if (holder instanceof ScanHolder) {
            handleScanClick(viewer, (ScanHolder) holder, slot, event.getCurrentItem());
            return;
        }
        handleCheckClick(viewer, (CheckHolder) holder, slot);
    }

    private void handleDashboardClick(Player viewer, DashboardHolder holder, int slot, ItemStack clicked) {
        if (slot == 45) {
            openDashboard(viewer, holder.page - 1);
            return;
        }
        if (slot == 49) {
            openDashboard(viewer, holder.page);
            return;
        }
        if (slot == 53) {
            openDashboard(viewer, holder.page + 1);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE || clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        String displayName = clicked.getItemMeta().getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        String playerName = ChatColor.stripColor(displayName).trim();
        if (!playerName.isBlank()) {
            openPlayerCheck(viewer, playerName, holder.page, false);
        }
    }

    private void handleScanClick(Player viewer, ScanHolder holder, int slot, ItemStack clicked) {
        if (slot == 44) {
            openDashboard(viewer, 0);
            return;
        }
        if (slot == 45) {
            openScanDashboard(viewer, holder.snapshot, holder.page - 1);
            return;
        }
        if (slot == 49) {
            openScanDashboard(viewer);
            return;
        }
        if (slot == 53) {
            openScanDashboard(viewer, holder.snapshot, holder.page + 1);
            return;
        }
        if (slot < 0 || slot >= SCAN_PAGE_SIZE || clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        String displayName = clicked.getItemMeta().getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        String playerName = ChatColor.stripColor(displayName).trim();
        if (!playerName.isBlank()) {
            openPlayerCheck(viewer, playerName, holder.page, true);
        }
    }

    private void handleCheckClick(Player viewer, CheckHolder holder, int slot) {
        if (slot == 45) {
            if (plugin.getServerScanner() != null) {
                plugin.getServerScanner().observePlayer(viewer, holder.playerName, "check-gui", true);
            }
            openPlayerCheck(viewer, holder.playerName, holder.backPage, holder.backToScan);
            return;
        }
        if (slot == 53) {
            openPlayerCheck(viewer, holder.playerName, holder.backPage, holder.backToScan);
            return;
        }
        if (slot != 49) {
            return;
        }
        if (holder.backPage != null) {
            if (holder.backToScan) {
                openScanDashboard(viewer);
            } else {
                openDashboard(viewer, holder.backPage);
            }
            return;
        }
        viewer.closeInventory();
    }

    private ItemStack buildScanEntryItem(CommandSender viewer, ServerScanner.ScanEntry entry) {
        Player online = Bukkit.getPlayerExact(entry.getPlayerName());
        ItemStack item = online != null ? new ItemStack(Material.PLAYER_HEAD, 1) : new ItemStack(materialForLevel(entry.getThreatLevel()), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (meta instanceof SkullMeta && online != null) {
            ((SkullMeta) meta).setOwningPlayer(online);
        }
        meta.setDisplayName(color(levelColor(entry.getThreatLevel()) + entry.getPlayerName()));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7" + t(viewer, "Mức rủi ro", "Risk tier") + ": &f" + entry.getRiskTier().name()));
        lore.add(color("&7" + t(viewer, "Mức nguy cơ", "Threat") + ": " + levelLabel(viewer, entry.getThreatLevel())));
        lore.add(color("&7" + t(viewer, "Điểm nghi ngờ", "Suspicion") + ": &f" + entry.getSuspicion()));
        lore.add(color("&7" + t(viewer, "Cảnh báo", "Alerts") + ": &f" + entry.getAlerts()));
        lore.add(color("&7Hack / Pro: &f" + entry.getHackConfidence() + " / " + entry.getProConfidence()));
        lore.add(color("&7CPS: &f" + entry.getCurrentCps() + " &8| &7Peak: &f" + entry.getRecentPeakCps()));
        lore.add(color("&7" + t(viewer, "Trạng thái", "State") + ": &f"
                + suspicionManager.describeBehaviorState(entry.getBehaviorState(), plugin.isEnglish(viewer))));
        lore.add(color("&7" + t(viewer, "Bằng chứng", "Evidence") + ": &f" + shorten(entry.getLatestEvidence(), 32)));
        lore.add(color("&7" + t(viewer, "Vị trí", "Location") + ": &f" + shorten(entry.getLocationSummary(), 32)));
        lore.add(color("&8" + t(viewer, "Bấm để mở check GUI", "Click to open the check GUI")));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSuspiciousLocationCard(CommandSender viewer, PlayerRiskProfile profile) {
        return statCard(viewer, Material.COMPASS,
                t(viewer, "&6Vị trí nghi ngờ", "&6Suspicious location"),
                "&f" + profile.getLastSuspiciousLocationSummary(plugin.isEnglish(viewer)),
                "&7IP: &f" + safe(profile.getLastKnownIp()));
    }

    private ItemStack buildCurrentLocationCard(CommandSender viewer, String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online == null || !online.isOnline() || online.getLocation() == null || online.getWorld() == null) {
            return statCard(viewer, Material.RECOVERY_COMPASS,
                    t(viewer, "&bVị trí hiện tại", "&bCurrent location"),
                    "&7" + t(viewer, "Người chơi đang offline", "Player is offline"),
                    "&7" + t(viewer, "Không có dữ liệu live", "No live position available"));
        }
        String summary = online.getWorld().getName() + " @ "
                + online.getLocation().getBlockX() + ", "
                + online.getLocation().getBlockY() + ", "
                + online.getLocation().getBlockZ();
        return statCard(viewer, Material.RECOVERY_COMPASS,
                t(viewer, "&bVị trí hiện tại", "&bCurrent location"),
                "&f" + summary,
                "&7Yaw/Pitch: &f" + Math.round(online.getLocation().getYaw()) + " / " + Math.round(online.getLocation().getPitch()));
    }

    private ItemStack buildThreatCard(CommandSender viewer, PlayerRiskProfile profile, ThreatLevel level, SkillClass skillClass) {
        return statCard(viewer, materialForLevel(level),
                t(viewer, "&cMức nguy cơ", "&cThreat level"),
                "&f" + levelLabel(viewer, level),
                "&7" + t(viewer, "Phân loại kỹ năng", "Skill class") + ": &f" + skillClass.name()
                        + " &8| &7" + t(viewer, "Điểm nghi ngờ", "Suspicion") + ": &f" + profile.getSuspicion());
    }

    private ItemStack buildProfileItem(CommandSender viewer, PlayerRiskProfile profile) {
        ThreatLevel level = suspicionManager.getThreatLevel(profile.getSuspicion());
        SkillClass skillClass = suspicionManager.getSkillClass(profile);

        Player online = Bukkit.getPlayerExact(profile.getName());
        ItemStack item = online != null ? new ItemStack(Material.PLAYER_HEAD, 1) : new ItemStack(materialForLevel(level), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        if (meta instanceof SkullMeta && online != null) {
            ((SkullMeta) meta).setOwningPlayer(online);
        }
        meta.setDisplayName(color(levelColor(level) + profile.getName()));
        meta.setLore(buildProfileLore(viewer, profile, level, skillClass));
        item.setItemMeta(meta);
        return item;
    }

    private List<String> buildProfileLore(CommandSender viewer, PlayerRiskProfile profile, ThreatLevel level, SkillClass skillClass) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7" + t(viewer, "Mức nguy cơ", "Threat") + ": " + levelLabel(viewer, level)));
        lore.add(color("&7" + t(viewer, "Mức rủi ro", "Risk tier") + ": &f" + suspicionManager.getRiskTier(profile.getSuspicion()).name()));
        lore.add(color("&7" + t(viewer, "Điểm nghi ngờ", "Suspicion") + ": &f" + profile.getSuspicion()));
        lore.add(color("&7" + t(viewer, "Tổng cảnh báo", "Alerts") + ": &f" + profile.getTotalAlerts()));
        lore.add(color("&7" + t(viewer, "Phân loại kỹ năng", "Skill class") + ": &f" + skillClass.name()));
        lore.add(color("&7Hack / Pro: &f" + profile.getHackConfidence() + " / " + profile.getProConfidence()));
        lore.add(color("&7" + t(viewer, "Vị trí nghi ngờ", "Suspicious location") + ": &f"
                + profile.getLastSuspiciousLocationSummary(plugin.isEnglish(viewer))));
        lore.add(color("&7IP: &f" + safe(profile.getLastKnownIp())));
        lore.add(color(t(viewer, "&7Peak gần đây: &f" + profile.getPeakCps(), "&7Recent peak: &f" + profile.getPeakCps())));
        lore.add(color("&7" + t(viewer, "CPS bất thường", "CPS flags") + ": &f" + profile.getHighCpsSamples()));
        lore.add(color("&7Fly / Scaffold / Xray / Spam: &f"
                + profile.getHoverFlySamples() + " / "
                + profile.getScaffoldSamples() + " / "
                + profile.getXraySamples() + " / "
                + profile.getChatSpamSamples()));
        lore.add(color("&7" + t(viewer, "Combat hợp lệ", "Legit combat") + ": &f" + profile.getLegitCombatSamples()));
        return lore;
    }

    private ItemStack buildLearningCard(CommandSender viewer, PlayerRiskProfile profile) {
        ItemStack item = new ItemStack(Material.BOOK, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(t(viewer, "&dDữ liệu hành vi", "&dBehavior telemetry")));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Aim: &f" + profile.getSuspiciousAimSamples()));
        lore.add(color("&7Move: &f" + profile.getSuspiciousMoveSamples()));
        lore.add(color(t(viewer, "&7CPS hiện tại: &f" + profile.getCurrentCps(), "&7Current CPS: &f" + profile.getCurrentCps())));
        lore.add(color(t(viewer, "&7Peak gần đây: &f" + profile.getPeakCps(), "&7Recent peak: &f" + profile.getPeakCps())));
        lore.add(color("&7" + t(viewer, "CPS bất thường", "CPS flags") + ": &f" + profile.getHighCpsSamples()));
        lore.add(color("&7Fly / Scaffold / Xray / Spam: &f"
                + profile.getHoverFlySamples() + " / "
                + profile.getScaffoldSamples() + " / "
                + profile.getXraySamples() + " / "
                + profile.getChatSpamSamples()));
        lore.add(color("&7" + t(viewer, "Combat hợp lệ", "Legit combat") + ": &f" + profile.getLegitCombatSamples()));
        lore.add(color("&7IP: &f" + safe(profile.getLastKnownIp())));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBehaviorCard(CommandSender viewer, PlayerRiskProfile profile) {
        ItemStack item = new ItemStack(Material.COMPARATOR, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        boolean english = plugin.isEnglish(viewer);
        meta.setDisplayName(color(t(viewer, "&aTrạng thái hành vi", "&aBehavior state")));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7" + t(viewer, "Trạng thái hiện tại", "Current state") + ": &f"
                + suspicionManager.describeBehaviorState(profile.getLastBehaviorState(), english)));
        long combatAgo = profile.getLastCombatMillis() <= 0L ? -1L : (System.currentTimeMillis() - profile.getLastCombatMillis()) / 1000L;
        lore.add(color("&7" + t(viewer, "Combat gần nhất", "Last combat") + ": &f"
                + (combatAgo < 0L ? t(viewer, "chưa có", "none") : combatAgo + "s")));
        lore.add(color("&7" + t(viewer, "Dấu vết gần nhất", "Latest evidence") + ": &f"
                + shorten(suspicionManager.getLatestEvidenceSummary(profile.getName(), english), 34)));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildEvidenceCard(CommandSender viewer, PlayerRiskProfile profile) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(t(viewer, "&6Timeline bằng chứng", "&6Evidence timeline")));
        List<String> lore = new ArrayList<>();
        List<PlayerRiskProfile.EvidenceEntry> entries = profile.getRecentEvidence(4);
        if (entries.isEmpty()) {
            lore.add(color(t(viewer, "&7Chưa có bằng chứng nổi bật nào được lưu.", "&7No recent evidence has been recorded yet.")));
        } else {
            long now = System.currentTimeMillis();
            for (PlayerRiskProfile.EvidenceEntry entry : entries) {
                long age = Math.max(0L, (now - entry.getTimestamp()) / 1000L);
                lore.add(color("&f" + age + "s &8| &e" + shorten(entry.getSource(), 12)
                        + " &7+" + entry.getPoints() + " &8(" + shorten(entry.getStateName(), 10) + ")"));
                lore.add(color("&7" + shorten(entry.getDetail(), 38)));
            }
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildDatabaseAnalyticsCard(CommandSender viewer) {
        ItemStack item = new ItemStack(Material.REDSTONE_LAMP, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(t(viewer, "&cAnalytics database", "&cDatabase analytics")));
        List<String> lore = new ArrayList<>();
        if (plugin.getDatabaseManager() == null) {
            lore.add(color(t(viewer, "&7Database chưa sẵn sàng.", "&7Database is not available.")));
        } else {
            lore.add(color("&7" + shorten(plugin.getDatabaseManager().getStatusSummary(), 38)));
            lore.add(color("&7" + shorten(plugin.getDatabaseManager().getAnalyticsSummary(), 38)));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildActionCard(CommandSender viewer, ThreatLevel level, SkillClass skillClass) {
        ItemStack item = new ItemStack(Material.NETHER_STAR, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(t(viewer, "&6Đề xuất xử lý", "&6Recommended action")));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7" + t(viewer, "Phân loại kỹ năng", "Skill class") + ": &f" + skillClass.name()));
        lore.add(color("&7" + t(viewer, "Mức nguy cơ", "Threat") + ": " + levelLabel(viewer, level)));
        lore.add(color("&f" + recommendAction(viewer, level, skillClass)));
        lore.add(color(t(viewer, "&8Bạn có thể bấm nút quan sát để AI theo dõi thêm", "&8You can click observe to let the AI watch longer")));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String recommendAction(CommandSender viewer, ThreatLevel level, SkillClass skillClass) {
        if (level == ThreatLevel.HIGH && skillClass == SkillClass.HACK_LIKELY) {
            return t(viewer, "Đề xuất kick ngay và xem xét termban.", "Recommend kicking immediately and reviewing a temp-ban.");
        }
        if (level == ThreatLevel.HIGH) {
            return t(viewer, "Cần staff theo dõi gấp để xác minh.", "Staff should verify this player urgently.");
        }
        if (level == ThreatLevel.MEDIUM) {
            return t(viewer, "Tiếp tục quan sát và đối chiếu alert anti-cheat.", "Keep observing and compare with anti-cheat alerts.");
        }
        if (skillClass == SkillClass.PRO) {
            return t(viewer, "Có thể là người chơi kỹ năng cao, cần giảm false positive.", "This may be a skilled player, so reduce false positives.");
        }
        return t(viewer, "Rủi ro thấp, tiếp tục scan định kỳ.", "Low risk for now. Keep periodic scans running.");
    }

    private void fillCheckBackground(Inventory inventory) {
        int[] borderSlots = new int[]{
                0, 1, 2, 3, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16, 17,
                18, 20, 22, 24, 26,
                27, 28, 29, 30, 32, 33, 34, 35,
                36, 38, 40, 42, 43, 44,
                50, 51, 52
        };
        for (int slot : borderSlots) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler(slot % 2 == 0 ? Material.BLACK_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
            }
        }
    }

    private ItemStack statCard(CommandSender viewer, Material material, String name, String line1, String line2) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(color(name));
        List<String> lore = new ArrayList<>();
        lore.add(color(line1));
        lore.add(color(line2));
        lore.add(color(t(viewer, "&8Tóm tắt nhanh", "&8Quick summary")));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(color(name));
        meta.setLore(Collections.singletonList(color(loreLine)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summaryItem(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(color(name));
        meta.setLore(Collections.singletonList(color(loreLine)));
        item.setItemMeta(meta);
        return item;
    }

    private int countThreat(ThreatLevel level) {
        if (level == ThreatLevel.HIGH) {
            return suspicionManager.countAtOrAbove(RiskTier.DANGER);
        }
        if (level == ThreatLevel.MEDIUM) {
            return Math.max(0, suspicionManager.countAtOrAbove(RiskTier.ALERT) - suspicionManager.countAtOrAbove(RiskTier.DANGER));
        }
        return Math.max(0, suspicionManager.countAtOrAbove(RiskTier.WATCH) - suspicionManager.countAtOrAbove(RiskTier.ALERT));
    }

    private Material materialForLevel(ThreatLevel level) {
        switch (level) {
            case HIGH:
                return Material.REDSTONE_BLOCK;
            case MEDIUM:
                return Material.ORANGE_CONCRETE;
            default:
                return Material.YELLOW_CONCRETE;
        }
    }

    private String levelColor(ThreatLevel level) {
        switch (level) {
            case HIGH:
                return "&c";
            case MEDIUM:
                return "&6";
            default:
                return "&e";
        }
    }

    private String levelLabel(CommandSender viewer, ThreatLevel level) {
        switch (level) {
            case HIGH:
                return color(t(viewer, "&cHIGH", "&cHIGH"));
            case MEDIUM:
                return color(t(viewer, "&6MEDIUM", "&6MEDIUM"));
            default:
                return color(t(viewer, "&eLOW", "&eLOW"));
        }
    }

    private String safe(String input) {
        return (input == null || input.isBlank()) ? "unknown" : input;
    }

    private String shorten(String input, int maxLength) {
        if (input == null || input.isBlank()) {
            return "-";
        }
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, Math.max(1, maxLength - 3)) + "...";
    }

    private String formatTimestamp(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private String t(CommandSender sender, String vietnamese, String english) {
        return plugin.tr(sender, vietnamese, english);
    }

    private String color(String input) {
        return input.replace("&", "\u00A7");
    }

    private static final class DashboardHolder implements InventoryHolder {
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private DashboardHolder(int page, int totalPages) {
            this.page = page;
            this.totalPages = totalPages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ScanHolder implements InventoryHolder {
        private final ServerScanner.ScanSnapshot snapshot;
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private ScanHolder(ServerScanner.ScanSnapshot snapshot, int page, int totalPages) {
            this.snapshot = snapshot;
            this.page = page;
            this.totalPages = totalPages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class CheckHolder implements InventoryHolder {
        private final String playerName;
        private final Integer backPage;
        private final boolean backToScan;
        private Inventory inventory;

        private CheckHolder(String playerName, Integer backPage, boolean backToScan) {
            this.playerName = playerName;
            this.backPage = backPage;
            this.backToScan = backToScan;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
