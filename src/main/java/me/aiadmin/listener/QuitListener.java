package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class QuitListener implements Listener {

    private final SuspicionManager suspicionManager;

    public QuitListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        suspicionManager.handleQuit(event.getPlayer());
    }
}
