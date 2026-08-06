package org.rigelmc.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Shared tab-completion suggestion providers for {@code <player>}-style arguments.
 * RigelMCMod's commands take a plain {@link com.mojang.brigadier.arguments.StringArgumentType}
 * for player-name arguments rather than Paper's built-in player-selector argument type
 * (deliberately - it keeps every command's execute method dealing with a plain online-name
 * lookup, matching the rest of the codebase's `Bukkit.getPlayerExact(name)` pattern, rather
 * than a `PlayerSelectorArgumentResolver`), which on its own gives Brigadier no idea what to
 * suggest while typing. Attaching {@link #ONLINE_PLAYERS} via {@code .suggests(...)} restores
 * the usual online-name tab-completion without changing the argument type or any execute
 * method.
 */
public final class PlayerSuggestions {

    /** Suggests every currently online player's name, filtered by what's typed so far. */
    public static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS = (ctx, builder) -> {
        String remaining = builder.getRemainingLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(player.getName());
            }
        }
        return builder.buildFuture();
    };

    private PlayerSuggestions() {}
}
