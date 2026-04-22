package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public class BlockPlaceListener implements Listener {

    private final SuspicionManager suspicionManager;

    public BlockPlaceListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location againstLocation = against == null ? null : against.getLocation();
        suspicionManager.recordBlockPlace(event.getPlayer(), placed.getLocation(), againstLocation);
    }
}
