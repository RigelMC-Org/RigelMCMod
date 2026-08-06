package org.rigelmc.tag;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.chat.PlayerDisplayService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;

/**
 * {@code /tag set <text>} / {@code /tag clear} - see {@link TagService}. Fully
 * synchronous: unlike almost everything else in this codebase, tags are session-only
 * and never touch the database, so there's no async dispatch needed here at all.
 */
public final class TagModule implements PluginModule {

    private final TagService tagService;
    private final PlayerDisplayService displayService;

    public TagModule(@NotNull TagService tagService, @NotNull PlayerDisplayService displayService) {
        this.tagService = tagService;
        this.displayService = displayService;
    }

    @Override
    public String id() {
        return "tag";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        // no listeners of its own - display application goes through PlayerDisplayService
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(tagCommand(), "Set a personal tag shown as a prefix before your name in chat");
    }

    private LiteralCommandNode<CommandSourceStack> tagCommand() {
        return Commands.literal("tag")
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/tag set <text> | /tag clear"))
                .then(Commands.literal("clear").executes(this::executeClear))
                .then(Commands.literal("set")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/tag set <text>"))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(this::executeSet)))
                .build();
    }

    private int executeSet(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String rawTag = StringArgumentType.getString(ctx, "text");

        Optional<String> error = TagService.validate(rawTag);
        if (error.isPresent()) {
            player.sendMessage(Component.text(error.get(), NamedTextColor.RED));
            return 0;
        }

        tagService.set(player.getUniqueId(), rawTag);
        displayService.apply(player);
        player.sendMessage(Component.text("Tag set for this session.", NamedTextColor.GREEN));
        return 1;
    }

    private int executeClear(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        tagService.clear(player.getUniqueId());
        displayService.apply(player);
        player.sendMessage(Component.text("Tag cleared.", NamedTextColor.GREEN));
        return 1;
    }
}
