package org.rigelmc.fun;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Server-wide jumppads - any wool-colored block launches a player standing on it upward
 * (and, in {@link Mode#NORMAL_AND_SIDEWAYS}, sideways off adjacent wool faces too). TFM
 * ref: {@code fun/Jumppads.java}, studied directly, ported near-verbatim (same three
 * modes, same damping coefficient).
 *
 * <p>Session-only: initialized from {@code fun.jumppads.*} on enable, live-changeable via
 * {@code /jumppads}, but never written back to config.yml - a restart reverts to whatever
 * config.yml says, matching {@code core.LockdownModule}'s own "always starts fresh on
 * restart" convention rather than TFM's own config-persisting behavior.</p>
 */
public final class JumppadService implements Listener {

    private static final double DAMPING_COEFFICIENT = 0.8;

    public enum Mode {
        OFF,
        NORMAL,
        NORMAL_AND_SIDEWAYS;

        @NotNull
        public static Mode fromConfig(@NotNull String raw) {
            try {
                return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OFF;
            }
        }
    }

    private final Map<UUID, Boolean> pushMap = new ConcurrentHashMap<>();
    private volatile Mode mode = Mode.OFF;
    private volatile double strength = 0.4;

    public void initialize(@NotNull Mode initialMode, double initialStrength) {
        this.mode = initialMode;
        this.strength = initialStrength;
    }

    @NotNull
    public Mode mode() {
        return mode;
    }

    public void setMode(@NotNull Mode newMode) {
        this.mode = newMode;
    }

    public double strength() {
        return strength;
    }

    public void setStrength(double newStrength) {
        this.strength = newStrength;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        Mode currentMode = mode;
        if (currentMode == Mode.OFF || !event.hasExplicitlyChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Block block = event.getTo().getBlock();
        Vector velocity = player.getVelocity().clone();

        if (currentMode == Mode.NORMAL) {
            UUID uuid = player.getUniqueId();
            boolean canPush = pushMap.getOrDefault(uuid, true);
            if (Tag.WOOL.isTagged(block.getRelative(0, -1, 0).getType())) {
                if (canPush) {
                    velocity.multiply(strength + 0.85).multiply(-1.0);
                }
                canPush = false;
            } else {
                canPush = true;
            }
            pushMap.put(uuid, canPush);
        } else {
            if (Tag.WOOL.isTagged(block.getRelative(0, -1, 0).getType())) {
                velocity.add(new Vector(0.0, strength, 0.0));
            }
            if (currentMode == Mode.NORMAL_AND_SIDEWAYS) {
                if (Tag.WOOL.isTagged(block.getRelative(1, 0, 0).getType())) {
                    velocity.add(new Vector(-DAMPING_COEFFICIENT * strength, 0.0, 0.0));
                }
                if (Tag.WOOL.isTagged(block.getRelative(-1, 0, 0).getType())) {
                    velocity.add(new Vector(DAMPING_COEFFICIENT * strength, 0.0, 0.0));
                }
                if (Tag.WOOL.isTagged(block.getRelative(0, 0, 1).getType())) {
                    velocity.add(new Vector(0.0, 0.0, -DAMPING_COEFFICIENT * strength));
                }
                if (Tag.WOOL.isTagged(block.getRelative(0, 0, -1).getType())) {
                    velocity.add(new Vector(0.0, 0.0, DAMPING_COEFFICIENT * strength));
                }
            }
        }

        if (!player.getVelocity().equals(velocity)) {
            player.setFallDistance(0.0f);
            player.setVelocity(velocity);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        pushMap.remove(event.getPlayer().getUniqueId());
    }
}
