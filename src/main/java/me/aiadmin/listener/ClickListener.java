package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

public class ClickListener implements Listener {

    private final SuspicionManager suspicionManager;

    public ClickListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }

        Player player = event.getPlayer();
        boolean lookingAtTarget = suspicionManager.isLookingAtTrackableTarget(player, 4.25D);
        boolean recentCombat = suspicionManager.hasRecentCombatContext(player);
        if (!lookingAtTarget && !recentCombat) {
            return;
        }

        suspicionManager.recordClick(player);
    }
}
