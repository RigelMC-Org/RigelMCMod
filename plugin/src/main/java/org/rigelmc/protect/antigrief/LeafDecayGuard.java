package org.rigelmc.protect.antigrief;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.LeavesDecayEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * User-requested: leaves left disconnected from a log don't naturally decay by default -
 * a Free-OP server has no per-tree ownership, so a tree someone else placed/generated
 * shouldn't be able to silently strip the leaves off a build that incorporates them (hedges,
 * floating islands, decorative foliage). {@code protect.anti-grief.leaf-decay.enabled}
 * (default {@code false}) flips this back to vanilla behavior if wanted.
 *
 * <p>Reads {@code plugin.rigelConfig()} fresh on every event rather than caching a
 * reference - see {@link AntiNukeGuard}'s javadoc for why.</p>
 */
public final class LeafDecayGuard implements Listener {

    private final RigelMCMod plugin;

    public LeafDecayGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeavesDecay(@NotNull LeavesDecayEvent event) {
        if (!plugin.rigelConfig().leafDecayEnabled()) {
            event.setCancelled(true);
        }
    }
}
