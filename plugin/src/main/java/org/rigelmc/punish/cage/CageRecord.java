package org.rigelmc.punish.cage;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** A row from {@code rigel_cages} - just the "is caged, and where" flag, see {@link CageService}. */
public record CageRecord(
        UUID uuid,
        String world,
        double centerX,
        double centerY,
        double centerZ,
        String outerMaterial,
        String innerMaterial,
        @Nullable UUID cagedBy,
        long appliedAt) {}
