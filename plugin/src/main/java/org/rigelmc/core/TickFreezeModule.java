package org.rigelmc.core;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ServerTickManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /tickfreeze [on|off]} and {@code /tickrate <rate>|reset} - thin wrappers over
 * Paper's own {@link ServerTickManager} (the exact machinery backing its built-in
 * {@code /tick freeze}/{@code /tick rate} commands), gated to Senior Admin+. Reuses
 * Paper's own tested implementation rather than reinventing it - same "reuse a proven
 * tool instead of rebuilding it" convention already used for CoreProtect/WorldEdit-FAWE
 * elsewhere in this project - so there's no custom scheduler/tick-hooking code here at
 * all, just a rank-gated, publicly-announced front door onto the real API.
 *
 * <p>No TFM equivalent exists to study/port from - {@code ServerTickManager} is a
 * newer Paper API TFM's own currently-supported Bukkit target predates - so this is
 * RigelMCMod-original, sized to match this project's own established conventions for a
 * global, highly disruptive toggle (see {@link #scheduleAutoUnfreeze} and the class
 * javadoc on {@code punish.freeze.FreezeService} for the sibling per-player version of
 * the same "don't leave the whole server visibly broken and forgotten" concern).</p>
 *
 * <p>Freezing the entire world tick loop (redstone, mob AI, crop growth, water/lava
 * flow, entity movement - everything except players themselves, who can still move and
 * chat) is at least as disruptive as {@code /freezeall}'s whole-server player freeze, so
 * it gets the same Senior Admin tier and the same kind of auto-unfreeze safety net. The
 * raw vanilla/Paper {@code /tick} command is separately blocked below Senior Admin via
 * {@code protect.command-access} (defense-in-depth, same rationale as the existing
 * CoreProtect/{@code /vanish}/{@code /tpo} entries there) - this module's own commands
 * are the intended way in for anyone at that rank.</p>
 */
public final class TickFreezeModule implements PluginModule {

    private final PermissionGate permissionGate;
    private RigelMCMod plugin;
    private BukkitTask autoUnfreezeTask;

    public TickFreezeModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "tickfreeze";
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
        registrar.register(tickFreezeCommand(),
                "Freeze or unfreeze the entire server's tick loop - Senior Admin+");
        registrar.register(tickRateCommand(), "Change or reset the server's tick rate - Senior Admin+");
    }

    @NotNull
    private static ServerTickManager tickManager() {
        return Bukkit.getServer().getServerTickManager();
    }

    // ---- /tickfreeze --------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> tickFreezeCommand() {
        return Commands.literal("tickfreeze")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> executeSetFrozen(ctx, !tickManager().isFrozen()))
                .then(Commands.literal("on").executes(ctx -> executeSetFrozen(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> executeSetFrozen(ctx, false)))
                .build();
    }

    private int executeSetFrozen(CommandContext<CommandSourceStack> ctx, boolean value) {
        CommandSender sender = ctx.getSource().getSender();
        tickManager().setFrozen(value);
        cancelAutoUnfreeze();
        if (value) {
            scheduleAutoUnfreeze();
        }

        // Public, unlike an individual /freeze target message - this affects every
        // player at once and (unlike /freezeall, which visibly locks player movement)
        // has no other obvious in-game tell, so everyone gets an explicit heads-up.
        broadcast(sender, value
                ? sender.getName() + " froze the entire server's tick loop."
                : sender.getName() + " unfroze the server's tick loop.",
                value ? NamedTextColor.RED : NamedTextColor.GREEN);
        return 1;
    }

    /**
     * Safety net matching {@link RigelConfig#freezeAutoUnfreezeMinutes}'s own rationale -
     * a global tick freeze left on with no one around to notice makes the entire server
     * appear dead until someone thinks to run {@code /tickfreeze off}. Cancelled and
     * rescheduled on every {@code /tickfreeze} call (see {@link #executeSetFrozen}) so a
     * fresh toggle always gets the full timeout, and cleared entirely on manual unfreeze.
     */
    private void scheduleAutoUnfreeze() {
        long minutes = plugin.rigelConfig().tickFreezeAutoUnfreezeMinutes();
        if (minutes <= 0) {
            return; // timeout disabled - stays frozen until manually released
        }
        autoUnfreezeTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            autoUnfreezeTask = null;
            if (!tickManager().isFrozen()) {
                return; // already unfrozen manually - nothing to do
            }
            tickManager().setFrozen(false);
            broadcast(null, "The server's tick loop was automatically unfrozen after " + minutes
                    + " minute(s) - it was left frozen with no follow-up.", NamedTextColor.YELLOW);
            plugin.getLogger().info("Auto-unfroze the server tick loop after " + minutes + " minute(s).");
        }, minutes * 60L * 20L);
    }

    private void cancelAutoUnfreeze() {
        if (autoUnfreezeTask != null) {
            autoUnfreezeTask.cancel();
            autoUnfreezeTask = null;
        }
    }

    // ---- /tickrate ----------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> tickRateCommand() {
        return Commands.literal("tickrate")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(this::executeQueryRate)
                .then(Commands.literal("reset").executes(this::executeResetRate))
                .then(Commands.argument("rate", FloatArgumentType.floatArg(1.0f, 10000.0f))
                        .executes(this::executeSetRate))
                .build();
    }

    private int executeQueryRate(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(Component.text(
                "Current tick rate: " + tickManager().getTickRate()
                        + " - use '/tickrate <rate>' to change it, or '/tickrate reset' to restore the"
                        + " default (20.0).",
                NamedTextColor.GRAY));
        return 1;
    }

    private int executeSetRate(CommandContext<CommandSourceStack> ctx) {
        float rate = FloatArgumentType.getFloat(ctx, "rate");
        tickManager().setTickRate(rate);
        broadcast(ctx.getSource().getSender(),
                ctx.getSource().getSender().getName() + " set the server's tick rate to " + rate + ".",
                NamedTextColor.GOLD);
        return 1;
    }

    private int executeResetRate(CommandContext<CommandSourceStack> ctx) {
        tickManager().setTickRate(20.0f);
        broadcast(ctx.getSource().getSender(),
                ctx.getSource().getSender().getName() + " reset the server's tick rate to the default (20.0).",
                NamedTextColor.GOLD);
        return 1;
    }

    // ---- shared helpers -------------------------------------------------------------

    /**
     * Public admin-action broadcast, matching this project's own convention for a
     * server-wide toggle (see {@code punish.PunishModule#broadcastPublic}). {@code
     * sender} may be {@code null} for a system-triggered message (the auto-unfreeze
     * timeout) that has no human sender to also separately notify.
     */
    private void broadcast(@org.jetbrains.annotations.Nullable CommandSender sender, String text, NamedTextColor color) {
        Component message = Component.text(text, color);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
        if (sender != null && !(sender instanceof Player)) {
            sender.sendMessage(message);
        }
        plugin.getLogger().info(text);
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
