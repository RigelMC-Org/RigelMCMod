package org.rigelmc.guild;

/**
 * A member's standing within their guild - small fixed ladder, matching {@code
 * protect.area.AreaFlag}'s own "small fixed set" precedent rather than an open-ended
 * permission system. Purely a guild-internal concept, unrelated to {@code rank.Rank}
 * (server staff rank) - a guild OWNER has no more server-wide permissions than a MEMBER.
 */
public enum GuildRole {

    /** The guild's founder (or whoever ownership was transferred to). Exactly one per guild. */
    OWNER(2),
    /** Can invite/kick MEMBERs, but not other OFFICERs or the OWNER. */
    OFFICER(1),
    /** Default role for anyone accepted into the guild. */
    MEMBER(0);

    private final int weight;

    GuildRole(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    /** @return {@code true} if this role's standing is at least {@code other}'s. */
    public boolean isAtLeast(GuildRole other) {
        return weight >= other.weight;
    }
}
