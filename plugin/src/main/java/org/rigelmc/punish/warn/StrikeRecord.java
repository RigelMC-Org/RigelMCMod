package org.rigelmc.punish.warn;

import java.util.UUID;

/** A row from {@code rigel_strikes} - see {@link StrikeService}. */
public record StrikeRecord(UUID uuid, int strikeCount, long lastStrikeAt) {}
