package org.rigelmc.chat;

import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Shared {@code &}-color-code parsing for every piece of player-typed text that supports
 * it (chat messages, {@code /nick}, {@code /tag}, {@code /myadmin setlogin}) - "Essentials
 * color codes," i.e. the same syntax EssentialsChat used to translate before this server
 * stopped running it: the 16 standard {@code &0}-{@code &f} colors, {@code &k}-{@code &o}/
 * {@code &r} formatting, <b>and</b> {@code &#RRGGBB} true-color hex (EssentialsX's own
 * extension beyond vanilla's 16-color set).
 *
 * <p>Deliberately its own explicitly-configured {@link LegacyComponentSerializer}
 * instance rather than the {@link LegacyComponentSerializer#legacyAmpersand()} singleton
 * - that convenience factory's exact hex-support configuration isn't part of its documented
 * contract, so this builds one with {@code hexColors()} explicitly enabled instead of
 * relying on an assumption about the singleton's defaults.</p>
 */
public final class ColorCodes {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    /** Matches a standard {@code &0}-{@code &f}/{@code &k}-{@code &o}/{@code &r} code, or a {@code &#RRGGBB} hex one. */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("&([0-9a-fk-orA-FK-OR]|#[0-9a-fA-F]{6})");

    /** Parses {@code &}-codes (including {@code &#RRGGBB} hex) into a formatted {@link Component}. */
    @NotNull
    public static Component parse(@NotNull String raw) {
        return SERIALIZER.deserialize(raw);
    }

    /** @return whether {@code raw} contains at least one recognized {@code &}-code - i.e. whether {@link #parse} would change anything. */
    public static boolean hasColorCode(@NotNull String raw) {
        return CODE_PATTERN.matcher(raw).find();
    }

    /**
     * Round-trips {@code raw} through {@link #parse} and back into a legacy ({@code
     * §}-based) string - for the handful of call sites still working with plain {@code
     * String} rather than a {@link Component} (e.g. the legacy {@code
     * AsyncPlayerChatEvent}'s {@code String} message field). Hex colors are preserved via
     * the standard {@code §x§R§R§G§G§B§B} legacy escape sequence.
     */
    @NotNull
    public static String translateToLegacyString(@NotNull String raw) {
        return LegacyComponentSerializer.legacySection().serialize(parse(raw));
    }

    private ColorCodes() {}
}
