package org.rigelmc.discord;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Batches console lines into one Discord message per flush interval instead of one
 * message per line - user-reported (screenshot comparison against TFM's own bot): TFM
 * groups several lines into a single fenced code block per message, not a message-per-line
 * flood. Beyond matching that look, this also keeps a busy console well clear of Discord's
 * per-channel rate limit (5 message sends per 5 seconds) - a full-server mirror can easily
 * produce far more than 1 line/second under normal load.
 *
 * <p>{@link #enqueue} is called from {@link ServerLogAppender#append}, which - being a
 * Log4j2 appender hooked on the root logger - can run on <b>any</b> thread that ever logs
 * anything, up to and including the main server thread. It must stay cheap and
 * non-blocking: this only ever adds to an in-memory queue, never touches the network
 * itself. The actual Discord send happens later, off a dedicated single-thread scheduler,
 * when {@link #flush} fires.</p>
 */
final class ConsoleRelayBatcher {

    private static final long FLUSH_INTERVAL_MILLIS = 2000;
    /** Leaves headroom under Discord's 2000-char message cap for the ```\n...\n``` fence added by the caller. */
    private static final int MAX_BLOCK_CHARS = 1900;

    private final DiscordBotManager botManager;
    private final Deque<String> pending = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler;

    ConsoleRelayBatcher(@NotNull DiscordBotManager botManager, @NotNull Logger logger) {
        this.botManager = botManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RigelMCMod-ConsoleRelayBatcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                flush();
            } catch (RuntimeException e) {
                // A scheduleAtFixedRate task that ever lets an exception escape stops
                // running permanently - same failure shape as the Discord4J reactive
                // subscriptions elsewhere in this bridge, same fix: catch and log.
                logger.log(Level.WARNING, "Failed to flush batched console lines to Discord", e);
            }
        }, FLUSH_INTERVAL_MILLIS, FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Cheap and non-blocking - see class javadoc on why that matters here specifically. */
    synchronized void enqueue(@NotNull String line) {
        pending.addLast(line);
    }

    /** Sends everything queued since the last flush as one or more code-block messages, each under Discord's size cap. */
    private void flush() {
        StringBuilder block = new StringBuilder();
        synchronized (this) {
            while (!pending.isEmpty()) {
                String line = pending.peekFirst();
                int extra = (block.length() > 0 ? 1 : 0) + line.length();
                if (block.length() > 0 && block.length() + extra > MAX_BLOCK_CHARS) {
                    botManager.sendConsoleBlock(block.toString());
                    block.setLength(0);
                    continue;
                }
                if (block.length() > 0) {
                    block.append('\n');
                }
                block.append(line);
                pending.pollFirst();
            }
        }
        if (block.length() > 0) {
            botManager.sendConsoleBlock(block.toString());
        }
    }

    /** Stops the flush timer and sends anything still queued - best-effort, called on bridge shutdown. */
    void shutdown() {
        scheduler.shutdown();
        flush();
    }
}
