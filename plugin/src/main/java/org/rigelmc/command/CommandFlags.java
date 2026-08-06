package org.rigelmc.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Shared flag parsing for punishment commands - see docs/architecture.md "Ban system"
 * for the baseline set ({@code -s}/{@code --silent}, {@code -r}/{@code --rollback},
 * {@code -c}/{@code --case}). Operates on a single trailing greedy-string Brigadier
 * argument (e.g. a command's combined "reason and flags" tail) rather than requiring a
 * dedicated grammar per flag, so every punishment command can share one simple parser.
 *
 * <p>Tokens starting with {@code -} are treated as flags and stripped out; everything
 * else is preserved, in order, as {@link #remainder()} (typically the punishment reason).
 * This is a deliberate, TFM-in-spirit baseline, not a verified port of TFM's actual flag
 * letters - see docs/architecture.md for that caveat.</p>
 */
public final class CommandFlags {

    private final Set<String> flags;
    private final String remainder;

    private CommandFlags(Set<String> flags, String remainder) {
        this.flags = flags;
        this.remainder = remainder;
    }

    @NotNull
    public static CommandFlags parse(String input) {
        if (input == null || input.isBlank()) {
            return new CommandFlags(Set.of(), "");
        }

        List<String> remaining = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (String token : input.trim().split("\\s+")) {
            if (isFlagToken(token)) {
                found.add(normalize(token));
            } else {
                remaining.add(token);
            }
        }
        return new CommandFlags(Set.copyOf(found), String.join(" ", remaining));
    }

    /** @return {@code true} if any of the given flag spellings (without dashes) was present */
    public boolean has(@NotNull String... aliases) {
        return Arrays.stream(aliases).map(CommandFlags::normalize).anyMatch(flags::contains);
    }

    /** @return everything left after stripping flag tokens - typically the punishment reason */
    @NotNull
    public String remainder() {
        return remainder;
    }

    private static boolean isFlagToken(String token) {
        // "-" alone or a bare negative number (e.g. a duration typo) shouldn't be
        // swallowed as a flag - require at least one letter after the dash(es).
        return token.length() > 1 && token.charAt(0) == '-' && !Character.isDigit(token.charAt(1));
    }

    @NotNull
    private static String normalize(String flag) {
        String stripped = flag.startsWith("--") ? flag.substring(2) : flag.substring(1);
        return stripped.toLowerCase(Locale.ROOT);
    }
}
