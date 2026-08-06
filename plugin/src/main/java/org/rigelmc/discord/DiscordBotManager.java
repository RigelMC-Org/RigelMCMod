package org.rigelmc.discord;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.RankService;

/**
 * Owns the Discord4J gateway connection and every Discord-facing relay/command-execution
 * path - see docs/architecture.md "Discord bridge & admin chat". Always constructed
 * (cheaply); {@link #start} is only called by {@code DiscordModule} when the module is
 * enabled and a bot token is configured, so other code (e.g. {@code ChatModule}'s
 * {@code /o}) can hold a reference unconditionally and just get safe no-ops via
 * {@link #isReady()} when the bridge isn't running.
 *
 * <p>Account linking ({@code /link}) and console execution ({@code /console}) are
 * <b>Discord application (slash) commands</b>, not {@code !}-prefixed message parsing -
 * registered globally as this bot connects (see {@link #registerCommands}). Plain chat
 * relay (public/admin channel messages mirrored in-game) is unaffected and still driven
 * off {@link MessageCreateEvent} - only the two command-like interactions moved to
 * Discord's own native command UI. {@code /console} is registered with
 * {@code dmPermission(false)} so Discord itself refuses it outside a guild, on top of the
 * explicit admin-channel-id check in {@link #handleConsoleCommand} - defense in depth for
 * the most security-sensitive path in this bridge.</p>
 *
 * <p>Built on Discord4J (reactive, Project Reactor {@code Mono}/{@code Flux}) rather than
 * JDA (listener-callback style). The one thing that matters most about the reactive model
 * here: {@link discord4j.core.event.EventDispatcher#on} returns a {@code Flux} whose
 * subscription <b>terminates permanently</b> the moment any exception escapes the
 * subscriber callback (standard Reactive Streams semantics - an {@code onError} signal
 * ends the stream, there's no "skip this one and keep going" by default). Every bit of
 * message/interaction-handling logic below is therefore wrapped in its own try/catch
 * <i>inside</i> the subscribed lambda - letting an exception escape would silently stop
 * the bot from processing anything further for the rest of the server's uptime, not just
 * fail the one event that triggered it.</p>
 *
 * <p>Slash-command replies use synchronous JDBC calls (the same account-linking/rank
 * lookups the old message-based flow used) before acknowledging the interaction, which
 * Discord requires within 3 seconds of receipt. That's comfortably fast against local
 * SQLite, but worth remembering if this is ever pointed at a slow/remote MySQL - an
 * "Unknown interaction" error would mean the ack arrived too late, not that anything is
 * broken. Not addressed with a defer/edit-reply flow here since it hasn't been an issue
 * in practice; revisit if it ever is.</p>
 *
 * <p><b>Unverified against a live Discord bot in this session</b> - written against
 * Discord4J 3.3.2's documented API shape (confirmed via direct bytecode inspection, not
 * assumed - see {@code CONTRIBUTING.md}), not exercised against a real gateway
 * connection. Treat the {@code /link}/{@code /console} interaction paths as needing a
 * real end-to-end smoke test before relying on them in production - global slash command
 * registration in particular can take up to an hour to propagate on Discord's side after
 * a first connect.</p>
 */
public final class DiscordBotManager {

    private static final String LINK_COMMAND = "link";
    private static final String CONSOLE_COMMAND = "console";
    private static final String CODE_OPTION = "code";
    private static final String COMMAND_OPTION = "command";

    private final Logger logger;

    private volatile GatewayDiscordClient client;
    private volatile TextChannel publicChannel;
    private volatile TextChannel adminChannel;
    private volatile TextChannel consoleChannel;

    public DiscordBotManager(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Connects to Discord on a background thread ({@code GatewayBootstrap#login()} is
     * blocked on here, and must never run on the main thread). Safe to call with a blank
     * token - logs a clear warning and leaves the bridge inert rather than throwing.
     */
    public void start(
            @NotNull RigelConfig config,
            @NotNull RigelMCMod plugin,
            @NotNull DiscordLinkService linkService,
            @NotNull RankService rankService,
            @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService) {
        String token = config.discordBotToken();
        if (token.isBlank()) {
            logger.warning(
                    "discord module is enabled but discord.bot-token is blank - Discord bridge will stay"
                            + " inactive. Set a token in config.yml to use it.");
            return;
        }

        Thread connectThread = new Thread(
                () -> connectBlocking(config, plugin, linkService, rankService, permissionGate, auditLogService, token),
                "RigelMCMod Discord Connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void connectBlocking(
            RigelConfig config,
            RigelMCMod plugin,
            DiscordLinkService linkService,
            RankService rankService,
            PermissionGate permissionGate,
            AuditLogService auditLogService,
            String token) {
        try {
            DiscordClient discordClient = DiscordClientBuilder.create(token).build();
            GatewayDiscordClient connected = discordClient
                    .gateway()
                    .setEnabledIntents(
                            IntentSet.of(Intent.GUILD_MESSAGES, Intent.DIRECT_MESSAGES, Intent.MESSAGE_CONTENT))
                    .login()
                    .block();
            if (connected == null) {
                logger.severe("Failed to connect the Discord bridge - login() returned no client.");
                return;
            }
            this.client = connected;

            connected.getEventDispatcher()
                    .on(MessageCreateEvent.class)
                    .subscribe(event -> {
                        try {
                            handleMessage(event, config, plugin, permissionGate);
                        } catch (RuntimeException e) {
                            // See class javadoc - an exception escaping this lambda would
                            // permanently kill the subscription, not just this one event.
                            logger.log(Level.WARNING, "Failed to handle a Discord message event", e);
                        }
                    });
            connected.getEventDispatcher()
                    .on(ChatInputInteractionEvent.class)
                    .subscribe(event -> {
                        try {
                            handleSlashCommand(
                                    event, config, plugin, linkService, rankService, permissionGate,
                                    auditLogService);
                        } catch (RuntimeException e) {
                            logger.log(Level.WARNING, "Failed to handle a Discord slash command", e);
                        }
                    });

            this.publicChannel = resolveChannel(connected, config.discordPublicChannelId());
            this.adminChannel = resolveChannel(connected, config.discordAdminChannelId());
            this.consoleChannel = resolveChannel(connected, config.discordConsoleChannelId());

            Long applicationId = connected.getRestClient().getApplicationId().block();
            if (applicationId != null) {
                registerCommands(connected, applicationId);
            } else {
                logger.warning("Could not resolve the Discord application id - /link and /console will not"
                        + " be registered.");
            }

            logger.info("Discord bridge connected as " + connected.getSelfId().asString() + ".");
        } catch (RuntimeException e) {
            // Discord4J's own connection failures throw a variety of runtime exception
            // types (invalid token, gateway errors, ...) - caught broadly and logged
            // rather than letting a background-thread exception vanish silently.
            logger.log(Level.SEVERE, "Failed to connect the Discord bridge - it will stay inactive.", e);
        }
    }

    /**
     * Registers (or re-registers - {@code bulkOverwrite} is idempotent, safe to call on
     * every connect) the {@code /link} and {@code /console} global slash commands.
     */
    private void registerCommands(GatewayDiscordClient connected, long applicationId) {
        int stringType = ApplicationCommandOption.Type.STRING.getValue();

        ApplicationCommandRequest linkCommand = ApplicationCommandRequest.builder()
                .name(LINK_COMMAND)
                .description("Link your Discord account to your RigelMCMod player account")
                .dmPermission(true) // usable in DMs - the whole point of this command
                .addOption(ApplicationCommandOptionData.builder()
                        .name(CODE_OPTION)
                        .description("The one-time code from /discord link in-game")
                        .type(stringType)
                        .required(true)
                        .build())
                .build();

        ApplicationCommandRequest consoleCommand = ApplicationCommandRequest.builder()
                .name(CONSOLE_COMMAND)
                .description("Run a server console command (admin channel only, linked + ranked account required)")
                .dmPermission(false) // Discord-level enforcement on top of the in-code channel check
                .addOption(ApplicationCommandOptionData.builder()
                        .name(COMMAND_OPTION)
                        .description("The command to run, without a leading /")
                        .type(stringType)
                        .required(true)
                        .build())
                .build();

        connected.getRestClient()
                .getApplicationService()
                .bulkOverwriteGlobalApplicationCommand(applicationId, List.of(linkCommand, consoleCommand))
                .subscribe(
                        data -> { },
                        error -> logger.log(Level.WARNING, "Failed to register Discord slash commands", error),
                        () -> logger.info("Registered Discord slash commands: /link, /console."));
    }

    @Nullable
    private TextChannel resolveChannel(GatewayDiscordClient connected, String channelId) {
        if (channelId.isBlank()) {
            return null;
        }
        Channel channel = connected.getChannelById(Snowflake.of(channelId)).block();
        if (!(channel instanceof TextChannel textChannel)) {
            logger.warning(
                    "Configured Discord channel id '" + channelId
                            + "' was not found or isn't a text channel - check config.yml.");
            return null;
        }
        return textChannel;
    }

    public boolean isReady() {
        return client != null;
    }

    public void relayPublicMessage(@NotNull String sender, @NotNull String message) {
        if (publicChannel != null) {
            publicChannel.createMessage("**" + sanitize(sender) + "**: " + sanitize(message)).subscribe();
        }
    }

    public void relayAdminMessage(@NotNull String sender, @NotNull String message) {
        if (adminChannel != null) {
            adminChannel.createMessage("**[Staff] " + sanitize(sender) + "**: " + sanitize(message)).subscribe();
        }
    }

    /** One-way: server log lines out to the console channel only, never accepts input back. */
    public void relayConsoleLine(@NotNull String line) {
        if (consoleChannel != null) {
            consoleChannel.createMessage(sanitize(line)).subscribe();
        }
    }

    public void shutdown() {
        GatewayDiscordClient connected = this.client;
        if (connected != null) {
            // Bounded, unlike JDA's fire-and-forget shutdown() - a hung network call
            // here must not hold up the rest of the plugin's onDisable().
            connected.logout().block(Duration.ofSeconds(5));
        }
    }

    /** Strips Discord markdown control characters/mass-mention vectors from relayed text. */
    @NotNull
    private static String sanitize(String input) {
        String noMentions = input.replace("@everyone", "@​everyone").replace("@here", "@​here");
        return noMentions.length() > 1800 ? noMentions.substring(0, 1800) + "..." : noMentions;
    }

    // ---- plain chat relay (public/admin channel messages -> in-game) -------------------

    private void handleMessage(
            MessageCreateEvent event, RigelConfig config, RigelMCMod plugin, PermissionGate permissionGate) {
        Message message = event.getMessage();
        Optional<User> authorOpt = message.getAuthor();
        if (authorOpt.isEmpty() || authorOpt.get().isBot()) {
            return; // empty = webhook/system message, not a real user
        }
        if (event.getGuildId().isEmpty()) {
            return; // DMs - no message-based commands anymore, see /link
        }
        User author = authorOpt.get();
        String channelId = message.getChannelId().asString();
        String content = message.getContent();

        if (channelId.equals(config.discordAdminChannelId())) {
            relayAdminChannelToGame(plugin, permissionGate, author, content);
        } else if (channelId.equals(config.discordPublicChannelId())) {
            relayPublicChannelToGame(plugin, author, content);
        }
    }

    private void relayAdminChannelToGame(RigelMCMod plugin, PermissionGate permissionGate, User author, String content) {
        Component formatted = Component.text(
                "[Discord/Staff] " + author.getUsername() + ": " + content, NamedTextColor.LIGHT_PURPLE);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(p -> permissionGate.hasAtLeastCached(p.getUniqueId(), "moderator"))
                .forEach(p -> p.sendMessage(formatted)));
    }

    private void relayPublicChannelToGame(RigelMCMod plugin, User author, String content) {
        String formatted = "[Discord] " + author.getUsername() + ": " + content;
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(Component.text(formatted)));
    }

    // ---- slash commands: /link, /console ------------------------------------------------

    private void handleSlashCommand(
            ChatInputInteractionEvent event,
            RigelConfig config,
            RigelMCMod plugin,
            DiscordLinkService linkService,
            RankService rankService,
            PermissionGate permissionGate,
            AuditLogService auditLogService) {
        switch (event.getCommandName()) {
            case LINK_COMMAND -> handleLinkCommand(event, linkService);
            case CONSOLE_COMMAND -> handleConsoleCommand(
                    event, config, plugin, linkService, rankService, auditLogService);
            default -> logger.fine("Ignoring unknown Discord slash command: " + event.getCommandName());
        }
    }

    private void handleLinkCommand(ChatInputInteractionEvent event, DiscordLinkService linkService) {
        String code = event.getOptionAsString(CODE_OPTION).orElse("").trim().toUpperCase(Locale.ROOT);
        String discordUserId = event.getInteraction().getUser().getId().asString();

        try {
            Optional<UUID> linkedUuid =
                    linkService.consumeLinkCode(code, discordUserId, System.currentTimeMillis());
            if (linkedUuid.isPresent()) {
                replyEphemeral(event, "Linked! Your Discord account is now tied to your RigelMCMod account.");
            } else {
                replyEphemeral(event, "That code is invalid or has expired. Run /discord link in-game again.");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to process Discord link code", e);
            replyEphemeral(event, "An internal error occurred. Please try again.");
        }
    }

    private void handleConsoleCommand(
            ChatInputInteractionEvent event,
            RigelConfig config,
            RigelMCMod plugin,
            DiscordLinkService linkService,
            RankService rankService,
            AuditLogService auditLogService) {
        String channelId = event.getInteraction().getChannelId().asString();
        if (!channelId.equals(config.discordAdminChannelId())) {
            replyEphemeral(event, "This command can only be used in the configured admin channel.");
            return;
        }

        String command = event.getOptionAsString(COMMAND_OPTION).orElse("").trim();
        String discordUserId = event.getInteraction().getUser().getId().asString();

        try {
            Optional<UUID> linkedUuid = linkService.resolveLinkedUuid(discordUserId);
            if (linkedUuid.isEmpty()) {
                replyEphemeral(event, "Your Discord account isn't linked - run /discord link in-game first.");
                return;
            }

            UUID uuid = linkedUuid.get();
            String minRank = config.discordConsoleCommandMinRank();
            if (!rankService.hasAtLeast(uuid, minRank)) {
                auditLogService.record(
                        uuid, "DISCORD_CONSOLE_DENIED", null, "discordUser=" + discordUserId + " cmd=" + command);
                replyEphemeral(event, "You do not have permission to run console commands.");
                return;
            }

            auditLogService.record(
                    uuid, "DISCORD_CONSOLE_EXEC", null, "discordUser=" + discordUserId + " cmd=" + command);
            Bukkit.getScheduler()
                    .runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            replyEphemeral(event, "Command dispatched: `" + command + "`");
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to process Discord console command attempt", e);
            replyEphemeral(event, "An internal error occurred. Please try again.");
        }
    }

    /** Ephemeral - only the invoking user sees the response, matching the old DM-only reply behavior. */
    private void replyEphemeral(ChatInputInteractionEvent event, String text) {
        event.reply(text)
                .withEphemeral(true)
                .subscribe(
                        v -> { },
                        error -> logger.log(Level.FINE, "Failed to send a Discord interaction reply", error));
    }
}
