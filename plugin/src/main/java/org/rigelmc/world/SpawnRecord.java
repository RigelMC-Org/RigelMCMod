package org.rigelmc.world;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** A row from {@code rigel_spawn} - the server's single configured spawn point, see {@link SpawnService}. */
public record SpawnRecord(
        String world, double x, double y, double z, float yaw, float pitch, @Nullable UUID setBy, long setAt) {}
