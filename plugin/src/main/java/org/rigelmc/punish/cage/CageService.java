package org.rigelmc.punish.cage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;

/**
 * Builds a literal block cage around a player - TFM ref: {@code caging.Cager}/{@code
 * CageData}. A 5x5x5 hollow shell of {@code outerMaterial} (default {@code GLASS})
 * centered one block above the player, with the inner 3x3x3 fully cleared to {@code
 * innerMaterial} (default {@code AIR}) so they have room to stand. Blocks overwritten to
 * build the cage are snapshotted first and restored verbatim on release.
 *
 * <p>The block snapshot itself is deliberately session-only (in-memory, matching TFM's own
 * {@code cageHistory}), not persisted - only the "is caged, and where" flag survives a
 * restart (via {@link CageDao}), so a cage is rebuilt fresh at the player's post-restart
 * location on their next join, exactly like TFM's own {@code playerJoin()} behavior.</p>
 */
public final class CageService {

    private static final int OUTER_RADIUS = 2;
    private static final int INNER_RADIUS = 1;
    private static final double LEASH_RADIUS = 2.5;

    private final RigelMCMod plugin;
    private final CageDao cageDao;
    private final Map<UUID, ActiveCage> active = new HashMap<>();

    public CageService(@NotNull RigelMCMod plugin, @NotNull CageDao cageDao) {
        this.plugin = plugin;
        this.cageDao = cageDao;
    }

    /** Loads which players are still flagged as caged from a previous session. Call once on enable. */
    public void loadPersistedState() {
        try {
            for (CageRecord record : cageDao.findAll()) {
                active.put(record.uuid(), ActiveCage.fromRecord(record));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load persisted /cage state", e);
        }
    }

    public boolean isCaged(@NotNull UUID uuid) {
        ActiveCage cage = active.get(uuid);
        return cage != null && cage.blocksBuilt;
    }

    public void cage(@NotNull Player player, @Nullable UUID cagedBy, @NotNull Material outer, @NotNull Material inner) {
        UUID uuid = player.getUniqueId();
        ActiveCage existing = active.get(uuid);
        boolean wasAlreadyCaged = existing != null && existing.blocksBuilt;
        if (wasAlreadyCaged) {
            restoreBlocks(existing);
        }

        Location center = player.getLocation().clone().add(0, 1, 0);
        ActiveCage cage = new ActiveCage(center, outer, inner);
        if (wasAlreadyCaged) {
            cage.previousGameMode = existing.previousGameMode;
            cage.wasOp = existing.wasOp;
        } else {
            cage.previousGameMode = player.getGameMode();
            cage.wasOp = player.isOp();
        }
        active.put(uuid, cage);

        player.setOp(false);
        player.setGameMode(GameMode.ADVENTURE);
        buildBlocks(cage);
        persist(uuid, cagedBy, cage);
    }

    public void uncage(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        ActiveCage cage = active.remove(uuid);
        if (cage == null) {
            return;
        }
        restoreBlocks(cage);
        player.setOp(cage.wasOp);
        if (cage.previousGameMode != null) {
            player.setGameMode(cage.previousGameMode);
        }
        deletePersisted(uuid);
    }

    public void purgeAll(@NotNull Iterable<? extends Player> onlinePlayers) {
        for (Player player : onlinePlayers) {
            if (isCaged(player.getUniqueId())) {
                uncage(player);
            }
        }
    }

    /** Snaps a straying caged player back to the cage's center and regenerates it. */
    public void teleportToCenterAndRegenerate(@NotNull Player player) {
        ActiveCage cage = active.get(player.getUniqueId());
        if (cage == null) {
            return;
        }
        Location target = cage.center.clone().subtract(0, 1, 0);
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.teleport(target);
        generate(cage.center, OUTER_RADIUS, cage.outerMaterial, true);
        generate(cage.center, INNER_RADIUS, cage.innerMaterial, false);
    }

    public boolean isOutOfCage(@NotNull Player player, @NotNull Location to) {
        ActiveCage cage = active.get(player.getUniqueId());
        if (cage == null) {
            return false;
        }
        Location playerLoc = to.clone().add(0, 1, 0);
        World toWorld = playerLoc.getWorld();
        World cageWorld = cage.center.getWorld();
        if (toWorld == null || cageWorld == null || !toWorld.equals(cageWorld)) {
            return true;
        }
        return playerLoc.distanceSquared(cage.center) > LEASH_RADIUS * LEASH_RADIUS;
    }

    /**
     * Restores this player's cage blocks (world state only) without clearing their caged
     * flag - a quit/kick leaves the world clean, but {@link #onJoin} rebuilds it fresh if
     * they're still flagged, matching TFM's {@code playerQuit()}/{@code playerJoin()} split.
     */
    public void onQuitOrKick(@NotNull UUID uuid) {
        ActiveCage cage = active.get(uuid);
        if (cage != null && cage.blocksBuilt) {
            restoreBlocks(cage);
        }
    }

    public void onJoin(@NotNull Player player) {
        ActiveCage cage = active.get(player.getUniqueId());
        if (cage == null) {
            return;
        }
        Location center = player.getLocation().clone().add(0, 1, 0);
        ActiveCage refreshed = new ActiveCage(center, cage.outerMaterial, cage.innerMaterial);
        refreshed.previousGameMode = cage.previousGameMode != null ? cage.previousGameMode : player.getGameMode();
        refreshed.wasOp = cage.wasOp;
        active.put(player.getUniqueId(), refreshed);

        player.setOp(false);
        player.setGameMode(GameMode.ADVENTURE);
        buildBlocks(refreshed);
        persist(player.getUniqueId(), null, refreshed);
    }

    // ---- block manipulation --------------------------------------------------------------

    private void buildBlocks(ActiveCage cage) {
        if (!cage.blocksBuilt) {
            snapshot(cage);
        }
        generate(cage.center, OUTER_RADIUS, cage.outerMaterial, true);
        generate(cage.center, INNER_RADIUS, cage.innerMaterial, false);
        cage.blocksBuilt = true;
    }

    private void snapshot(ActiveCage cage) {
        Block center = cage.center.getBlock();
        cage.snapshot.clear();
        for (int x = -OUTER_RADIUS; x <= OUTER_RADIUS; x++) {
            for (int y = -OUTER_RADIUS; y <= OUTER_RADIUS; y++) {
                for (int z = -OUTER_RADIUS; z <= OUTER_RADIUS; z++) {
                    Block block = center.getRelative(x, y, z);
                    cage.snapshot.add(new SnapshotBlock(block.getLocation(), block.getType()));
                }
            }
        }
    }

    private void restoreBlocks(ActiveCage cage) {
        for (SnapshotBlock snapshotBlock : cage.snapshot) {
            snapshotBlock.location().getBlock().setType(snapshotBlock.material());
        }
        cage.snapshot.clear();
        cage.blocksBuilt = false;
    }

    private static void generate(Location center, int radius, Material material, boolean hollowShell) {
        Block centerBlock = center.getBlock();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (hollowShell && Math.abs(x) != radius && Math.abs(y) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    Block block = centerBlock.getRelative(x, y, z);
                    if (block.getType() != material) {
                        block.setType(material);
                    }
                }
            }
        }
    }

    // ---- persistence -----------------------------------------------------------------------

    private void persist(UUID uuid, @Nullable UUID cagedBy, ActiveCage cage) {
        try {
            World world = cage.center.getWorld();
            cageDao.upsert(new CageRecord(
                    uuid,
                    world != null ? world.getName() : "world",
                    cage.center.getX(), cage.center.getY(), cage.center.getZ(),
                    cage.outerMaterial.name(), cage.innerMaterial.name(),
                    cagedBy, System.currentTimeMillis()));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist /cage state", e);
        }
    }

    private void deletePersisted(UUID uuid) {
        try {
            cageDao.delete(uuid);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete persisted /cage state", e);
        }
    }

    // ---- state holders ---------------------------------------------------------------------

    private static final class ActiveCage {
        private final Location center;
        private final Material outerMaterial;
        private final Material innerMaterial;
        private final List<SnapshotBlock> snapshot = new ArrayList<>();
        private boolean blocksBuilt;
        private GameMode previousGameMode;
        private boolean wasOp;

        private ActiveCage(Location center, Material outerMaterial, Material innerMaterial) {
            this.center = center;
            this.outerMaterial = outerMaterial;
            this.innerMaterial = innerMaterial;
        }

        private static ActiveCage fromRecord(CageRecord record) {
            World world = Bukkit.getWorld(record.world());
            Location center = new Location(world, record.centerX(), record.centerY(), record.centerZ());
            ActiveCage cage = new ActiveCage(
                    center,
                    parseMaterial(record.outerMaterial(), Material.GLASS),
                    parseMaterial(record.innerMaterial(), Material.AIR));
            cage.blocksBuilt = false; // world blocks aren't rebuilt until the player actually rejoins
            return cage;
        }

        private static Material parseMaterial(String raw, Material fallback) {
            try {
                return Material.valueOf(raw);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    private record SnapshotBlock(Location location, Material material) {}
}
