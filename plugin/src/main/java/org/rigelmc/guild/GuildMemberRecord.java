package org.rigelmc.guild;

import java.util.UUID;

/** A row from {@code rigel_guild_members}. */
public record GuildMemberRecord(int guildId, UUID memberUuid, GuildRole role, long joinedAt) {
}
