package org.rigelmc.rank;

import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

/**
 * A cosmetic/honorary label layered on top of a player's {@link Rank} - see
 * docs/architecture.md "Ranks vs. Titles". Titles grant no permissions by default; they
 * only affect the composed tab-list/chat prefix.
 *
 * <p>Prefix format matches {@link Rank}'s ({@code &8[&<color><Name>&8] &r}) for visual
 * consistency when a rank and a title prefix are composed together - not explicitly
 * requested when the rank colors were hardcoded in, but left inconsistent it would
 * otherwise mix the old abbreviated {@code [Dev]}-style brackets with the new full-name
 * rank style in the same rendered name.</p>
 */
public record Title(String id, String displayName, String prefix) {

    public static final Title DEVELOPER = new Title("developer", "Developer", "&8[&5Developer&8] &r");
    public static final Title EXECUTIVE = new Title("executive", "Executive", "&8[&4Executive&8] &r");
    public static final Title OWNER = new Title("owner", "Owner", "&8[&9Owner&8] &r");
    /** Store-purchase (Tebex) supporter tier - see {@code store.StoreModule}. Purely cosmetic, same as every other title - grants no rank/permission. */
    public static final Title SUPPORTER = new Title("supporter", "Supporter", "&8[&dSupporter&8] &r");
    /** Vote-milestone reward - see {@code vote.VoteRecordService}. */
    public static final Title VOTER = new Title("voter", "Voter", "&8[&aVoter&8] &r");

    public static java.util.List<Title> defaultTitles() {
        return java.util.List.of(DEVELOPER, EXECUTIVE, OWNER, SUPPORTER, VOTER);
    }

    /**
     * The color a title-holder's name is rendered in - see {@link Rank#nameColor(String)}
     * for the full rationale (kept in lock-step with each title's hardcoded prefix
     * bracket color above). Only consulted for an unranked-but-titled player;
     * {@code rank.PrefixService} prefers the {@link Rank} color whenever a real rank is
     * held.
     *
     * @param titleId a {@link #id()} value, e.g. {@code "owner"}
     */
    @NotNull
    public static Optional<NamedTextColor> nameColor(@NotNull String titleId) {
        return switch (titleId) {
            case "developer" -> Optional.of(NamedTextColor.DARK_PURPLE); // matches DEVELOPER's &5
            case "executive" -> Optional.of(NamedTextColor.DARK_RED); // matches EXECUTIVE's &4
            case "owner" -> Optional.of(NamedTextColor.BLUE); // matches OWNER's &9
            case "supporter" -> Optional.of(NamedTextColor.LIGHT_PURPLE); // matches SUPPORTER's &d
            case "voter" -> Optional.of(NamedTextColor.GREEN); // matches VOTER's &a
            default -> Optional.empty();
        };
    }

    /**
     * Every title outranks every {@link Rank}, including {@link Rank#SENIOR_ADMIN}
     * (weight 30) - titles conceptually sit "above" the rank ladder (see this class's own
     * javadoc), so a Senior Admin who also holds a title shows <b>only</b> the title's
     * prefix, never both stacked (see {@code PrefixService#refresh}, which picks the
     * single highest-weight prefix a player holds rather than concatenating everything).
     * Unknown/custom title ids default to just above {@code SENIOR_ADMIN} rather than 0,
     * so a config-added custom title (not one of the three built-ins) still outranks
     * every rank by default.
     *
     * @param titleId a {@link #id()} value, e.g. {@code "owner"}
     */
    public static int weight(@NotNull String titleId) {
        return switch (titleId) {
            case "developer" -> 40;
            case "executive" -> 50;
            case "owner" -> 60;
            // Deliberately below the staff/founder titles above - a paid or vote-earned
            // title should never visually override a real staff title if a player somehow
            // holds both, but still above the generic default (31) so it's not silently
            // indistinguishable from an unknown custom title.
            case "voter" -> 33;
            case "supporter" -> 35;
            default -> 31;
        };
    }
}
