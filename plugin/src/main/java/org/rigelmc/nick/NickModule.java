package org.rigelmc.nick;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.chat.PlayerDisplayService;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /nickname <name>} (also {@code /nick}) / {@code /nickname clear [player]} - see
 * {@link NickService}. The online-player-name collision check (can't nick yourself to
 * look exactly like someone else currently online) lives here rather than in
 * {@link NickService} since it's inherently a live-server check.
 *
 * <p>{@code /nick} is registered as its own separate top-level command (see
 * {@link #contributeCommands}), not as a Brigadier alias of {@code /nickname} - a plain
 * alias lost out to Essentials' own {@code /nick} in practice (Essentials loads {@code
 * BEFORE} RigelMCMod and claims the bare "nick" label first; Brigadier's alias mechanism
 * didn't reliably reclaim it). Registering a second, independent literal command node
 * unconditionally is the same fix already used for {@code /ban}/{@code /unban}/{@code
 * /mute}/{@code /kick} shadowing Essentials - see {@code punish.PunishModule}'s matching
 * comments.</p>
 */
public final class NickModule implements PluginModule {

    private final NickService nickService;
    private final PlayerDisplayService displayService;
    private final PermissionGate permissionGate;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public NickModule(
            @NotNull NickService nickService,
            @NotNull PlayerDisplayService displayService,
            @NotNull PermissionGate permissionGate,
            @NotNull ExecutorService dbExecutor) {
        this.nickService = nickService;
        this.displayService = displayService;
        this.permissionGate = permissionGate;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "nick";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(nickTree("nickname"), "Set a nickname shown in place of your real name");
        // Unconditional, not gated behind EssentialsCollisionPolicy - deliberately
        // shadows Essentials' own /nick, exactly like /ban, /mute, and /kick already do
        // (see the naming table in docs/architecture.md) - see this class's javadoc for
        // why this is a second independent command rather than a Brigadier alias.
        registrar.register(nickTree("nick"), "Alias of /nickname");
    }

    private LiteralCommandNode<CommandSourceStack> nickTree(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> org.rigelmc.command.CommandUsage.show(
                        ctx.getSource().getSender(), "/nickname <name> | /nickname clear"))
                .then(Commands.literal("clear")
                        .executes(ctx -> executeClear((Player) ctx.getSource().getSender()))
                        // Staff override: /nickname clear <player> forces off someone else's
                        // nickname (TFM's Command_denick concept, folded into this tree rather
                        // than a separate command name).
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(org.rigelmc.command.PlayerSuggestions.ONLINE_PLAYERS)
                                .requires(source -> source.getSender() instanceof Player player
                                        && permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator"))
                                .executes(this::executeClearOther)))
                // greedyString, not word() - a bare word() argument can't contain '&', so
                // Brigadier rejected any attempt to include a color code before it ever
                // reached NickService's own (already color-code-aware) validation -
                // confirmed the hard way via a real "expected whitespace to end one
                // argument" parse error in testing.
                .then(Commands.argument("nickname", StringArgumentType.greedyString()).executes(this::executeSet))
                .build();
    }

    private int executeSet(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String rawNick = StringArgumentType.getString(ctx, "nickname");

        Optional<String> error = NickService.validate(rawNick);
        if (error.isPresent()) {
            player.sendMessage(Component.text(error.get(), NamedTextColor.RED));
            return 0;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && other.getName().equalsIgnoreCase(rawNick)) {
                player.sendMessage(Component.text(
                        "That nickname matches an online player's real name.", NamedTextColor.RED));
                return 0;
            }
        }

        dbExecutor.submit(() -> {
            try {
                nickService.set(player.getUniqueId(), rawNick, System.currentTimeMillis());
                sync(() -> {
                    displayService.apply(player);
                    player.sendMessage(Component.text("Nickname set.", NamedTextColor.GREEN));
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to set nickname for " + player.getName(), e);
                sync(() -> player.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    private int executeClear(Player player) {
        dbExecutor.submit(() -> {
            try {
                nickService.clear(player.getUniqueId());
                sync(() -> {
                    displayService.apply(player);
                    player.sendMessage(Component.text("Nickname cleared.", NamedTextColor.GREEN));
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear nickname for " + player.getName(), e);
                sync(() -> player.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    private int executeClearOther(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "target");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text("No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }

        dbExecutor.submit(() -> {
            try {
                nickService.clear(target.getUniqueId());
                sync(() -> {
                    displayService.apply(target);
                    sender.sendMessage(Component.text("Cleared " + target.getName() + "'s nickname.", NamedTextColor.GREEN));
                    target.sendMessage(Component.text("Your nickname was cleared by staff.", NamedTextColor.YELLOW));
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear nickname for " + target.getName(), e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
