package org.rigelmc.punish.ban;

import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Distinguishes an IP address argument from a player-name argument for
 * {@code /permban <name|ip>}, without doing a DNS lookup (which would block and could
 * be abused to make the command hang) - a player name can never contain a {@code .} or
 * {@code :}, so a cheap structural check is sufficient and fast.
 */
public final class IpAddressUtil {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private IpAddressUtil() {}

    public static boolean looksLikeIp(@NotNull String input) {
        return IPV4.matcher(input).matches() || input.contains(":"); // ":" => IPv6 literal
    }
}
