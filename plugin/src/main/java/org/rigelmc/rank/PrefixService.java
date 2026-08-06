package org.rigelmc.rank;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * Composes and caches a player's <b>single, highest-weight</b> rank/title prefix (e.g.
 * the current {@code Rank.SENIOR_ADMIN} prefix {@code "&8[&6SrA&8] &r"}) as a rendered
 * {@link Component} - the "Prefixes similar to TFM" feature. Cached per online player so
 * chat rendering (called per viewer, per message - see {@code chat.ChatFormatListener})
 * never does I/O; {@link #refresh} does the actual (blocking) DB read and must be called
 * off the main thread, then the cache is read from freely on any thread afterward.
 *
 * <p>Exactly one prefix shows, never several stacked: a Senior Admin who also holds the
 * Owner title shows only {@code [Owner]}, not {@code [SrA][Owner]} - see
 * {@link Title#weight(String)} (every title outranks every rank by design).</p>
 *
 * <p>{@link Rank#DEFAULT}'s generic {@code [OP]} bracket is a special case: per its own
 * javadoc it's meant to represent "currently holds vanilla operator status," not "holds
 * no RigelMCMod rank" - so unlike every other rank/title's prefix, it must track live
 * {@code Player#isOp()} state, not just DB-backed rank data. {@link #refresh} (which has
 * no live {@code Player} reference and runs off the main thread) computes what the
 * bracket <i>would</i> say but leaves it hidden by default; {@link #applyOpStatus} (cheap,
 * synchronous, called with a live {@code Player} right after every place op status can
 * change - see {@code core.AutoOpModule}) is what actually shows or hides it.</p>
 */
public final class PrefixService {

    private final RankService rankService;
    private final TitleService titleService;
    private final Map<UUID, Component> cache = new ConcurrentHashMap<>();
    private final Map<UUID, NamedTextColor> nameColorCache = new ConcurrentHashMap<>();
    /** The generic {@code [OP]} bracket text this player's rank would use, if/when {@link #applyOpStatus} shows it. */
    private final Map<UUID, Component> genericOpPrefixCache = new ConcurrentHashMap<>();

    public PrefixService(@NotNull RankService rankService, @NotNull TitleService titleService) {
        this.rankService = rankService;
        this.titleService = titleService;
    }

    /**
     * Recomputes and caches this player's composed prefix - whichever single rank/title
     * they hold with the highest weight, not a concatenation of everything they hold.
     * Blocking - call off the main thread.
     *
     * <p>Callers must follow up with {@link #applyOpStatus} (using a live {@code
     * Player#isOp()} read) before relying on {@link #prefixFor} - see this class's
     * javadoc for why the generic {@code [OP]} case can't be fully resolved here.</p>
     *
     * @param config current config, consulted for a {@code rank-prefixes} override of
     *     this player's rank's bracket text (falls back to {@link Rank#prefix()} if
     *     unset) - pass {@code plugin.rigelConfig()} fresh each call, not a captured
     *     reference, so {@code /rmcm reload} takes effect without a restart
     */
    public void refresh(@NotNull UUID uuid, @NotNull RigelConfig config) throws SQLException {
        Rank rank = rankService.rankOf(uuid);
        Set<String> titleIds = titleService.titleIdsFor(uuid);
        String rankPrefixText = config.rankPrefixOverride(rank.id()).orElse(rank.prefix());

        int bestWeight = rank.weight();
        String bestPrefix = rankPrefixText;
        Title winningTitle = null;

        for (String titleId : titleIds) {
            int titleWeight = Title.weight(titleId);
            if (titleWeight <= bestWeight) {
                continue; // a lower-weight title never displaces a higher-weight winner
            }
            Optional<Title> title = titleService.title(titleId);
            if (title.isEmpty()) {
                continue; // stale/unknown title id - shouldn't happen, ignore defensively
            }
            bestWeight = titleWeight;
            bestPrefix = title.get().prefix();
            winningTitle = title.get();
        }

        Component composedPrefix = bestPrefix.isEmpty() ? Component.empty() : toComponent(bestPrefix);
        if (rank.isDefault() && winningTitle == null) {
            // The generic OP case - remember what the bracket would say, but hide it by
            // default until applyOpStatus confirms the player is actually currently op'd.
            genericOpPrefixCache.put(uuid, composedPrefix);
            cache.put(uuid, Component.empty());
        } else {
            genericOpPrefixCache.remove(uuid);
            cache.put(uuid, composedPrefix);
        }

        // A winning title's color always wins over the rank's; "OPs by default have no
        // tag color" - see Rank.nameColor/Title.nameColor.
        Optional<NamedTextColor> resolved = winningTitle != null
                ? Title.nameColor(winningTitle.id())
                : (rank.isDefault() ? Optional.empty() : Rank.nameColor(rank.id()));
        if (resolved.isPresent()) {
            nameColorCache.put(uuid, resolved.get());
        } else {
            nameColorCache.remove(uuid);
        }
    }

    /**
     * Shows or hides the generic {@code [OP]} bracket based on live vanilla op status -
     * a no-op for a player whose current rank/title isn't the generic default case (a
     * real ranked/titled player's prefix never depends on op status). Cheap and
     * synchronous, no DB access - safe to call directly on the main thread right after
     * {@link #refresh}, or right after any {@code Player#setOp} call.
     */
    public void applyOpStatus(@NotNull UUID uuid, boolean isOp) {
        Component genericPrefix = genericOpPrefixCache.get(uuid);
        if (genericPrefix == null) {
            return; // not currently the generic-default case - nothing to correct
        }
        cache.put(uuid, isOp ? genericPrefix : Component.empty());
    }

    @NotNull
    public Component prefixFor(@NotNull UUID uuid) {
        return cache.getOrDefault(uuid, Component.empty());
    }

    /**
     * @return the color this player's name (not just their bracketed prefix) should be
     *     rendered in - see {@link Rank#nameColor(String)}. Empty for an unranked,
     *     untitled player (the generic {@code [OP]} case).
     */
    @NotNull
    public Optional<NamedTextColor> nameColorFor(@NotNull UUID uuid) {
        return Optional.ofNullable(nameColorCache.get(uuid));
    }

    public void clear(@NotNull UUID uuid) {
        cache.remove(uuid);
        nameColorCache.remove(uuid);
        genericOpPrefixCache.remove(uuid);
    }

    @NotNull
    private static Component toComponent(String legacyText) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }
}
