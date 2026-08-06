package org.rigelmc.protect.area;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * Tab-completion over a live {@link ProtectAreaService}'s region names - the {@code
 * <name>}-argument equivalent of {@code command.PlayerSuggestions#ONLINE_PLAYERS}, but
 * instance-scoped (region names aren't a static Bukkit-global list the way online players
 * are, so this can't be a {@code static final} constant the way that one is).
 */
public final class AreaSuggestions {

    private AreaSuggestions() {}

    @NotNull
    public static SuggestionProvider<CommandSourceStack> forService(@NotNull ProtectAreaService service) {
        return (ctx, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (AreaRegion region : service.list()) {
                if (region.name().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(region.name());
                }
            }
            return builder.buildFuture();
        };
    }
}
