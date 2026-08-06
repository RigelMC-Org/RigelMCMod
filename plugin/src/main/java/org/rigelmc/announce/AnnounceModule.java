package org.rigelmc.announce;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * Scheduled rotating broadcaster ({@code announce.broadcast.messages}) plus a
 * {@code /announce} one-off command - see docs/architecture.md's {@code announce/}
 * module. "Configurable with colors" is MiniMessage: both the configured rotation
 * messages and {@code /announce}'s argument are parsed as MiniMessage, so operators can
 * use tags like {@code <red>}, {@code <bold>}, {@code <gradient:blue:aqua>}, etc.
 */
public final class AnnounceModule implements PluginModule {

    private final PermissionGate permissionGate;
    private AnnouncerService announcerService;

    public AnnounceModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "announce";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        RigelConfig config = plugin.rigelConfig();
        this.announcerService = new AnnouncerService(config.announceMessages());

        if (!config.announceEnabled()) {
            return;
        }
        if (!announcerService.hasMessages()) {
            plugin.getLogger()
                    .info("announce module is enabled but announce.broadcast.messages is empty - nothing to say.");
            return;
        }

        long periodTicks = Math.max(config.announceInterval().toSeconds() * 20, 20);
        Bukkit.getScheduler()
                .runTaskTimer(
                        plugin,
                        () -> announcerService
                                .nextMessage()
                                .ifPresent(message -> broadcast(config.announcePrefix() + message)),
                        periodTicks,
                        periodTicks);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(announceTree("announce"), "Broadcast a message to the whole server");
        // A separate independent top-level command, not a Brigadier alias - a plain
        // alias isn't guaranteed to win a bare-label collision against an
        // already-registered Essentials command of the same name (confirmed the hard
        // way for /nick - see nick.NickModule's javadoc for the full story), and
        // Essentials ships its own /broadcast. Registering a second real command node
        // is the same fix applied there.
        registrar.register(announceTree("broadcast"), "Alias of /announce");
    }

    private LiteralCommandNode<CommandSourceStack> announceTree(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> !(source.getSender() instanceof Player player)
                        || permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator"))
                .executes(ctx -> org.rigelmc.command.CommandUsage.show(
                        ctx.getSource().getSender(), "/" + rootLiteral + " <message>"))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            broadcast(StringArgumentType.getString(ctx, "message"));
                            return 1;
                        }))
                .build();
    }

    private static void broadcast(String miniMessageText) {
        Component component = MiniMessage.miniMessage().deserialize(miniMessageText);
        Bukkit.broadcast(component);
    }
}
