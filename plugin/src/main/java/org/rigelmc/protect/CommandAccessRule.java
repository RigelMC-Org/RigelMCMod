package org.rigelmc.protect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One parsed {@code protect.command-access} rule - mirrors TFM's own
 * {@code CommandBlockerEntry} (same argument-pattern matching semantics), adapted to
 * RigelMCMod's rank ladder. See {@code CommandAccessRegistry}'s javadoc for the full
 * {@code rank:action:/command args:message} config syntax.
 */
public final class CommandAccessRule {

    /** {@code null} required rank means "nobody" - blocked for everyone, see {@link CommandAccessRegistry#NOBODY}. */
    private final String requiredRankId;
    private final CommandAccessAction action;
    private final String baseCommand;
    private final List<PatternToken> argPattern;
    private final boolean hasWildcard;
    private final Component message;

    CommandAccessRule(
            @Nullable String requiredRankId,
            @NotNull CommandAccessAction action,
            @NotNull String baseCommand,
            @NotNull List<PatternToken> argPattern,
            @Nullable String rawMessage) {
        this.requiredRankId = requiredRankId;
        this.action = action;
        this.baseCommand = baseCommand;
        this.argPattern = List.copyOf(argPattern);
        boolean wildcard = false;
        for (PatternToken token : this.argPattern) {
            if (token.type() != TokenType.LITERAL) {
                wildcard = true;
                break;
            }
        }
        this.hasWildcard = wildcard;
        String text = rawMessage == null || rawMessage.equals("_") ? "That command is blocked." : rawMessage;
        this.message = LegacyComponentSerializer.legacyAmpersand().deserialize("&7" + text);
    }

    @Nullable
    public String requiredRankId() {
        return requiredRankId;
    }

    @NotNull
    public CommandAccessAction action() {
        return action;
    }

    @NotNull
    public String baseCommand() {
        return baseCommand;
    }

    @NotNull
    public Component message() {
        return message;
    }

    /** How many pattern tokens this rule specifies - used to sort most-specific-first, see TFM's {@code MOST_SPECIFIC_FIRST}. */
    public int specificity() {
        int literalCount = 0;
        for (PatternToken token : argPattern) {
            if (token.type() == TokenType.LITERAL) {
                literalCount++;
            }
        }
        // Literal tokens matter most (an exact sub-argument match beats a wildcard one),
        // then total token count as a tiebreaker - identical ordering logic to TFM's
        // literalTokenCount() then patternTokenCount() comparator.
        return literalCount * 1000 + argPattern.size();
    }

    /** @return {@code true} if this rule's argument pattern matches the player's actual command arguments */
    public boolean matches(@NotNull String[] inputArgs) {
        if (!hasWildcard) {
            if (argPattern.isEmpty()) {
                return true; // bare base command, no sub-arguments specified - matches any/no args
            }
            if (inputArgs.length < argPattern.size()) {
                return false;
            }
            for (int i = 0; i < argPattern.size(); i++) {
                if (!argPattern.get(i).value().equals(inputArgs[i])) {
                    return false;
                }
            }
            return true;
        }
        // {?} = exactly one (any) argument at that position; trailing {*} = one or more
        // remaining arguments. Same semantics as TFM's CommandBlockerEntry#matches.
        for (int i = 0; i < argPattern.size(); i++) {
            PatternToken token = argPattern.get(i);
            if (token.type() == TokenType.MULTI) {
                return inputArgs.length > i;
            }
            if (i >= inputArgs.length) {
                return false;
            }
            if (token.type() == TokenType.LITERAL && !token.value().equals(inputArgs[i])) {
                return false;
            }
        }
        return inputArgs.length == argPattern.size();
    }

    private enum TokenType {
        LITERAL, SINGLE, MULTI
    }

    private record PatternToken(TokenType type, String value) {
        static PatternToken of(String raw) {
            String trimmed = raw.trim().toLowerCase(Locale.ROOT);
            if (trimmed.equals("{*}")) {
                return new PatternToken(TokenType.MULTI, null);
            }
            if (trimmed.equals("{?}")) {
                return new PatternToken(TokenType.SINGLE, null);
            }
            return new PatternToken(TokenType.LITERAL, trimmed);
        }
    }

    /**
     * Parses one raw {@code rank:action:/command args:message} config line.
     *
     * @return the parsed rule, or {@code null} if malformed (logged by the caller)
     */
    @Nullable
    static CommandAccessRule parse(@NotNull String rawEntry, @NotNull java.util.function.Consumer<String> onWarning) {
        String[] parts = rawEntry.split(":", 4);
        if (parts.length < 3) {
            onWarning.accept("Invalid command-access entry (expected rank:action:/command[:message]): " + rawEntry);
            return null;
        }

        String rankToken = parts[0];
        CommandAccessAction action = CommandAccessAction.fromToken(parts[1]);
        String commandSpec = parts[2].toLowerCase(Locale.ROOT);
        if (commandSpec.startsWith("/")) {
            commandSpec = commandSpec.substring(1);
        }
        String message = parts.length > 3 ? parts[3] : null;

        if (action == null || commandSpec.isEmpty()) {
            onWarning.accept("Invalid command-access entry: " + rawEntry);
            return null;
        }

        String requiredRankId = CommandAccessRegistry.rankIdFromToken(rankToken);
        if (requiredRankId == null && !"n".equalsIgnoreCase(rankToken)) {
            onWarning.accept("Invalid command-access entry - unknown rank token '" + rankToken + "': " + rawEntry);
            return null;
        }

        String[] specParts = commandSpec.split("\\s+");
        String baseCommand = specParts[0];
        List<PatternToken> pattern = new ArrayList<>();
        for (int i = 1; i < specParts.length; i++) {
            if (specParts[i].isEmpty()) {
                continue;
            }
            PatternToken token = PatternToken.of(specParts[i]);
            if (token.type() == TokenType.MULTI && i != specParts.length - 1) {
                onWarning.accept("Invalid command-access entry ('{*}' may only appear as the last token): " + rawEntry);
                return null;
            }
            pattern.add(token);
        }

        return new CommandAccessRule(requiredRankId, action, baseCommand, pattern, message);
    }

    @NotNull
    static Component defaultDeniedMessage() {
        return Component.text("You do not have permission to run that command.", NamedTextColor.RED);
    }
}
