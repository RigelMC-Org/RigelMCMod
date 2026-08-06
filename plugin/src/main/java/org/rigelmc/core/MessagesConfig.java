package org.rigelmc.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Typed access over {@code messages.yml} - the public/broadcast-facing strings punishment
 * and admin commands (ban, mute, freeze, cage, smite, op/deop, join/leave, ...) show to
 * the whole server, pulled out of Java so an operator can reword or re-theme them without
 * a rebuild.
 * Mirrors {@link RigelConfig}'s own "thin wrapper over {@link FileConfiguration}" design,
 * for the same trivial-unit-testability reasons.
 *
 * <p>Every message is MiniMessage-formatted. {@code {placeholder}} tokens are substituted
 * first, before MiniMessage parsing, so a placeholder's runtime value (e.g. a player-typed
 * ban reason) is never itself interpreted as MiniMessage tags. A key missing from the live
 * file falls back to the {@code fallback} the caller passes - which should always match
 * {@code resources/messages.yml}'s own default for that key - rather than crashing or
 * showing a raw key name; this is the same "call-site-supplied default" shape {@link
 * RigelConfig} already uses throughout ({@code source.getString(path, default)}).</p>
 */
public final class MessagesConfig {

    private final FileConfiguration source;

    public MessagesConfig(@NotNull FileConfiguration source) {
        this.source = source;
    }

    /**
     * @param key a dotted path into {@code messages.yml}, e.g. {@code "ban.public"}
     * @param fallback used if {@code key} isn't present in the live file
     * @param placeholderPairs alternating {@code name, value} pairs - e.g. {@code
     *     get("ban.public", "...", "target", name, "reason", reason)} - substituted as
     *     {@code {name}} before MiniMessage parsing
     */
    @NotNull
    public Component get(@NotNull String key, @NotNull String fallback, @NotNull String... placeholderPairs) {
        String raw = source.getString(key, fallback);
        for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
            raw = raw.replace("{" + placeholderPairs[i] + "}", placeholderPairs[i + 1]);
        }
        return MiniMessage.miniMessage().deserialize(raw);
    }
}
