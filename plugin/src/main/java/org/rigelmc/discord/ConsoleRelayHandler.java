package org.rigelmc.discord;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.jetbrains.annotations.NotNull;

/**
 * Forwards RigelMCMod's own log output to the Discord console channel - see
 * docs/architecture.md "Discord bridge & admin chat". Deliberately attached only to
 * {@code plugin.getLogger()}, <b>not</b> the server's root/global logger: mirroring the
 * entire server console is a much bigger, riskier feature (feedback-loop risk from
 * Discord4J/Reactor's own network logging, log-injection formatting concerns, no natural
 * filtering), so this
 * ships a narrower, safer, still genuinely useful slice - RigelMCMod's own startup/
 * warning/error lines - rather than overclaiming full console mirroring. Widening this
 * to the whole server console is a possible future enhancement, not something this
 * implementation does.
 */
public final class ConsoleRelayHandler extends Handler {

    private final DiscordBotManager botManager;

    public ConsoleRelayHandler(@NotNull DiscordBotManager botManager) {
        this.botManager = botManager;
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record) || !botManager.isReady()) {
            return;
        }
        String line = "[" + record.getLevel() + "] " + record.getMessage();
        botManager.relayConsoleLine(line);
    }

    @Override
    public void flush() {
        // no-op - relayConsoleLine is fire-and-forget via Discord4J's own reactive pipeline
    }

    @Override
    public void close() {
        // no-op - nothing owned by this handler needs releasing
    }
}
