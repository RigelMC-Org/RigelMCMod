package org.rigelmc.rank;

import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

/**
 * One rung of the permission-bearing rank ladder. See docs/architecture.md
 * "Ranks vs. Titles" - ranks grant permissions (by {@link #weight()} order); the
 * separate {@link org.rigelmc.rank.Title} concept is purely cosmetic. {@link #SENIOR_ADMIN}
 * is the top rank - {@code Developer}/{@code Executive}/{@code Owner} are titles worn by
 * Senior Admins, not additional ranks, and grant no extra permissions beyond it.
 *
 * <p>{@link #DEFAULT}'s prefix is deliberately non-empty - {@code [OP]} in red, shown
 * for every unranked player. On a Free-OP server everyone is vanilla-op'd
 * ({@code core.AutoOpModule}), so this is what visually distinguishes "just an auto-op'd
 * player" from real ranked staff (who show their rank name instead) - without it,
 * unranked players would render with no prefix at all, and a player couldn't tell staff
 * from everyone-else at a glance.</p>
 */
public record Rank(String id, String displayName, String prefix, int weight, boolean isDefault) {

    /** The built-in ladder seeded on first run if {@code rigel_ranks} is empty. */
    public static final Rank DEFAULT = new Rank("default", "OP", "&8[&cOP&8] &r", 0, true);
    public static final Rank MODERATOR = new Rank("moderator", "Moderator", "&8[&bMod&8] &r", 10, false);
    public static final Rank ADMIN = new Rank("admin", "Admin", "&8[&2Admin&8] &r", 20, false);
    public static final Rank SENIOR_ADMIN =
            new Rank("senior_admin", "Senior Admin", "&8[&6SrA&8] &r", 30, false);

    public static java.util.List<Rank> defaultLadder() {
        return java.util.List.of(DEFAULT, MODERATOR, ADMIN, SENIOR_ADMIN);
    }

    /**
     * The color a ranked player's name (not just their bracketed prefix) is rendered in,
     * in chat, the tab list, and their overhead nametag ({@code rank.NameTagService}) -
     * kept in lock-step with each rank's hardcoded prefix bracket color above, by design
     * (a Senior Admin's gold prefix and gold name are meant to match). {@link #DEFAULT}
     * (the generic auto-op'd {@code [OP]} player, not a real rank) deliberately resolves
     * to {@link Optional#empty()} - "OPs by default have no [name] tag color", only real
     * ranked staff get one.
     *
     * @param rankId a {@link #id()} value, e.g. {@code "senior_admin"}
     */
    @NotNull
    public static Optional<NamedTextColor> nameColor(@NotNull String rankId) {
        return switch (rankId) {
            case "moderator" -> Optional.of(NamedTextColor.AQUA); // matches MODERATOR's &b
            case "admin" -> Optional.of(NamedTextColor.DARK_GREEN); // matches ADMIN's &2
            case "senior_admin" -> Optional.of(NamedTextColor.GOLD); // matches SENIOR_ADMIN's &6
            default -> Optional.empty(); // "default" (OP) and any unknown/custom rank id
        };
    }
}
