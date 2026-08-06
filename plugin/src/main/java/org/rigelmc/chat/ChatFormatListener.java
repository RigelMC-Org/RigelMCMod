package org.rigelmc.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.discord.DiscordBotManager;

/**
 * Also translates {@code &}-color codes (including {@code &#RRGGBB} hex - see
 * {@link ColorCodes}) in the message body itself into real colors, now that this server no
 * longer runs EssentialsChat (previously the source of any chat coloring at all) - see
 * {@link org.rigelmc.core.RigelConfig#chatColorCodesEnabled}.
 *
 * <p><b>A message that actually contains a color code is delivered as a fully
 * server-composed, unsigned message instead of going through the normal signed-chat
 * renderer pathway</b> - see {@link #deliverColorizedMessage}. Two things were tried and
 * ruled out first, both confirmed against a real server rather than assumed:
 * <ol>
 *   <li>Overwriting {@code event.message(Component)} with the recolored version at an
 *       early priority (matching TFM's own {@code ChatManager}) - works, but Minecraft
 *       1.19.1+'s signed-chat system flags any divergence between what a client
 *       cryptographically signed and what {@code message()} ends up holding, showing the
 *       sender their own message with a "Message modified by the server" disclaimer.</li>
 *   <li>Doing the translation purely inside {@code event.renderer(...)}'s per-viewer
 *       callback, never touching {@code message()} at all - avoids that warning (the
 *       existing prefix/name composition below has always differed substantially from the
 *       raw signed text with no warning, confirming the renderer itself isn't compared
 *       against the signed original), but the color styling inside the message body
 *       specifically still didn't visibly render for at least one real client - the signed-
 *       chat pipeline appears to reconstruct the displayed message content from something
 *       other than the renderer's own return value for at least the message portion.</li>
 * </ol>
 * Only fully bypassing the signed-chat pipeline - cancelling and delivering a plain
 * {@code Player#sendMessage(Component)} to every viewer, exactly like every other
 * successfully-colored broadcast in this plugin (bans, announcements, ...) - reliably
 * works. This only applies to messages that actually contain a code ({@link
 * ColorCodes#hasColorCode}); a plain message still goes through the normal renderer
 * pathway below and keeps its chat-report signature intact.</p>
 *
 * <p>Renders chat as {@code <prefix><name><tag>: <message>} via
 * {@link PlayerDisplayService} - the actual "show prefixes/tags in chat" mechanism.
 *
 * <p><b>What TotalFreedomMod's real, current {@code ChatManager} actually does</b>
 * (confirmed by reading its production source directly, not a secondhand summary -
 * earlier notes in this project's history describing it as using the legacy
 * {@link AsyncPlayerChatEvent} at {@code HIGHEST} priority were simply wrong): it only
 * ever touches the modern {@link AsyncChatEvent}, at {@code EventPriority.LOWEST}, via
 * {@code event.message(Component)} (recolor/caps/length filtering) and
 * {@code event.renderer(...)} (the actual prefix/name/message composition) - never the
 * legacy event at all. TFM's own recolor-via-{@code event.message()} approach is exactly
 * what triggers the signed-chat warning described above - not replicated here for that
 * reason, see this class's own note above on why the renderer-only approach is used
 * instead.</p>
 *
 * <p>This class deliberately goes further than that, for reasons specific to being a
 * general-purpose plugin rather than software run on one specific, fully-controlled
 * server:
 * <ol>
 *   <li><b>{@code MONITOR} instead of {@code LOWEST}.</b> A single {@code renderer} field
 *       on the event is last-write-wins - whichever listener calls
 *       {@code event.renderer(...)} <i>last</i> (by priority order) is the one whose
 *       output actually gets used. Registering at {@code LOWEST} (TFM's choice) only wins
 *       if every other plugin on the server happens to cooperate by not overwriting an
 *       already-customized renderer - a convention, not something the API enforces. TFM
 *       can assume that because it controls its own server's exact plugin list; a
 *       general-purpose plugin can't, so this uses {@code MONITOR} - the last priority
 *       tier Bukkit dispatches, guaranteed to run after every other plugin's listener
 *       short of another plugin also (non-conventionally) using {@code MONITOR} to modify
 *       the event. {@code HIGHEST} was tried first and empirically was not always enough
 *       against a real EssentialsChat install; {@code MONITOR} is the strongest guarantee
 *       the API actually offers.</li>
 *   <li><b>Also setting the legacy {@link AsyncPlayerChatEvent} format string (and, for
 *       color codes, its message body too).</b> Paper's documented legacy-chat
 *       compatibility bridge means that if <i>any</i> plugin on the server still listens
 *       for the legacy event (older/alternate Essentials builds have historically done
 *       this), the legacy pipeline wins outright and the modern renderer's output is
 *       discarded regardless of priority. TFM doesn't need to hedge against this on its
 *       own server; a general-purpose plugin does. Setting both is not a double-send risk
 *       - only one pipeline actually performs the send either way, this just guarantees
 *       whichever one Paper ends up using already has the correct composed name (and,
 *       here, colorized message) in place. Translating the legacy event's plain-{@code
 *       String} message field carries none of the signed-chat risk described above - that
 *       system doesn't exist at this event's (much older) layer at all.</li>
 * </ol>
 *
 * <p>If chat prefixes still don't show even at {@code MONITOR}, the remaining explanation
 * is that whatever's interfering isn't using {@code event.renderer()}/{@code setFormat()}
 * at all - e.g. cancelling the event and manually broadcasting its own fully-built
 * message, which no listener priority can out-run since the manual send doesn't consult
 * either field. EssentialsChat specifically is the prime suspect on a server that also
 * runs Essentials; disabling its chat-formatting (its {@code chat-format} handling, not
 * all of Essentials) is the standard fix other prefix plugins document for exactly this
 * conflict.</p>
 */
public final class ChatFormatListener implements Listener {

    private final RigelMCMod plugin;
    private final PlayerDisplayService displayService;
    private final DiscordBotManager discordBotManager;
    private final Logger logger;

    public ChatFormatListener(
            @NotNull RigelMCMod plugin, @NotNull PlayerDisplayService displayService,
            @NotNull DiscordBotManager discordBotManager, @NotNull Logger logger) {
        this.plugin = plugin;
        this.displayService = displayService;
        this.discordBotManager = discordBotManager;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onModernChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        Component fullName;
        try {
            fullName = displayService.fullDisplayFor(player.getUniqueId(), player.getName());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Failed to compute chat prefix for " + player.getName(), e);
            return; // leave whichever renderer/format was already in place
        }

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (plugin.rigelConfig().chatColorCodesEnabled() && ColorCodes.hasColorCode(raw)) {
            deliverColorizedMessage(event, player, fullName, raw);
            return;
        }

        // event.renderer(...) only *registers* the lambda here - Paper actually invokes
        // it later (once per viewer), so error handling has to live inside the lambda
        // itself, not just around this registration call, or a throw from inside it
        // propagates straight into Paper's own chat-delivery code instead of anywhere
        // this class can catch it.
        Component finalName = fullName;
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            try {
                // Component-to-Component #append chaining (NOT the
                // Component.text()....build() *builder* chain) - confirmed the hard way
                // that the builder form can throw a runtime-only NoSuchMethodError when
                // this plugin's compiled-against Adventure version differs from what the
                // running server actually bundles - observed failing on a live Paper
                // 26.1.2 server when this plugin was compiled against paper-api 26.2;
                // now compiled directly against 26.1.2 instead (see
                // gradle/libs.versions.toml), but keep chaining this way regardless -
                // see CONTRIBUTING.md rule 3. This renderer runs on
                // the hot path for every chat message, so that one broken pattern was
                // silently defeating the entire prefix feature - Paper appears to fall
                // back to its own default (vanilla) renderer whenever this throws, which
                // is exactly the symptom that was reported.
                return Component.empty().append(finalName).append(Component.text(": ")).append(message);
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.WARNING, "Failed to render chat message for " + source.getName(), e);
                return ChatRenderer.defaultRenderer().render(source, sourceDisplayName, message, viewer);
            }
        });
    }

    /**
     * Cancels the signed-chat pipeline entirely and delivers a plain, fully server-composed
     * message to every viewer instead - see this class's javadoc for why this is the only
     * approach that's actually confirmed to render color reliably. Also relays to Discord
     * and echoes to the console/log directly, since cancelling the event means {@code
     * discord.PublicChatBridgeListener} ({@code ignoreCancelled = true}) and Bukkit's own
     * automatic console echo both silently skip it otherwise.
     */
    private void deliverColorizedMessage(AsyncChatEvent event, Player player, Component fullName, String raw) {
        event.setCancelled(true);
        Component colorized = ColorCodes.parse(raw);
        Component full = Component.empty().append(fullName).append(Component.text(": ")).append(colorized);
        String plainForLogAndDiscord = PlainTextComponentSerializer.plainText().serialize(full);

        for (Audience viewer : event.viewers()) {
            viewer.sendMessage(full);
        }
        plugin.getLogger().info(plainForLogAndDiscord);
        if (discordBotManager.isReady()) {
            discordBotManager.relayPublicMessage(
                    player.getName(), PlainTextComponentSerializer.plainText().serialize(colorized));
        }
    }

    @SuppressWarnings("deprecation") // AsyncPlayerChatEvent - deliberate, see class javadoc
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyChat(@NotNull AsyncPlayerChatEvent event) {
        try {
            Player player = event.getPlayer();
            Component fullName = displayService.fullDisplayFor(player.getUniqueId(), player.getName());
            String legacyName = LegacyComponentSerializer.legacySection().serialize(fullName);
            // Escape literal '%' in the composed name (color codes/tags/nicknames are
            // all player-influenced text) before it becomes part of a
            // java.util.Formatter pattern - same defensive escaping TFM's own
            // ChatManager does for its tag.
            String escapedName = legacyName.replace("%", "%%");
            event.setFormat(escapedName + ": %2$s");

            // Legacy pathway has no signed-chat concept at all (it predates that system
            // entirely), so translating the message body directly here carries none of
            // the risk described in this class's javadoc for the modern event.
            if (plugin.rigelConfig().chatColorCodesEnabled()) {
                event.setMessage(ColorCodes.translateToLegacyString(event.getMessage()));
            }
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Failed to render legacy chat prefix for " + event.getPlayer().getName(), e);
        }
    }
}
