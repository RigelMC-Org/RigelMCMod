package org.rigelmc.protect;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What happens when a command-access rule matches and the sender doesn't meet its
 * required rank - mirrors TFM's {@code CommandBlockerAction} exactly (same three
 * actions, same single-letter tokens).
 */
public enum CommandAccessAction {

    /** Cancel the command and show the rule's message. */
    BLOCK("b"),
    /** Cancel the command, show the message, and kick the sender (if a player). */
    KICK("k"),
    /** Cancel the command and show Minecraft's generic "unknown command" message - hides that it exists at all. */
    UNKNOWN("u");

    private final String token;

    CommandAccessAction(String token) {
        this.token = token;
    }

    @Nullable
    public static CommandAccessAction fromToken(@NotNull String token) {
        for (CommandAccessAction action : values()) {
            if (action.token.equalsIgnoreCase(token)) {
                return action;
            }
        }
        return null;
    }
}
