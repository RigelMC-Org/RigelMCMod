package org.rigelmc.punish;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/** Parses simple {@code /tban}-style duration strings like {@code "1d"}, {@code "2h30m"}. */
public final class DurationParser {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {}

    @NotNull
    public static Optional<Duration> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TOKEN.matcher(input);
        Duration total = Duration.ZERO;
        boolean matchedAny = false;
        while (matcher.find()) {
            matchedAny = true;
            long value = Long.parseLong(matcher.group(1));
            total =
                    switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                        case "d" -> total.plusDays(value);
                        case "h" -> total.plusHours(value);
                        case "m" -> total.plusMinutes(value);
                        case "s" -> total.plusSeconds(value);
                        default -> total;
                    };
        }
        return matchedAny ? Optional.of(total) : Optional.empty();
    }
}
