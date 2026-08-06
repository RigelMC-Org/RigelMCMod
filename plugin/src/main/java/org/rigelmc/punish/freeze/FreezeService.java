package org.rigelmc.punish.freeze;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.api.event.RigelFreezeEvent;

/**
 * Player freeze - both per-player and a TFM-style global toggle. {@code /freeze} bare
 * toggles a <b>global</b> freeze affecting every currently-online player at once (the
 * literal TFM behavior - see {@code freeze.Freezer#globalFreeze}); {@code /freezeall} is a
 * direct alias for that same global toggle, since the user-facing name makes the effect
 * obvious even to someone who's never seen TFM's overloaded bare-{@code /freeze} idiom.
 * {@code /freeze <player>} targets one player individually.
 *
 * <p>Deliberately session-only, matching TFM's own {@code Freezer#globalFreeze} (explicitly
 * reset to {@code false} on every start) - not persisted to the database, so a restart
 * always comes back fully unfrozen rather than risking a stored "frozen" flag with no
 * matching auto-unfreeze timer to go with it.</p>
 *
 * <p>Pure Java, no Bukkit event handling here - {@link FreezeListener} is what actually
 * pins a frozen player in place on {@code PlayerMoveEvent}, matching TFM's own separation
 * (its {@code Freezer} service versus {@code FreezeData} per-player state).</p>
 */
public final class FreezeService {

    private final RigelMCMod plugin;
    private final Map<UUID, Location> frozenLocations = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> autoUnfreezeTasks = new ConcurrentHashMap<>();
    private volatile boolean globalFreeze = false;

    public FreezeService(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    public boolean isGlobalFreeze() {
        return globalFreeze;
    }

    public boolean isFrozen(@NotNull UUID uuid) {
        return globalFreeze || frozenLocations.containsKey(uuid);
    }

    /** @return the location a frozen player should be snapped back to, or {@code null} if unset yet */
    @Nullable
    public Location frozenLocationOf(@NotNull UUID uuid) {
        return frozenLocations.get(uuid);
    }

    /** {@code /freeze} bare (and {@code /freezeall}) - toggles the global freeze on every online player. */
    public void setGlobalFreeze(boolean frozen, @Nullable UUID actorUuid) {
        this.globalFreeze = frozen;
        for (Player online : Bukkit.getOnlinePlayers()) {
            new RigelFreezeEvent(online.getUniqueId(), frozen, actorUuid, "global freeze").callEvent();
        }
    }

    /** {@code /freeze <player>} - individually freezes one player, with an auto-unfreeze safety net. */
    public void freeze(@NotNull Player player, @Nullable UUID actorUuid) {
        UUID uuid = player.getUniqueId();
        frozenLocations.put(uuid, player.getLocation());
        player.setAllowFlight(true);
        player.setFlying(true);
        cancelAutoUnfreeze(uuid);

        long timeoutMinutes = plugin.rigelConfig().freezeAutoUnfreezeMinutes();
        if (timeoutMinutes > 0) {
            long ticks = timeoutMinutes * 60L * 20L;
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> unfreeze(player, null), ticks);
            autoUnfreezeTasks.put(uuid, task);
        }
        new RigelFreezeEvent(uuid, true, actorUuid, "individual freeze").callEvent();
    }

    /** {@code /freeze <player> off} - releases one individually-frozen player. */
    public void unfreeze(@NotNull Player player, @Nullable UUID actorUuid) {
        UUID uuid = player.getUniqueId();
        frozenLocations.remove(uuid);
        cancelAutoUnfreeze(uuid);
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        new RigelFreezeEvent(uuid, false, actorUuid, "individual unfreeze").callEvent();
    }

    /** {@code /freeze purge} - clears the global freeze and every individual freeze at once. */
    public void purge(@Nullable UUID actorUuid) {
        globalFreeze = false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (frozenLocations.containsKey(online.getUniqueId())) {
                unfreeze(online, actorUuid);
            }
        }
    }

    public void clearOnQuit(@NotNull UUID uuid) {
        frozenLocations.remove(uuid);
        cancelAutoUnfreeze(uuid);
    }

    private void cancelAutoUnfreeze(UUID uuid) {
        BukkitTask task = autoUnfreezeTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
}
