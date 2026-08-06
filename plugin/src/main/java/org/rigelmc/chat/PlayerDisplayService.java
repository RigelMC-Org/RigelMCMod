package org.rigelmc.chat;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.nick.NickService;
import org.rigelmc.rank.PrefixService;
import org.rigelmc.tag.TagService;

/**
 * Unifies rank/title prefix ({@link PrefixService}), personal tag ({@link TagService}),
 * and nickname ({@link NickService}) into one composed display, applied identically to
 * the tab list, the player's Bukkit "display name", and chat rendering
 * ({@link ChatFormatListener}) so a player's name reads consistently everywhere. All
 * three source caches are O(1) in-memory reads - this class does no I/O of its own.
 *
 * <p>{@code /tag} is a <b>prefix</b>, not a trailing suffix, and it <i>fully replaces</i>
 * whatever rank/title prefix would otherwise show - the sole prefix for the rest of the
 * session, regardless of rank or title (even {@code Owner}): {@code tag + name}, never
 * {@code [rank]tag + name}. Session-only, per {@code tag.TagService} - resets to the
 * normal rank/title prefix the moment the player leaves.</p>
 */
public final class PlayerDisplayService {

    private final PrefixService prefixService;
    private final NickService nickService;
    private final TagService tagService;

    public PlayerDisplayService(
            @NotNull PrefixService prefixService, @NotNull NickService nickService, @NotNull TagService tagService) {
        this.prefixService = prefixService;
        this.nickService = nickService;
        this.tagService = tagService;
    }

    /**
     * Composes and pushes this player's tab-list name <b>and</b> their Bukkit "display
     * name" (both the modern {@link Player#displayName(Component)} and the legacy
     * {@link Player#setDisplayName(String)}). Main-thread only.
     *
     * <p>The display-name push matters a lot in practice: {@code getDisplayName()}/
     * {@code displayName()} is the decades-old, near-universal convention every chat
     * plugin's own formatter (EssentialsChat's {@code {DISPLAYNAME}} placeholder very much
     * included) reads as "the player's already-decorated name" - setting it means
     * RigelMCMod's prefix shows up correctly even when another plugin ends up building
     * the actual final chat line through a pipeline RigelMCMod's own
     * {@link ChatFormatListener} doesn't control (confirmed necessary in real testing:
     * setting only the chat-event format/renderer wasn't enough on a server where
     * Essentials' own formatter was what actually produced the sent message).</p>
     */
    @SuppressWarnings("deprecation") // Player#setDisplayName(String) - deliberate, see above
    public void apply(@NotNull Player player) {
        Component full = fullDisplayFor(player.getUniqueId(), player.getName());
        player.playerListName(full);
        player.displayName(full);
        player.setDisplayName(LegacyComponentSerializer.legacySection().serialize(full) + "§r");
    }

    /** @return the full composed display Component for use in chat rendering or elsewhere. */
    @NotNull
    public Component fullDisplayFor(@NotNull UUID uuid, @NotNull String realName) {
        Optional<Component> nickname = nickService.nicknameFor(uuid);
        Component name = nickname.orElseGet(() -> Component.text(realName));
        // Rank/title color applies to the name itself, not just the bracketed prefix -
        // matches the same color everywhere the name appears (chat, tab list, nametag -
        // see rank.NameTagService). Unranked/untitled ("OP") players keep no color.
        name = prefixService.nameColorFor(uuid).map(name::color).orElse(name);
        if (nickname.isPresent()) {
            // TFM-style: hovering a nickname reveals the real in-game name underneath it
            // - matches TotalFreedomMod's own ChatManager, which does exactly this for a
            // custom-nicknamed player's displayed name.
            name = name.hoverEvent(HoverEvent.showText(Component.text(realName)));
        }
        Component prefix = composedPrefix(uuid);
        return Component.empty().append(prefix).append(name);
    }

    @NotNull
    private Component composedPrefix(UUID uuid) {
        if (tagService.hasTag(uuid)) {
            // Sole prefix while set - fully replaces the rank/title prefix, not just the
            // generic default one, regardless of rank/title.
            return tagService.tagFor(uuid).append(Component.space());
        }
        return prefixService.prefixFor(uuid);
    }
}
