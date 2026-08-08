package org.rigelmc.discord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.jetbrains.annotations.NotNull;

/**
 * TFM-style full-server console mirror to Discord - user-requested ("the same as TFM, I
 * want a full console discord bridge"), replacing the previous {@code ConsoleRelayHandler}
 * (a {@code java.util.logging.Handler} attached only to {@code plugin.getLogger()}, so it
 * only ever saw RigelMCMod's own log lines, deliberately not the rest of the server - see
 * that class's own now-superseded javadoc for the original scoping rationale).
 *
 * <p>This is a Log4j2 {@link AbstractAppender} registered directly on the server's root
 * {@code LoggerConfig}, not a {@code java.util.logging.Handler} - that distinction matters
 * on Paper specifically: a meaningful share of vanilla/engine console output (chunk
 * loading, connection handling, "Can't keep up!", etc.) is logged straight through Log4j2
 * loggers, never bridged through {@code java.util.logging} at all. A JUL {@code Handler},
 * even one attached to the JUL root logger, would only ever see whichever subsystems
 * happen to log via a JUL {@code Logger} (i.e. plugins using {@code getLogger()}) and miss
 * that vanilla/engine output entirely. Hooking Log4j2's own root logger config directly is
 * the actually-reliable mechanism for genuine full-console coverage on this platform - the
 * same approach established console-bridge plugins in this ecosystem use, for the same
 * reason. Verified against the real Log4j2 2.26.1 core API shape via direct bytecode
 * inspection this session (not assumed - matches this project's "confirm the API before
 * use" discipline for new integration surface, e.g. the Discord4J button work), but not
 * exercised against a live server's actual logging pipeline.</p>
 *
 * <p>Registered/deregistered by {@link DiscordBotManager#start}/{@link
 * DiscordBotManager#shutdown} via {@link #register}/{@link #deregister}, tied to the
 * bridge's own connect/disconnect lifecycle. This matters in a way it didn't for the old
 * plugin-logger-scoped handler: that one was naturally garbage-collected along with the
 * plugin on disable, but this appender lives on the server-wide, JVM-lifetime {@code
 * LoggerContext} - a plugin disable/re-enable within the same JVM without an explicit
 * {@link #deregister} would leak a second instance and double every relayed line from
 * then on.</p>
 *
 * <p>Excludes Discord4J's/Reactor's/Netty's own logger names ({@link
 * #EXCLUDED_LOGGER_PREFIXES}) - without this, this appender's own outbound Discord API
 * calls would generate log lines that get relayed right back to Discord, triggering more
 * API calls and more log lines: a genuine feedback-loop risk unique to hooking the literal
 * root logger (the old plugin-scoped handler couldn't hit this, since Discord4J never logs
 * through RigelMCMod's own logger).</p>
 */
final class ServerLogAppender extends AbstractAppender {

    private static final String APPENDER_NAME = "RigelMCMod-DiscordConsoleMirror";
    private static final String[] EXCLUDED_LOGGER_PREFIXES = {
        "discord4j", "reactor", "io.netty", "org.reactivestreams",
    };
    /** {@code HH:mm:ss}, server-local time - matches TFM's own console-bridge line format ({@code [09:50:05 INFO]:}). */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DiscordBotManager botManager;
    private final Level minLevel;

    private ServerLogAppender(@NotNull DiscordBotManager botManager, @NotNull Level minLevel) {
        super(APPENDER_NAME, null, null, true);
        this.botManager = botManager;
        this.minLevel = minLevel;
    }

    /**
     * Builds, starts, and registers a new instance on the server's root Log4j2 logger
     * config. {@code minLevelName} (from {@code discord.console-relay-min-level}) is
     * parsed via {@link Level#toLevel(String, Level)}, which already defaults to
     * {@code INFO} for anything unrecognized rather than failing to register at all.
     */
    @NotNull
    static ServerLogAppender register(@NotNull DiscordBotManager botManager, @NotNull String minLevelName) {
        ServerLogAppender appender = new ServerLogAppender(botManager, Level.toLevel(minLevelName, Level.INFO));
        appender.start();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, null, null);
        context.updateLoggers();
        return appender;
    }

    /** Reverses {@link #register} - removes this appender from the root logger config and stops it. */
    void deregister() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getConfiguration().getRootLogger().removeAppender(getName());
        context.updateLoggers();
        stop();
    }

    @Override
    public void append(LogEvent event) {
        if (!botManager.isReady()) {
            return;
        }
        Level level = event.getLevel();
        if (level == null || level.intLevel() > minLevel.intLevel()) {
            return; // less severe than the configured minimum (lower intLevel = more severe)
        }
        String loggerName = event.getLoggerName();
        if (loggerName != null) {
            for (String prefix : EXCLUDED_LOGGER_PREFIXES) {
                if (loggerName.startsWith(prefix)) {
                    return;
                }
            }
        }
        String message = event.getMessage() != null ? event.getMessage().getFormattedMessage() : "";
        String time = TIME_FORMAT.format(Instant.ofEpochMilli(event.getTimeMillis()));
        botManager.relayConsoleLine("[" + time + " " + level + "]: " + message);
    }
}
