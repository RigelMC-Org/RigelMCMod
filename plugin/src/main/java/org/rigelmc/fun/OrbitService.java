package org.rigelmc.fun;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /orbit} - repeatedly launches a toggled player upward. TFM ref: {@code
 * Orbiter.java} (the move-tick re-launch) + {@code FPlayer#isOrbiting/startOrbiting/
 * stopOrbiting}. Session-only, cleared on quit - purely a fun/gadget toggle, nothing to
 * persist.
 */
public final class OrbitService implements Listener {

    private final Map<UUID, Double> orbiting = new ConcurrentHashMap<>();

    public boolean isOrbiting(@NotNull UUID uuid) {
        return orbiting.containsKey(uuid);
    }

    public void startOrbiting(@NotNull Player player, double strength) {
        orbiting.put(player.getUniqueId(), strength);
        player.setVelocity(new Vector(0, strength, 0));
    }

    public void stopOrbiting(@NotNull UUID uuid) {
        orbiting.remove(uuid);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        Double strength = orbiting.get(player.getUniqueId());
        if (strength == null) {
            return;
        }
        if (player.getVelocity().length() < strength * (2.0 / 3.0)) {
            player.setVelocity(new Vector(0, strength, 0));
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        orbiting.remove(event.getPlayer().getUniqueId());
    }
}
