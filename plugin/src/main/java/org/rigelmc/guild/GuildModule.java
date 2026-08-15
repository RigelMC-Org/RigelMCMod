package org.rigelmc.guild;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.guild.plot.GuildPlotBoundaryGuard;
import org.rigelmc.guild.plot.GuildPlotBypassService;
import org.rigelmc.guild.plot.GuildPlotWorldService;
import org.rigelmc.guild.plot.PlotCosmeticService;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /guild} - roster, roles, and the plot world. Thin wiring only, matching {@code
 * protect.area.ProtectAreaModule}'s exact shape: construct the command internally, bind it
 * and load persisted state on enable, register the tree - plus one addition this module
 * needs that {@code ProtectAreaModule} doesn't: ensuring the guild plot world itself exists
 * (and is fully protected - see {@code GuildPlotWorldService}'s javadoc) before any command
 * can run, mirroring {@code world.WorldModule}'s own {@code flatlandsService.initializeWorld()}
 * call for the exact same reason.
 *
 * <p>Also contributes a second, independent top-level command - {@code /wipeguildplots
 * confirm} (user-requested) - matching {@code world.WorldModule}'s own precedent of one
 * module contributing more than one top-level command ({@code /wipeflatlands} + {@code
 * /flatlands}). Console/RCON-only, unconditionally (not just when a specific config flag is
 * set, unlike {@code /wipeflatlands}'s own conditional gate) - this is more consequential
 * than a flatlands wipe: it also reassigns every existing guild's plot, not just terrain.</p>
 *
 * <p>Also registers {@code guild.plot.GuildPlotBoundaryGuard} (user-requested): nobody -
 * not even staff - can break/place anything outside their own plot in the plot world
 * without a Senior Admin first toggling {@code /guild admin plotbypass}. See that class's
 * javadoc for why this is a separate, additive listener rather than a change to the
 * general {@code protect.area.ProtectAreaService} bypass floor every other protected
 * region still uses unchanged.</p>
 */
public final class GuildModule implements PluginModule {

    private final GuildService guildService;
    private final GuildPlotWorldService guildPlotWorldService;
    private final GuildCommand command;
    private final ExecutorService dbExecutor;
    private final PermissionGate permissionGate;
    private final GuildPlotBypassService guildPlotBypassService;
    private final ProtectAreaService protectAreaService;
    private RigelMCMod plugin;

    public GuildModule(
            @NotNull GuildService guildService,
            @NotNull GuildInviteManager inviteManager,
            @NotNull GuildPlotWorldService guildPlotWorldService,
            @NotNull PlotCosmeticService plotCosmeticService,
            @NotNull PlayerDao playerDao,
            @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor,
            @NotNull GuildPlotBypassService guildPlotBypassService,
            @NotNull ProtectAreaService protectAreaService) {
        this.guildService = guildService;
        this.guildPlotWorldService = guildPlotWorldService;
        this.dbExecutor = dbExecutor;
        this.permissionGate = permissionGate;
        this.guildPlotBypassService = guildPlotBypassService;
        this.protectAreaService = protectAreaService;
        this.command = new GuildCommand(
                guildService, inviteManager, guildPlotWorldService, plotCosmeticService, playerDao, permissionGate,
                auditLogService, dbExecutor, guildPlotBypassService);
    }

    @Override
    public String id() {
        return "guild";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        command.bind(plugin);
        guildPlotWorldService.ensureWorldExists(plugin);
        try {
            guildPlotWorldService.loadPersistedState();
            guildService.loadPersistedState();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load persisted guild state", e);
        }
        plugin.getServer().getPluginManager().registerEvents(
                new GuildPlotBoundaryGuard(plugin, protectAreaService, permissionGate, guildPlotBypassService), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(command.build("guild"), "Manage your guild's roster, roles, and plot", List.of());
        registrar.register(
                wipeGuildPlotsCommand(), "Console/RCON only - wipe and reset the guild plot world", List.of());
    }

    // ---- /wipeguildplots (console/RCON only) -----------------------------------------------

    private LiteralCommandNode<CommandSourceStack> wipeGuildPlotsCommand() {
        return Commands.literal("wipeguildplots")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (rejectIfNotConsole(sender)) {
                        return 0;
                    }
                    sender.sendMessage(Component.text(
                            "This will PERMANENTLY delete every build in the guild plot world and reassign every"
                                    + " guild a fresh, empty plot - it cannot be undone. Run '/wipeguildplots"
                                    + " confirm' to proceed.",
                            NamedTextColor.RED));
                    return 1;
                })
                .then(Commands.literal("confirm").executes(this::executeWipeGuildPlotsConfirmed))
                .build();
    }

    /** @return {@code true} if the sender was rejected (and already notified); {@code false} if it's console/RCON. */
    private boolean rejectIfNotConsole(CommandSender sender) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(
                    "/wipeguildplots can only be run from the server console or RCON - it wipes every guild's"
                            + " plot and cannot be undone.",
                    NamedTextColor.RED));
            return true;
        }
        return false;
    }

    /**
     * Evacuates/unloads the plot world (main thread), deletes its folder off-thread (via
     * {@code dbExecutor}, matching {@code FlatlandsService}'s own reuse of it for this same
     * kind of blocking file I/O), recreates the world fresh, then resets every guild's plot
     * assignment - see {@code GuildPlotWorldService#evacuateAndUnloadWorld}/{@code
     * #deleteWorldFolder} and {@code GuildService#resetPlotWorld} for what each step
     * actually does.
     */
    private int executeWipeGuildPlotsConfirmed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }

        GuildPlotWorldService.WorldUnloadOutcome unloadOutcome = guildPlotWorldService.evacuateAndUnloadWorld(plugin);
        if (unloadOutcome.result() == GuildPlotWorldService.WorldUnloadResult.UNLOAD_FAILED) {
            sender.sendMessage(Component.text(
                    "Could not reset the guild plot world - it's still in use (a player teleport may not have"
                            + " fully landed yet). Try again in a moment.",
                    NamedTextColor.RED));
            return 0;
        }

        sender.sendMessage(Component.text("Resetting the guild plot world...", NamedTextColor.GOLD));
        File folder = unloadOutcome.folder();
        dbExecutor.submit(() -> {
            if (folder != null) {
                guildPlotWorldService.deleteWorldFolder(folder, plugin.getLogger());
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                guildPlotWorldService.ensureWorldExists(plugin); // recreates it - it's missing now, so this always goes through the creation branch
                dbExecutor.submit(() -> {
                    try {
                        RigelConfig config = plugin.rigelConfig();
                        guildService.resetPlotWorld(
                                config.guildPlotWorldName(), GuildPlotWorldService.PlotGridSettings.fromConfig(config),
                                System.currentTimeMillis());
                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(
                                "Guild plot world reset - every guild has a fresh, empty plot.", NamedTextColor.GREEN)));
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to reset guild plot assignments", e);
                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(
                                "The world was wiped, but resetting guild plot assignments failed - check the"
                                        + " console.",
                                NamedTextColor.RED)));
                    }
                });
            });
        });
        return 1;
    }
}
