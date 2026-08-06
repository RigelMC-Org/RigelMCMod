package org.rigelmc.world;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Manages the admin-only "adminworld" - a private, separate world only real staff (and
 * their explicitly-invited guests) may enter. TFM-style ({@code world.AdminWorld},
 * studied directly from its source): a guest's access is tied to the specific staff
 * member ("supervisor") who invited them, and only holds while that supervisor is both
 * online and still actually staff - demoting or logging off a supervisor silently
 * revokes every guest they vouched for.
 *
 * <p>The guest list is deliberately session-only (in-memory), same as TFM's own (keyed
 * by live {@code Player} references there too) - it doesn't survive a restart, and
 * doesn't need to; re-inviting a guest after one is a single command.</p>
 */
public final class AdminWorldService {

    /** Minimum rank for unconditional access - see {@link #canAccess}. */
    private static final String REQUIRED_RANK = "moderator";

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final Map<UUID, UUID> guestSupervisors = new ConcurrentHashMap<>(); // guest -> supervisor

    public AdminWorldService(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    /** Creates the admin world if it doesn't already exist. Main-thread only. */
    public void ensureWorldExists() {
        String name = plugin.rigelConfig().adminWorldName();
        if (Bukkit.getWorld(name) != null) {
            return;
        }
        new WorldCreator(name).type(WorldType.FLAT).generateStructures(false).createWorld();
        plugin.getLogger().info("Created admin world '" + name + "'.");
    }

    @NotNull
    public Optional<World> world() {
        return Optional.ofNullable(Bukkit.getWorld(plugin.rigelConfig().adminWorldName()));
    }

    /**
     * @return {@code true} if this player may currently be in/enter the admin world:
     *     either a real staff member ({@value #REQUIRED_RANK}+), or a guest whose
     *     supervisor is online and still staff themselves. A guest whose supervisor no
     *     longer qualifies is silently dropped from the guest list as a side effect.
     */
    public boolean canAccess(@NotNull UUID uuid) {
        if (permissionGate.hasAtLeastCached(uuid, REQUIRED_RANK)) {
            return true;
        }
        UUID supervisorUuid = guestSupervisors.get(uuid);
        if (supervisorUuid == null) {
            return false;
        }
        Player supervisor = Bukkit.getPlayer(supervisorUuid);
        boolean stillValid = supervisor != null
                && supervisor.isOnline()
                && permissionGate.hasAtLeastCached(supervisorUuid, REQUIRED_RANK);
        if (!stillValid) {
            guestSupervisors.remove(uuid);
        }
        return stillValid;
    }

    /**
     * @return {@code true} if added - fails if the guest is already staff (no need to be
     *     a "guest") or the vouching supervisor isn't actually staff themselves
     */
    public boolean addGuest(@NotNull UUID guestUuid, @NotNull UUID supervisorUuid) {
        if (guestUuid.equals(supervisorUuid) || permissionGate.hasAtLeastCached(guestUuid, REQUIRED_RANK)) {
            return false;
        }
        if (!permissionGate.hasAtLeastCached(supervisorUuid, REQUIRED_RANK)) {
            return false;
        }
        guestSupervisors.put(guestUuid, supervisorUuid);
        return true;
    }

    public boolean removeGuest(@NotNull UUID guestUuid) {
        return guestSupervisors.remove(guestUuid) != null;
    }

    public void purgeGuests() {
        guestSupervisors.clear();
    }

    public boolean hasGuests() {
        return !guestSupervisors.isEmpty();
    }

    @NotNull
    public Map<UUID, UUID> guestSupervisors() {
        return Map.copyOf(guestSupervisors);
    }
}
