package org.rigelmc.protect.area;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable mirror of one {@code rigel_protect_areas} row. */
public record AreaRecord(
        int id,
        @NotNull String name,
        @NotNull String world,
        @NotNull String shapeType,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        int priority,
        boolean enabled,
        @Nullable UUID owner,
        @Nullable UUID createdBy,
        long createdAt,
        @Nullable UUID updatedBy,
        long updatedAt) {

    /** @return the number of blocks this region's bounding box contains - used for the smallest-wins overlap tie-break. */
    public long volume() {
        long dx = (long) (maxX - minX) + 1;
        long dy = (long) (maxY - minY) + 1;
        long dz = (long) (maxZ - minZ) + 1;
        return dx * dy * dz;
    }

    /** @return whether {@code (x, y, z)} in {@code worldName} falls within this region's bounds. */
    public boolean contains(@NotNull String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** @return whether this region's bounds intersect the given box in the same world, inclusive on every edge. */
    public boolean intersects(@NotNull String worldName, int otherMinX, int otherMinY, int otherMinZ,
            int otherMaxX, int otherMaxY, int otherMaxZ) {
        return world.equals(worldName)
                && minX <= otherMaxX && maxX >= otherMinX
                && minY <= otherMaxY && maxY >= otherMinY
                && minZ <= otherMaxZ && maxZ >= otherMinZ;
    }
}
