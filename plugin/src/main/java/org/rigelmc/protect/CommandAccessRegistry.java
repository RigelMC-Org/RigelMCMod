package org.rigelmc.protect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * TFM-style tiered command access control - studied directly from TFM's own
 * {@code CommandBlocker}/{@code CommandBlockerEntry}/{@code CommandBlockerRank} source,
 * not reimplemented from a description, so operators already familiar with TFM's config
 * syntax (or who want to extend it themselves) get the exact same expressiveness.
 *
 * <p>Each {@code protect.command-access} entry is a colon-delimited string:
 * {@code rank:action:/command [args...]:message}
 *
 * <ul>
 *   <li><b>rank</b> - the minimum rank allowed to run this command (adapted to
 *       RigelMCMod's own ladder, not TFM's):
 *       <ul>
 *         <li>{@code n} - Nobody. Fully disabled, short of the
 *             {@code rigelmcmod.protect.bypass.blockedcommands} permission.</li>
 *         <li>{@code m} - Moderator and above.</li>
 *         <li>{@code a} - Admin and above.</li>
 *         <li>{@code s} - Senior Admin and above.</li>
 *       </ul></li>
 *   <li><b>action</b> - what happens on a denied attempt, see {@link CommandAccessAction}:
 *       {@code b} (block + message), {@code k} (block + kick), {@code u} (block + fake
 *       "unknown command").</li>
 *   <li><b>command</b> - the base command (leading slash optional) plus an optional
 *       argument pattern. Two wildcard tokens are supported, exactly like TFM's:
 *       {@code {?}} matches exactly one argument at that position, {@code {*}} matches
 *       one or more trailing arguments (only valid as the last token). No pattern at all
 *       means "match regardless of arguments." When several rules exist for the same
 *       base command, the most specific one (most literal tokens, then most total
 *       tokens) is checked first - so a broad {@code {*}} catch-all can coexist with a
 *       more specific carve-out, same as TFM.</li>
 *   <li><b>message</b> - optional, {@code &}-color-coded, shown on a blocked attempt.
 *       Omit or use {@code _} for the default "That command is blocked." message.</li>
 * </ul>
 *
 * <p>Examples (straight from TFM's own default config, translated to this rank scheme):
 * <pre>
 *   - 'n:b:/execute:_'                    # fully disabled for everyone
 *   - 's:b:/clear {*}:_'                  # allow bare /clear, block /clear &lt;anything&gt;
 *   - 's:b:/clear inventory {*}:_'        # allow /clear inventory, block /clear inventory &lt;x&gt;
 *   - 's:k:/stop:&cDon't try that again.' # block AND kick, custom message
 * </pre>
 *
 * <p>Kept O(1)-ish per check: parsed once at load time (and on {@code /rmcm reload}) into
 * per-base-command buckets, pre-sorted by specificity - a lookup on the hot path (every
 * command a player runs) is a hash lookup plus a short in-order scan of that one
 * command's own rules, not a scan of the whole list.</p>
 */
public final class CommandAccessRegistry {

    // volatile: read on the command-preprocess hot path, reassigned wholesale by
    // reload() (called from /rmcm reload, potentially a different thread's call than
    // whatever's reading it) - needs to be visible across threads without relying on
    // incidental timing.
    private volatile Map<String, List<CommandAccessRule>> rulesByBaseCommand = Map.of();
    private final Logger logger;

    public CommandAccessRegistry(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Replaces every rule, without alias expansion - only the exact base command name
     * from each rule is registered. Mainly for tests; see the 2-arg overload for real
     * use, which also covers a command's registered aliases.
     *
     * @param rawEntries raw {@code rank:action:/command [args]:message} strings from
     *     {@code protect.command-access} in config.yml
     */
    public void reload(@NotNull List<String> rawEntries) {
        reload(rawEntries, baseCommand -> List.of());
    }

    /**
     * Replaces every rule. Call once at startup and on config reload.
     *
     * <p>{@code aliasesOf} additionally registers each rule under whatever aliases the
     * live server currently has for that base command (e.g. Essentials commonly aliases
     * {@code /vanish} to {@code /v}) - matches TFM's own {@code CommandBlocker#load},
     * which resolves aliases via the server's {@code CommandMap} for exactly this reason:
     * without it, a rule written against the canonical command name is trivially routed
     * around by anyone who just types a shorter alias instead. See
     * {@code ProtectModule#aliasesOf} for the real (CommandMap-backed) resolver used in
     * production.
     *
     * @param rawEntries raw {@code rank:action:/command [args]:message} strings from
     *     {@code protect.command-access} in config.yml
     * @param aliasesOf given a base command name, returns its known aliases (empty list
     *     if none/unknown)
     */
    public void reload(@NotNull List<String> rawEntries, @NotNull Function<String, List<String>> aliasesOf) {
        Map<String, List<CommandAccessRule>> loaded = new HashMap<>();
        int parsed = 0;
        for (String rawEntry : rawEntries) {
            CommandAccessRule rule = CommandAccessRule.parse(rawEntry, logger::warning);
            if (rule == null) {
                continue;
            }
            loaded.computeIfAbsent(rule.baseCommand(), k -> new ArrayList<>()).add(rule);
            for (String alias : aliasesOf.apply(rule.baseCommand())) {
                String normalizedAlias = alias.toLowerCase(Locale.ROOT);
                loaded.computeIfAbsent(normalizedAlias, k -> new ArrayList<>()).add(rule);
            }
            parsed++;
        }
        Comparator<CommandAccessRule> mostSpecificFirst =
                Comparator.comparingInt(CommandAccessRule::specificity).reversed();
        for (List<CommandAccessRule> bucket : loaded.values()) {
            bucket.sort(mostSpecificFirst);
        }
        this.rulesByBaseCommand = Map.copyOf(loaded);
        logger.info("Loaded " + parsed + " command-access rule(s) covering "
                + rulesByBaseCommand.size() + " command/alias label(s).");
    }

    /**
     * @param baseCommand the bare command label (no slash, no namespace, lowercased -
     *     see {@code BlockedCommandListener})
     * @param args the command's arguments, in order
     * @return the first (most specific) rule whose argument pattern matches, if any
     */
    @NotNull
    public Optional<CommandAccessRule> match(@NotNull String baseCommand, @NotNull String[] args) {
        List<CommandAccessRule> bucket = rulesByBaseCommand.get(baseCommand);
        if (bucket == null) {
            return Optional.empty();
        }
        for (CommandAccessRule rule : bucket) {
            if (rule.matches(args)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public int ruleCount() {
        int count = 0;
        for (List<CommandAccessRule> bucket : rulesByBaseCommand.values()) {
            count += bucket.size();
        }
        return count;
    }

    /** @return the rank id a config rank token maps to, or {@code null} for {@code "n"} (nobody) or an unknown token */
    @Nullable
    static String rankIdFromToken(@NotNull String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "m" -> "moderator";
            case "a" -> "admin";
            case "s" -> "senior_admin";
            default -> null; // "n" (nobody) and any unrecognized token both mean "nobody"
        };
    }
}
