package com.crammedxp;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Set;

/**
 * Awards experience to mobs that die from entity cramming.
 *
 * <h2>Why nothing drops normally</h2>
 * Minecraft only grants experience when a mob's death is credited to a player -
 * internally, when it was damaged by one recently enough. Cramming, suffocation
 * and fall damage have no such credit, so the mob dies, drops its ordinary loot,
 * and yields zero experience. That is not a bug in the game; it is the rule that
 * stops fully automatic farms outproducing combat.
 *
 * <p>Restoring it therefore means setting the amount ourselves rather than asking
 * Bukkit for a value it will always report as zero.
 */
public final class CrammingListener implements Listener {

    private final CrammedXP plugin;

    public CrammingListener(CrammedXP plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs at MONITOR so any other plugin that wants to cancel the death, change
     * the drops, or set its own experience has already had its say. We only fill in
     * an amount nothing else has set.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!plugin.isEnabled() || !plugin.settings().active()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }

        // Something upstream already granted experience - a player landed the
        // killing blow, or another plugin set an amount. Leave it alone.
        if (event.getDroppedExp() > 0) {
            return;
        }

        EntityDamageEvent last = entity.getLastDamageCause();
        if (last == null) {
            return;
        }
        Set<EntityDamageEvent.DamageCause> causes = plugin.settings().causes();
        if (!causes.contains(last.getCause())) {
            return;
        }

        if (plugin.settings().isExcluded(entity.getType())) {
            return;
        }

        int xp = plugin.xpTable().xpFor(entity);
        if (xp <= 0) {
            return;
        }

        int radius = plugin.settings().requirePlayerWithin();
        if (radius > 0 && !playerNearby(entity, radius)) {
            // No one is around to collect it. Orbs would just sit there ticking
            // until they despawn, which on a busy farm is pure overhead.
            return;
        }

        event.setDroppedExp(xp);
    }

    private boolean playerNearby(LivingEntity entity, int radius) {
        double squared = (double) radius * radius;
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(entity.getLocation()) <= squared) {
                return true;
            }
        }
        return false;
    }
}
