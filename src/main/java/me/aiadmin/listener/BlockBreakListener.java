package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class BlockBreakListener implements Listener {

    private final SuspicionManager suspicionManager;

    public BlockBreakListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        suspicionManager.recordBlockBreak(event.getPlayer(), block);
    }
}
