package org.rigelmc.protect.area;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * On-demand, bounded-duration particle outline for one region's boundary - {@code
 * /protectarea info <name> -show}. Deliberately <b>not</b> an always-on outline rendered
 * near every player (that would just be main-thread/packet overhead and visual clutter for
 * regular players walking past someone's protected build); TFM has no visualization at all
 * to improve on here (its {@code info} only prints raw coordinates as text).
 */
public final class AreaBoundaryVisualizer {

    private static final int DURATION_TICKS = 200; // ~10 seconds
    private static final long PERIOD_TICKS = 5L;
    private static final double STEP = 0.5;

    private AreaBoundaryVisualizer() {}

    /** Draws {@code region}'s 12 bounding-box edges for {@code viewer} only, self-cancelling after ~10s. */
    public static void show(@NotNull RigelMCMod plugin, @NotNull Player viewer, @NotNull AreaRegion region) {
        World world = Bukkit.getWorld(region.record().world());
        if (world == null) {
            return;
        }
        double minX = region.record().minX();
        double minY = region.record().minY();
        double minZ = region.record().minZ();
        double maxX = region.record().maxX() + 1.0;
        double maxY = region.record().maxY() + 1.0;
        double maxZ = region.record().maxZ() + 1.0;

        BukkitTask[] taskHolder = new BukkitTask[1];
        int[] elapsed = {0};
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline() || elapsed[0] >= DURATION_TICKS) {
                taskHolder[0].cancel();
                return;
            }
            elapsed[0] += (int) PERIOD_TICKS;
            drawEdges(world, viewer, minX, minY, minZ, maxX, maxY, maxZ);
        }, 0L, PERIOD_TICKS);
    }

    private static void drawEdges(World world, Player viewer, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        // The 4 bottom edges, 4 top edges, and 4 vertical corner edges of the box.
        edge(world, viewer, minX, minY, minZ, maxX, minY, minZ);
        edge(world, viewer, maxX, minY, minZ, maxX, minY, maxZ);
        edge(world, viewer, maxX, minY, maxZ, minX, minY, maxZ);
        edge(world, viewer, minX, minY, maxZ, minX, minY, minZ);

        edge(world, viewer, minX, maxY, minZ, maxX, maxY, minZ);
        edge(world, viewer, maxX, maxY, minZ, maxX, maxY, maxZ);
        edge(world, viewer, maxX, maxY, maxZ, minX, maxY, maxZ);
        edge(world, viewer, minX, maxY, maxZ, minX, maxY, minZ);

        edge(world, viewer, minX, minY, minZ, minX, maxY, minZ);
        edge(world, viewer, maxX, minY, minZ, maxX, maxY, minZ);
        edge(world, viewer, maxX, minY, maxZ, maxX, maxY, maxZ);
        edge(world, viewer, minX, minY, maxZ, minX, maxY, maxZ);
    }

    private static void edge(World world, Player viewer, double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) (length / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location point = new Location(world, x1 + dx * t, y1 + dy * t, z1 + dz * t);
            viewer.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
        }
    }
}
