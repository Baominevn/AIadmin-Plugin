package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {

    private final SuspicionManager suspicionManager;

    public JoinListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        suspicionManager.handleJoin(event.getPlayer());
    }
}
