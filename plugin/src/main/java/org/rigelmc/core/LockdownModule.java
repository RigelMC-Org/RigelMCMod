package org.rigelmc.core;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.RankService;

/**
 * {@code /lockdown [on|off]} - while enabled, only Moderator+ ranked players may connect;
 * everyone else is kicked with an explanatory message at login (see {@link
 * LockdownListener} for the actual enforcement, unchanged by this class - Moderator+ can
 * always still join, only who may <i>toggle</i> lockdown is gated here).
 *
 * <p>Toggling requires <b>Admin+</b>, in-game or from the server console/RCON - console
 * senders always pass (matching every other console-usable command in this codebase);
 * in-game senders need {@link PermissionGate#hasAtLeastCached}. Deliberately no top-level
 * {@code .requires()} gate - a failed {@code .requires()} hides the whole command node from
 * Brigadier's parser, surfacing to an under-ranked player as a confusing raw "Unknown or
 * incomplete command" instead of a clear rejection message (the same class of issue {@code
 * rmcm.RmcmModule}/{@code world.WorldModule#wipeFlatlandsCommand} already document and fix
 * the same way) - the check happens inside {@link #executeToggle} instead. This used to be
 * console-only with no rank gate at all (TFM's own {@code Command_settings} is {@code
 * SourceType.ONLY_CONSOLE}) - opened up to in-game Admin+ for the same reason {@code
 * /wipeflatlands} was: there's nothing about toggling this flag that inherently needs
 * console/RCON access specifically, only a high enough rank to be trusted with it.</p>
 *
 * <p>Deliberately <b>session-only</b> rather than persisted to {@code config.yml}, unlike
 * TFM's own version - a lockdown flag silently surviving a crash/restart could accidentally
 * soft-lock an entire playerbase out with no one around to notice. Always starting
 * unlocked, requiring a conscious re-toggle after every restart, is the safer default.</p>
 */
public final class LockdownModule implements PluginModule {

    private final RankService rankService;
    private final PermissionGate permissionGate;
    private volatile boolean enabled = false;

    public LockdownModule(@NotNull RankService rankService, @NotNull PermissionGate permissionGate) {
        this.rankService = rankService;
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "lockdown";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new LockdownListener(this, rankService, plugin.getLogger()), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(lockdownCommand(), "Toggle server lockdown (Moderator+ only may join) - Admin+");
    }

    boolean isActive() {
        return enabled;
    }

    private LiteralCommandNode<CommandSourceStack> lockdownCommand() {
        return Commands.literal("lockdown")
                .executes(ctx -> executeToggle(ctx, !enabled))
                .then(Commands.literal("on").executes(ctx -> executeToggle(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> executeToggle(ctx, false)))
                .build();
    }

    private int executeToggle(CommandContext<CommandSourceStack> ctx, boolean value) {
        CommandSender sender = ctx.getSource().getSender();
        if (!hasRank(sender)) {
            sender.sendMessage(Component.text("You need Admin rank or higher to toggle lockdown.", NamedTextColor.RED));
            return 0;
        }
        this.enabled = value;
        sender.sendMessage(Component.text(
                value ? "Lockdown is now ENABLED - only Moderator+ may join." : "Lockdown is now disabled.",
                value ? NamedTextColor.RED : NamedTextColor.GREEN));
        return 1;
    }

    private boolean hasRank(CommandSender sender) {
        if (sender instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), "admin");
        }
        return true; // console/RCON always allowed
    }
}
