package org.rigelmc.protect.antigrief;

/**
 * Tracks whether one player has repeated the same chat message too many times within a
 * rolling window - a message identical (case-insensitively) to the last one, sent again
 * before the window resets, counts toward the streak; anything else resets it.
 */
final class RepeatTracker {

    private String lastMessage = "";
    private int repeatCount = 0;
    private long windowStart = 0;

    /** @return {@code true} if this message pushes the repeat streak over {@code maxRepeats} */
    boolean recordAndCheck(String message, long nowMillis, long windowMillis, int maxRepeats) {
        if (nowMillis - windowStart > windowMillis || !message.equalsIgnoreCase(lastMessage)) {
            lastMessage = message;
            repeatCount = 1;
            windowStart = nowMillis;
            return false;
        }
        repeatCount++;
        return repeatCount > maxRepeats;
    }
}
