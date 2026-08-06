package org.rigelmc.data.dao;

import java.util.UUID;
import org.rigelmc.identity.PlayerIdentity;

/** A row from {@code rigel_players}. */
public record PlayerRecord(
        UUID uuid,
        String lastKnownName,
        PlayerIdentity identityType,
        String rankId,
        long firstSeenAt,
        long lastSeenAt) {}
