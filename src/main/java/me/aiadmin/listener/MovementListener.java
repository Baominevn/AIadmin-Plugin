package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class MovementListener implements Listener {

    private final SuspicionManager suspicionManager;

    public MovementListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        double delta = from.distance(to);
        if (delta <= 0) {
            return;
        }

        Player player = event.getPlayer();
        suspicionManager.captureMovement(player, from, to);
    }
}
