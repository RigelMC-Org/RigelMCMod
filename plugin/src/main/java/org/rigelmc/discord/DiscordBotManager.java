package org.rigelmc.discord;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.DeferrableInteractionEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.TopLevelMessageComponent;
import discord4j.core.object.emoji.Emoji;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.punish.appeal.Appeal;
import org.rigelmc.punish.appeal.AppealService;
import org.rigelmc.punish.ban.Ban;
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
 * explicit channel check in {@link #handleConsoleCommand} - defense in depth for
 * the most security-sensitive path in this bridge.</p>
 *
 * <p><b>Full TFM-style console bridge</b> (user-requested), built from two pieces once
 * {@code discord.console-channel-id} is set:
 * <ul>
 *   <li>Every server console line (not just RigelMCMod's own, and not just via {@code
 *   /console}'s dispatched-command confirmation) is mirrored into that channel by {@link
 *   ServerLogAppender}, registered/deregistered here in {@link #start}/{@link #shutdown} -
 *   see that class's own javadoc for why it hooks Log4j2 directly rather than a
 *   {@code java.util.logging.Handler}.</li>
 *   <li>Any plain (non-command) message posted in that same channel by a linked, ranked
 *   user is itself dispatched as a console command - see {@link #handleConsoleChannelMessage}
 *   - toggleable via {@code discord.console-channel-executes-messages} (default on) as an
 *   escape hatch back to {@code /console}-only. The channel effectively <i>is</i> the
 *   server console at that point, output and all - no separate "command output" reply is
 *   sent, since the mirror above already shows it.</li>
 * </ul>
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
    private static final String APPEAL_APPROVE_PREFIX = "appeal-approve-";
    private static final String APPEAL_DENY_PREFIX = "appeal-deny-";

    private final Logger logger;

    private volatile GatewayDiscordClient client;
    private volatile TextChannel publicChannel;
    private volatile TextChannel adminChannel;
    private volatile TextChannel consoleChannel;
    private volatile TextChannel appealChannel;
    /**
     * Set via {@link #setAppealService} once {@code punish.appeal.AppealService} exists
     * (both are constructed in {@code RigelMCMod#initializeDataLayer}, this one first -
     * see that method) - a mutable field rather than a constructor dependency
     * specifically to avoid a circular dependency: {@code AppealService} itself already
     * needs a live {@code DiscordBotManager} reference to post a newly-submitted appeal.
     * {@code null} until then, or forever if the {@code appeal} module is disabled - every
     * caller below checks for that.
     */
    private volatile AppealService appealService;
    /** Non-null only while a dedicated console channel is configured and the bridge is connected - see {@link ServerLogAppender}. */
    private volatile ServerLogAppender consoleMirrorAppender;
    /** Same lifecycle as {@link #consoleMirrorAppender} - see {@link ConsoleRelayBatcher}. */
    private volatile ConsoleRelayBatcher consoleRelayBatcher;

    public DiscordBotManager(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void setAppealService(@NotNull AppealService appealService) {
        this.appealService = appealService;
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
                            handleMessage(
                                    event, config, plugin, permissionGate, linkService, rankService,
                                    auditLogService);
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
            connected.getEventDispatcher()
                    .on(ButtonInteractionEvent.class)
                    .subscribe(event -> {
                        try {
                            handleButtonInteraction(event, config, linkService, rankService);
                        } catch (RuntimeException e) {
                            logger.log(Level.WARNING, "Failed to handle a Discord button interaction", e);
                        }
                    });

            this.publicChannel = resolveChannel(connected, config.discordPublicChannelId());
            this.adminChannel = resolveChannel(connected, config.discordAdminChannelId());
            this.consoleChannel = resolveChannel(connected, config.discordConsoleChannelId());
            this.appealChannel = resolveChannel(connected, config.discordAppealChannelId());

            if (this.consoleChannel != null) {
                // Only registered once the channel actually resolved - see
                // ServerLogAppender's own javadoc for what this does and why it's a
                // Log4j2 appender rather than a java.util.logging.Handler.
                this.consoleRelayBatcher = new ConsoleRelayBatcher(this, logger);
                this.consoleMirrorAppender = ServerLogAppender.register(this, config.discordConsoleRelayMinLevel());
                logger.info("[Discord] Full console mirror active (min level "
                        + config.discordConsoleRelayMinLevel() + ").");
            }

            Long applicationId = connected.getRestClient().getApplicationId().block();
            if (applicationId != null) {
                registerCommands(connected, applicationId, config.discordCommandGuildId());
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
     * every connect) the {@code /link} and {@code /console} slash commands.
     *
     * <p><b>Global registration (default, {@code commandGuildId} blank) can take up to an
     * hour to actually appear</b> - a real, well-known Discord limitation, not a bug in
     * this bridge (a first-time "commands aren't showing up" report is almost always just
     * this). If {@code discord.command-guild-id} is set, commands are registered to that
     * one guild instead via {@code bulkOverwriteGuildApplicationCommand} - guild-scoped
     * commands propagate near-instantly, which is genuinely useful while actively testing,
     * at the cost of only working in that one guild. Switch back to blank (global) once
     * you're done iterating.</p>
     */
    private void registerCommands(GatewayDiscordClient connected, long applicationId, @NotNull String commandGuildId) {
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
                .description("Run a server console command (admin/console channel only, linked + ranked account required)")
                .dmPermission(false) // Discord-level enforcement on top of the in-code channel check
                .addOption(ApplicationCommandOptionData.builder()
                        .name(COMMAND_OPTION)
                        .description("The command to run, without a leading /")
                        .type(stringType)
                        .required(true)
                        .build())
                .build();

        List<ApplicationCommandRequest> commands = List.of(linkCommand, consoleCommand);
        long guildId = commandGuildId.isBlank() ? -1 : parseSnowflakeOrDefault(commandGuildId, -1);
        if (guildId != -1) {
            connected.getRestClient()
                    .getApplicationService()
                    .bulkOverwriteGuildApplicationCommand(applicationId, guildId, commands)
                    .subscribe(
                            data -> { },
                            error -> logger.log(Level.WARNING, "Failed to register Discord slash commands to guild "
                                    + commandGuildId, error),
                            () -> logger.info("Registered Discord slash commands: /link, /console (guild "
                                    + commandGuildId + " - should appear immediately)."));
            return;
        }
        if (!commandGuildId.isBlank()) {
            logger.warning("discord.command-guild-id '" + commandGuildId
                    + "' isn't a valid Discord guild id (should be numeric) - falling back to global registration.");
        }
        connected.getRestClient()
                .getApplicationService()
                .bulkOverwriteGlobalApplicationCommand(applicationId, commands)
                .subscribe(
                        data -> { },
                        error -> logger.log(Level.WARNING, "Failed to register Discord slash commands", error),
                        () -> logger.info("Registered Discord slash commands: /link, /console (global - can take"
                                + " up to an hour to appear; set discord.command-guild-id for instant testing)."));
    }

    /** @return the parsed snowflake, or {@code fallback} if {@code raw} isn't a valid numeric Discord id. */
    private static long parseSnowflakeOrDefault(String raw, long fallback) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    /**
     * User-requested: player join/leave posted to the public Discord channel (the
     * "server chat bridge") and, separately, to the admin channel (the "staff chat
     * bridge" - staff-only by virtue of who's actually in that channel, not an extra
     * filter here). {@code includePublic} is {@code false} for a vanished player's leave
     * (see {@code discord.JoinLeaveBridgeListener}) - the admin channel still gets it
     * either way, since staff should know when a vanished colleague disconnects.
     */
    public void relayJoinLeave(@NotNull String message, boolean includePublic) {
        String content = sanitize(message);
        if (includePublic && publicChannel != null) {
            publicChannel.createMessage(content).subscribe();
        }
        if (adminChannel != null) {
            adminChannel.createMessage(content).subscribe();
        }
    }

    /**
     * One-way: server log lines out to the console channel only, never accepts input
     * back. IP-redacted and sanitized (ANSI/legacy-color codes, mass mentions, length),
     * then handed to {@link #consoleRelayBatcher}, which groups it with whatever else
     * comes in over the next couple of seconds into a single fenced code-block message -
     * one Discord message per line would be both far spammier than TFM's own console
     * bridge (which this is explicitly modeled on - user-verified against a screenshot of
     * it) and an easy way to hit Discord's per-channel rate limit under any real console
     * volume.
     */
    public void relayConsoleLine(@NotNull String line) {
        ConsoleRelayBatcher batcher = this.consoleRelayBatcher;
        if (batcher != null) {
            batcher.enqueue(sanitize(redactIps(line)));
        }
    }

    /**
     * Sends one already-batched, already-sanitized block of one or more newline-joined
     * console lines as a single fenced code-block message - called only by {@link
     * ConsoleRelayBatcher#flush}, never directly.
     */
    void sendConsoleBlock(@NotNull String block) {
        if (consoleChannel != null) {
            consoleChannel.createMessage(codeBlock(block)).subscribe();
        }
    }

    /**
     * Fences {@code content} as a Discord code block. Escapes any literal {@code ```} in
     * the content itself (with a zero-width space, same neutering technique {@link
     * #sanitize} already uses for mentions) - unescaped, it would prematurely close the
     * block partway through and let the remainder render as normal, unfenced markdown
     * instead.
     */
    @NotNull
    private static String codeBlock(@NotNull String content) {
        String escaped = content.replace("```", "`​``");
        return "```\n" + escaped + "\n```";
    }

    /**
     * Posts a newly-submitted ban appeal with Approve/Deny buttons - called by {@code
     * AppealService#submit}. No-op (logged) if neither an appeal-specific nor a fallback
     * admin channel resolved, or if the Discord bridge isn't connected at all.
     */
    public void postAppeal(@NotNull Appeal appeal, @NotNull Ban ban) {
        TextChannel channel = appealChannel;
        if (channel == null) {
            logger.warning("Cannot post appeal #" + appeal.id() + " to Discord - no appeal or admin channel is"
                    + " configured/resolved (or the Discord bridge isn't connected).");
            return;
        }
        Button approveButton = Button.success(APPEAL_APPROVE_PREFIX + appeal.id(), "Approve");
        Button denyButton = Button.danger(APPEAL_DENY_PREFIX + appeal.id(), "Deny");
        channel.createMessage(buildAppealMessageContent(appeal, ban))
                .withComponents(ActionRow.of(approveButton, denyButton))
                .subscribe(
                        message -> persistDiscordMessageId(appeal.id(), message.getId().asString()),
                        error -> logger.log(
                                Level.WARNING, "Failed to post appeal #" + appeal.id() + " to Discord", error));
    }

    @NotNull
    private static String buildAppealMessageContent(Appeal appeal, Ban ban) {
        String target = ban.targetLastName() != null
                ? ban.targetLastName()
                : (ban.targetUuid() != null ? ban.targetUuid().toString() : "IP ban (" + ban.type() + ")");
        String expiry = ban.isPermanent() ? "Permanent" : Instant.ofEpochMilli(ban.expiresAt()).toString();
        StringBuilder sb = new StringBuilder()
                .append("**New ban appeal #").append(appeal.id()).append("**\n")
                .append("Target: ").append(sanitize(target)).append('\n')
                .append("Ban type: ").append(ban.type()).append(" | Expiry: ").append(expiry)
                .append(" | Reason: ").append(sanitize(ban.reason())).append('\n')
                .append("Appeal message: ").append(sanitize(appeal.message()));
        if (appeal.contact() != null) {
            sb.append("\nContact: ").append(sanitize(appeal.contact()));
        }
        return sb.toString();
    }

    private void persistDiscordMessageId(long appealId, String discordMessageId) {
        AppealService currentAppealService = this.appealService;
        if (currentAppealService == null) {
            return;
        }
        try {
            currentAppealService.setDiscordMessageId(appealId, discordMessageId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to persist Discord message id for appeal #" + appealId, e);
        }
    }

    // ---- ban-appeal decision buttons -----------------------------------------------------

    private void handleButtonInteraction(
            @NotNull ButtonInteractionEvent event, RigelConfig config, DiscordLinkService linkService,
            RankService rankService) {
        AppealService currentAppealService = this.appealService;
        if (currentAppealService == null) {
            return; // not one of our buttons (appeal module disabled) - nothing to do
        }

        String customId = event.getCustomId();
        boolean approve;
        String prefix;
        if (customId.startsWith(APPEAL_APPROVE_PREFIX)) {
            approve = true;
            prefix = APPEAL_APPROVE_PREFIX;
        } else if (customId.startsWith(APPEAL_DENY_PREFIX)) {
            approve = false;
            prefix = APPEAL_DENY_PREFIX;
        } else {
            return; // some other plugin's button, or an id shape we don't recognize
        }
        long appealId = parseAppealId(customId, prefix);
        if (appealId < 0) {
            return;
        }

        String discordUserId = event.getUser().getId().asString();
        try {
            Optional<UUID> linkedUuid = linkService.resolveLinkedUuid(discordUserId);
            if (linkedUuid.isEmpty()) {
                replyEphemeral(event, "Your Discord account isn't linked - run /discord link in-game first.");
                return;
            }
            UUID uuid = linkedUuid.get();
            if (!rankService.hasAtLeast(uuid, config.discordAppealDecisionMinRank())) {
                replyEphemeral(event, "You don't have permission to decide ban appeals.");
                return;
            }

            AppealService.DecisionResult result =
                    approve ? currentAppealService.approve(appealId, uuid) : currentAppealService.deny(appealId, uuid);
            switch (result) {
                case APPROVED, DENIED -> finalizeAppealMessage(event, approve, event.getUser().getUsername());
                case ALREADY_DECIDED -> replyEphemeral(event, "This appeal was already decided by someone else.");
                case NOT_FOUND -> replyEphemeral(event, "That appeal could not be found.");
                case BAN_MISSING -> replyEphemeral(event, "The underlying ban record for this appeal is missing.");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to process a Discord appeal decision", e);
            replyEphemeral(event, "An internal error occurred. Please try again.");
        }
    }

    /** Strips the buttons and appends the outcome to the original message - no separate reply. */
    private void finalizeAppealMessage(ButtonInteractionEvent event, boolean approved, String decidedByName) {
        String originalContent = event.getMessage().map(Message::getContent).orElse("");
        String suffix = (approved ? "\n\n✅ **Approved" : "\n\n❌ **Denied") + " by "
                + sanitize(decidedByName) + "**";
        event.edit()
                .withComponents(List.<TopLevelMessageComponent>of())
                .withContent(originalContent + suffix)
                .subscribe(
                        v -> { },
                        error -> logger.log(Level.WARNING, "Failed to update a decided appeal message", error));
    }

    private static long parseAppealId(String customId, String prefix) {
        try {
            return Long.parseLong(customId.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void shutdown() {
        ServerLogAppender appender = this.consoleMirrorAppender;
        if (appender != null) {
            // Must happen before logout - this appender lives on the server-wide,
            // JVM-lifetime Log4j2 LoggerContext (unlike a plugin-scoped
            // java.util.logging.Handler), so a disable without this leaks a second
            // instance on the next enable within the same JVM and doubles every line.
            // Deregistered first (before the batcher shuts down) so nothing new gets
            // enqueued while the batcher is flushing whatever's left.
            appender.deregister();
            this.consoleMirrorAppender = null;
        }
        ConsoleRelayBatcher batcher = this.consoleRelayBatcher;
        if (batcher != null) {
            batcher.shutdown(); // best-effort final flush, before logout below disconnects
            this.consoleRelayBatcher = null;
        }
        GatewayDiscordClient connected = this.client;
        if (connected != null) {
            // Bounded, unlike JDA's fire-and-forget shutdown() - a hung network call
            // here must not hold up the rest of the plugin's onDisable().
            connected.logout().block(Duration.ofSeconds(5));
        }
    }

    /** ANSI CSI escape sequences (e.g. terminal color codes) - Paper's console output is ANSI-colorized by default, and plenty of plugins also color their own log lines directly. */
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*[a-zA-Z]");
    /** Legacy Minecraft formatting codes (e.g. {@code §b}, {@code §l}) - some plugins log with these embedded directly instead of/alongside ANSI codes. */
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");
    /**
     * IPv4 dotted-quad, with an optional leading "/" (Bukkit's raw join-log bracket
     * format, e.g. {@code PlayerName[/1.2.3.4:25565] logged in with entity id ...}) and
     * optional trailing {@code :port}. IPv6 deliberately not covered - a heuristic loose
     * enough to reliably catch it would also false-positive on ordinary colon-separated
     * text (this bridge's own {@code [HH:mm:ss LEVEL]:} line prefix looks exactly like a
     * plausible short IPv6 literal to a naive pattern) - not worth that risk given IPv4 is
     * what actually shows up on the overwhelming majority of real Minecraft server setups.
     */
    private static final Pattern IPV4_PATTERN = Pattern.compile("/?\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b(?::[0-9]{1,5})?");

    /**
     * Strips Discord markdown control characters/mass-mention vectors from relayed text,
     * plus (user-reported, screenshot-verified: raw {@code ESC[90m}-style sequences
     * leaking through as literal garbage in Discord) terminal ANSI color codes and legacy
     * {@code §}-formatting codes - neither means anything to Discord, which doesn't
     * interpret either, so left in they just render as junk characters in the relayed
     * console lines.
     */
    @NotNull
    private static String sanitize(String input) {
        String noAnsi = ANSI_PATTERN.matcher(input).replaceAll("");
        String noLegacyColor = LEGACY_COLOR_PATTERN.matcher(noAnsi).replaceAll("");
        String noMentions = noLegacyColor.replace("@everyone", "@​everyone").replace("@here", "@​here");
        return noMentions.length() > 1800 ? noMentions.substring(0, 1800) + "..." : noMentions;
    }

    /**
     * User-requested ("similar to TFM"): redacts every IPv4 address in a console line
     * before it's relayed to Discord, e.g. Bukkit's own join-log line {@code
     * PlayerName[/1.2.3.4:25565] logged in with entity id ...} becomes {@code
     * PlayerName[IP hidden] logged in with entity id ...} - the exact wording TFM's own
     * console bridge uses (screenshot-verified). Console-relay only (see {@link
     * #relayConsoleLine}), never applied to admin/public chat relay - a player's own
     * IPv4-shaped chat message isn't the same kind of sensitive data a join-log line's
     * actual connection IP is, and this is scoped to exactly what was asked for ("redact
     * any IP address of any connecting player").
     */
    @NotNull
    private static String redactIps(String line) {
        return IPV4_PATTERN.matcher(line).replaceAll("IP hidden");
    }

    // ---- plain chat relay (public/admin channel messages -> in-game) -------------------

    private void handleMessage(
            MessageCreateEvent event,
            RigelConfig config,
            RigelMCMod plugin,
            PermissionGate permissionGate,
            DiscordLinkService linkService,
            RankService rankService,
            AuditLogService auditLogService) {
        Message message = event.getMessage();
        Optional<User> authorOpt = message.getAuthor();
        if (authorOpt.isEmpty() || authorOpt.get().isBot()) {
            return; // empty = webhook/system message, not a real user - also keeps this
            // bridge's own replies/reactions in the console channel from ever being
            // re-read as a new command below
        }
        if (event.getGuildId().isEmpty()) {
            return; // DMs - no message-based commands anymore, see /link
        }
        User author = authorOpt.get();
        String channelId = message.getChannelId().asString();
        String content = message.getContent();
        String consoleChannelId = config.discordConsoleChannelId();

        if (config.discordConsoleChannelExecutesMessages()
                && !consoleChannelId.isBlank()
                && channelId.equals(consoleChannelId)) {
            // Deliberately only when a *dedicated* console channel is explicitly
            // configured (never falls back to the admin channel like /console below can)
            // - the admin channel doubles as staff chat via relayAdminChannelToGame, and
            // treating every casual staff message there as a console command to dispatch
            // would be actively dangerous (e.g. someone typing "stop" in conversation).
            handleConsoleChannelMessage(
                    message, config, plugin, linkService, rankService, auditLogService, author, content);
            return;
        }

        if (channelId.equals(config.discordAdminChannelId())) {
            relayAdminChannelToGame(plugin, permissionGate, author, content);
        } else if (channelId.equals(config.discordPublicChannelId())) {
            relayPublicChannelToGame(plugin, author, content);
        }
    }

    /**
     * TFM-style: any plain message in the dedicated console channel from a linked, ranked
     * user is itself the command, dispatched verbatim - no {@code /console} needed once
     * you're in that channel. Reacts ✅/❌ on the message itself for a lightweight
     * outcome signal rather than posting a whole new reply (which the mirror registered
     * in {@link #connectBlocking} would show anyway once it actually runs).
     */
    private void handleConsoleChannelMessage(
            Message message,
            RigelConfig config,
            RigelMCMod plugin,
            DiscordLinkService linkService,
            RankService rankService,
            AuditLogService auditLogService,
            User author,
            String content) {
        if (content.isBlank()) {
            return; // e.g. an image-only/embed-only post - nothing to run
        }
        String discordUserId = author.getId().asString();
        try {
            ConsoleExecResult result = executeConsoleCommandIfAllowed(
                    discordUserId, content, config, plugin, linkService, rankService, auditLogService);
            react(message, result == ConsoleExecResult.EXECUTED ? "✅" : "❌");
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to process a console-channel message as a command", e);
            react(message, "❌");
        }
    }

    private void react(Message message, String unicodeEmoji) {
        message.addReaction(Emoji.unicode(unicodeEmoji))
                .subscribe(v -> { }, error -> logger.log(Level.FINE, "Failed to react to a Discord message", error));
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
        // Admin channel (backward-compatible - this predates the dedicated console
        // channel concept) or the dedicated console channel, if one is configured.
        if (!channelId.equals(config.discordAdminChannelId()) && !channelId.equals(config.discordConsoleChannelId())) {
            replyEphemeral(event, "This command can only be used in the configured admin or console channel.");
            return;
        }

        String command = event.getOptionAsString(COMMAND_OPTION).orElse("").trim();
        String discordUserId = event.getInteraction().getUser().getId().asString();

        try {
            ConsoleExecResult result = executeConsoleCommandIfAllowed(
                    discordUserId, command, config, plugin, linkService, rankService, auditLogService);
            switch (result) {
                case EXECUTED -> replyEphemeral(event, "Command dispatched: `" + command + "`");
                case NOT_LINKED -> replyEphemeral(
                        event, "Your Discord account isn't linked - run /discord link in-game first.");
                case INSUFFICIENT_RANK -> replyEphemeral(event, "You do not have permission to run console commands.");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to process Discord console command attempt", e);
            replyEphemeral(event, "An internal error occurred. Please try again.");
        }
    }

    private enum ConsoleExecResult { EXECUTED, NOT_LINKED, INSUFFICIENT_RANK }

    /**
     * Shared by {@link #handleConsoleCommand} ({@code /console}) and {@link
     * #handleConsoleChannelMessage} (plain messages in the dedicated console channel) -
     * same linked-account + min-rank gate, same audit trail either way, only the
     * command's origin differs.
     */
    private ConsoleExecResult executeConsoleCommandIfAllowed(
            String discordUserId,
            String command,
            RigelConfig config,
            RigelMCMod plugin,
            DiscordLinkService linkService,
            RankService rankService,
            AuditLogService auditLogService)
            throws SQLException {
        Optional<UUID> linkedUuid = linkService.resolveLinkedUuid(discordUserId);
        if (linkedUuid.isEmpty()) {
            return ConsoleExecResult.NOT_LINKED;
        }

        UUID uuid = linkedUuid.get();
        if (!rankService.hasAtLeast(uuid, config.discordConsoleCommandMinRank())) {
            auditLogService.record(
                    uuid, "DISCORD_CONSOLE_DENIED", null, "discordUser=" + discordUserId + " cmd=" + command);
            return ConsoleExecResult.INSUFFICIENT_RANK;
        }

        auditLogService.record(
                uuid, "DISCORD_CONSOLE_EXEC", null, "discordUser=" + discordUserId + " cmd=" + command);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        return ConsoleExecResult.EXECUTED;
    }

    /**
     * Ephemeral - only the invoking user sees the response, matching the old DM-only reply
     * behavior. Takes the common {@link DeferrableInteractionEvent} supertype (not just
     * {@link ChatInputInteractionEvent}) specifically so {@link ButtonInteractionEvent}'s
     * decision-rejection replies can reuse this same method.
     */
    private void replyEphemeral(DeferrableInteractionEvent event, String text) {
        event.reply(text)
                .withEphemeral(true)
                .subscribe(
                        v -> { },
                        error -> logger.log(Level.FINE, "Failed to send a Discord interaction reply", error));
    }
}
