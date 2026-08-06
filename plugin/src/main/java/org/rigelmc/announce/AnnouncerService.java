package org.rigelmc.announce;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Rotation state for the scheduled broadcaster - see docs/architecture.md's
 * {@code announce/} module. Deliberately plain Java (no Bukkit/Adventure types), so the
 * rotation logic itself is unit-testable without a live server; MiniMessage parsing and
 * actually broadcasting happen in {@link AnnounceModule}, which owns this instance.
 */
public final class AnnouncerService {

    private final List<String> messages;
    private int nextIndex;

    public AnnouncerService(@NotNull List<String> messages) {
        this.messages = List.copyOf(messages);
    }

    /**
     * @return the next message in rotation (wrapping around to the start), or empty if
     *     no messages are configured - advances rotation state as a side effect
     */
    @NotNull
    public synchronized Optional<String> nextMessage() {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        String message = messages.get(nextIndex % messages.size());
        nextIndex++;
        return Optional.of(message);
    }

    public boolean hasMessages() {
        return !messages.isEmpty();
    }
}
