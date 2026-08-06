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
import org.rigelmc.rank.RankService;

/**
 * {@code /lockdown [on|off]} - while enabled, only Moderator+ ranked players may connect;
 * everyone else is kicked with an explanatory message at login (see {@link
 * LockdownListener} for the actual enforcement). TFM ref: its {@code lockdown_mode} config
 * flag, toggled via {@code Command_settings} - TFM restricts that toggle to console only
 * (its {@code SourceType.ONLY_CONSOLE}), which RigelMCMod matches here too, for the same
 * reason {@code /adminconfig} is console-only: locking out the entire playerbase should
 * require actual server access, not just being in-game.
 *
 * <p>Deliberately <b>session-only</b> rather than persisted to {@code config.yml}, unlike
 * TFM's own version - a lockdown flag silently surviving a crash/restart could accidentally
 * soft-lock an entire playerbase out with no one around to notice. Always starting
 * unlocked, requiring a conscious re-toggle after every restart, is the safer default.</p>
 */
public final class LockdownModule implements PluginModule {

    private final RankService rankService;
    private volatile boolean enabled = false;

    public LockdownModule(@NotNull RankService rankService) {
        this.rankService = rankService;
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
        registrar.register(lockdownCommand(), "Toggle server lockdown (Moderator+ only may join) - console-only");
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
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(
                    "/lockdown can only be run from the server console or RCON, not in-game.", NamedTextColor.RED));
            return 0;
        }
        this.enabled = value;
        sender.sendMessage(Component.text(
                value ? "Lockdown is now ENABLED - only Moderator+ may join." : "Lockdown is now disabled.",
                value ? NamedTextColor.RED : NamedTextColor.GREEN));
        return 1;
    }
}
