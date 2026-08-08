package org.rigelmc.vanish;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * RigelMCMod's own vanish, stronger than Essentials' default: hidden from every player
 * who isn't at least Moderator - including the auto-op'd masses, who on a Free-OP
 * server hold real vanilla operator status and would otherwise see through (or invoke)
 * Essentials' vanish the same way any op can. See docs/architecture.md's Essentials
 * collision table for the "Vanish" entry and the rationale.
 *
 * <p>Registered as {@code /rvanish}, not the bare {@code /vanish}, deliberately -
 * Essentials already owns that literal name, and which plugin's Brigadier registration
 * would actually win a same-literal collision on Paper is unverified (see
 * docs/architecture.md's open items). {@code protect.command-access}'s
 * {@code vanish: moderator} entry blocks the bare command for non-staff regardless of
 * which plugin ends up answering it, since that check runs at
 * {@code PlayerCommandPreprocessEvent} time, before either plugin's handler executes.</p>
 */
public final class VanishModule implements PluginModule {

    private final VanishService vanishService;
    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    /**
     * @param vanishService constructor-injected (rather than owned/created here) so
     *     {@code discord.JoinLeaveBridgeListener} can also query vanish state - a
     *     vanished staff member's leave shouldn't be announced to the public Discord
     *     channel, since that would leak that they were online at all
     */
    public VanishModule(@NotNull VanishService vanishService, @NotNull PermissionGate permissionGate) {
        this.vanishService = vanishService;
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "vanish";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new VanishListener(plugin, vanishService, permissionGate), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(
                rvanishCommand(), "Toggle vanish - hidden from regular players and non-staff ops");
    }

    private LiteralCommandNode<CommandSourceStack> rvanishCommand() {
        return Commands.literal("rvanish")
                .requires(source -> source.getSender() instanceof Player player
                        && permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator"))
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource().getSender();
                    boolean nowVanished = vanishService.toggle(player.getUniqueId());
                    applyVisibility(player, nowVanished);
                    player.sendMessage(nowVanished
                            ? Component.text(
                                    "You are now vanished - hidden from regular players and non-staff ops.",
                                    NamedTextColor.GRAY)
                            : Component.text("You are no longer vanished.", NamedTextColor.GRAY));
                    return 1;
                })
                .build();
    }

    private void applyVisibility(Player target, boolean vanished) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }
            boolean viewerIsStaff = permissionGate.hasAtLeastCached(viewer.getUniqueId(), "moderator");
            if (vanished && !viewerIsStaff) {
                viewer.hidePlayer(plugin, target);
            } else {
                viewer.showPlayer(plugin, target);
            }
        }
    }
}
