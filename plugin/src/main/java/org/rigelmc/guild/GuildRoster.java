package org.rigelmc.guild;

import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The cached, queryable unit {@link GuildService} holds one of per guild - a {@link
 * GuildRecord} plus its resolved membership, immutable (every mutation replaces the
 * cached instance rather than mutating one in place, avoiding any need for its own
 * locking). Mirrors {@code protect.area.AreaRegion}'s relationship to {@code AreaRecord}.
 */
public final class GuildRoster {

    private final GuildRecord record;
    private final Map<UUID, GuildRole> members;

    public GuildRoster(@NotNull GuildRecord record, @NotNull Map<UUID, GuildRole> members) {
        this.record = record;
        this.members = Map.copyOf(members);
    }

    @NotNull
    public GuildRecord record() {
        return record;
    }

    @NotNull
    public Map<UUID, GuildRole> members() {
        return members;
    }

    @Nullable
    public GuildRole roleOf(@NotNull UUID uuid) {
        return members.get(uuid);
    }

    public boolean isMember(@NotNull UUID uuid) {
        return members.containsKey(uuid);
    }

    /** @return {@code true} if {@code uuid} is a member here with standing at least {@code required}. */
    public boolean isAtLeast(@NotNull UUID uuid, @NotNull GuildRole required) {
        GuildRole role = members.get(uuid);
        return role != null && role.isAtLeast(required);
    }

    @NotNull
    public UUID ownerUuid() {
        return record.ownerUuid();
    }

    public int id() {
        return record.id();
    }

    @NotNull
    public String name() {
        return record.name();
    }

    /** @return a copy of this roster with a different underlying record (e.g. after an owner/plot change). */
    @NotNull
    public GuildRoster withRecord(@NotNull GuildRecord newRecord) {
        return new GuildRoster(newRecord, members);
    }

    /** @return a copy of this roster with a different member map (e.g. after a join/kick/role change). */
    @NotNull
    public GuildRoster withMembers(@NotNull Map<UUID, GuildRole> newMembers) {
        return new GuildRoster(record, newMembers);
    }
}
