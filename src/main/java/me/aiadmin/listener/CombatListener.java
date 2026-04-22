package me.aiadmin.listener;

import me.aiadmin.system.SuspicionManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class CombatListener implements Listener {

    private final SuspicionManager suspicionManager;

    public CombatListener(SuspicionManager suspicionManager) {
        this.suspicionManager = suspicionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        Entity damagerEntity = event.getDamager();
        Entity victimEntity = event.getEntity();
        if (!(damagerEntity instanceof Player)) {
            return;
        }

        Player damager = (Player) damagerEntity;
        if (!suspicionManager.isTrackableCombatTarget(victimEntity, damager)) {
            return;
        }

        suspicionManager.recordCombatInteraction(damager, victimEntity);
        if (victimEntity instanceof Player) {
            suspicionManager.recordReceivedDamage((Player) victimEntity, damagerEntity);
        }
    }
}
