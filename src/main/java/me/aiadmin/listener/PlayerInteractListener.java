package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class PlayerInteractListener implements Listener {

    private final SuspicionManager suspicionManager;

    public PlayerInteractListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        suspicionManager.recordBlockInteract(event.getPlayer(), event.getClickedBlock());
    }
}
