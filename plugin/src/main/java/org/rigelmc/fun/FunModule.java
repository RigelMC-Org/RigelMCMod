package org.rigelmc.fun;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * Fun/gadget commands - just {@code /orbit} for now (TFM ref: {@code Command_orbit.java});
 * jumppads, landmines, novelty guns, and particle trails are tracked as a follow-up batch.
 * Reads {@code plugin.rigelConfig()} fresh rather than caching it - see {@code
 * protect.antigrief.AntiNukeGuard}'s javadoc for why.
 */
public final class FunModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final OrbitService orbitService = new OrbitService();
    private RigelMCMod plugin;

    public FunModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "fun";
    }

    @Override
    public boolean isEnabled(RigelConfig cfg) {
        return cfg.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(orbitService, plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(orbitCommand(), "Toggle repeatedly launching a player upward - Senior Admin+");
    }

    private LiteralCommandNode<CommandSourceStack> orbitCommand() {
        return Commands.literal("orbit")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/orbit <player>"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS).executes(this::executeOrbit))
                .build();
    }

    private int executeOrbit(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "No online player found matching '" + name + "'.", NamedTextColor.RED));
            return 0;
        }

        if (orbitService.isOrbiting(target.getUniqueId())) {
            orbitService.stopOrbiting(target.getUniqueId());
            sender.sendMessage(Component.text("Stopped orbiting " + target.getName() + ".", NamedTextColor.GRAY));
        } else {
            target.setGameMode(GameMode.SURVIVAL);
            orbitService.startOrbiting(target, plugin.rigelConfig().orbitStrength());
            sender.sendMessage(Component.text("Orbiting " + target.getName() + ".", NamedTextColor.AQUA));
        }
        return 1;
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
