package org.rigelmc.guild;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A row from {@code rigel_guilds}. {@code plotAreaId}/{@code plotSlotIndex} are {@code
 * null} until the plot-world sub-phase assigns this guild a plot - present in the schema
 * now so that later addition doesn't need a second migration.
 */
public record GuildRecord(
        int id,
        String name,
        String nameLower,
        UUID ownerUuid,
        @Nullable Integer plotAreaId,
        @Nullable Integer plotSlotIndex,
        long createdAt,
        long updatedAt) {
}
