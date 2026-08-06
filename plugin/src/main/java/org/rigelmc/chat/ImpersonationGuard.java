package org.rigelmc.chat;

import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.NotNull;

/**
 * Shared validation for self-service display customization ({@code /tag}, {@code /nick})
 * - a basic anti-impersonation guard, not a full profanity filter (no canonical word
 * list is bundled or endorsed here; that's a deliberate scope boundary, matching TFM's
 * own {@code nickfilter}/{@code nickclean} concept but only for the staff-prefix-spoofing
 * case this project can validate precisely). Rejects text that, once color codes are
 * stripped, contains a bracketed staff-rank/title term - e.g. a tag or nickname
 * containing {@code [Owner]} or {@code [Admin]} that could be mistaken for a real
 * RigelMCMod rank/title prefix (see {@code rank.Rank}/{@code rank.Title}).
 */
public final class ImpersonationGuard {

    private static final Pattern RESERVED_BRACKET_TERM = Pattern.compile(
            // "sra" listed explicitly - it's Rank.SENIOR_ADMIN's actual current bracket
            // abbreviation ([SrA]), not just a variant spelling of "senior admin".
            "\\[\\s*(owner|super\\s?admin|senior\\s?admin|sr\\s?admin|sra|admin|mod(erator)?|dev(eloper)?|"
                    + "exec(utive)?|staff|console)\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    private ImpersonationGuard() {}

    /** @return {@code true} if the text (after stripping color codes) looks like a staff-prefix spoof */
    public static boolean looksLikeImpersonation(@NotNull String input) {
        String translated = ChatColor.translateAlternateColorCodes('&', input);
        String stripped = ChatColor.stripColor(translated);
        return stripped != null && RESERVED_BRACKET_TERM.matcher(stripped.toLowerCase(Locale.ROOT)).find();
    }
}
