package org.rigelmc.rmcm;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /rmcm} - plugin meta-info and admin utility commands, for RigelMCMod itself
 * rather than any one feature area:
 *
 * <ul>
 *   <li>{@code /rmcm info} - name, version, author, and website. Open to everyone -
 *       purely informational, nothing here isn't already visible to any op via
 *       {@code /version RigelMCMod}.</li>
 *   <li>{@code /rmcm reload} - reloads {@code config.yml} - Senior Admin+ (console always
 *       allowed), since unlike {@code info} this can change live behavior. See
 *       {@link RigelMCMod#reloadRigelConfig()} for exactly what it does and does not
 *       cover - notably, {@code modules.*.enabled} toggles still require a restart.</li>
 * </ul>
 *
 * <p>Uses {@link PluginMeta} (via {@code Plugin#getPluginMeta()}), the modern accessor -
 * confirmed via direct bytecode inspection of Paper 26.1.2's actual {@code Plugin}/
 * {@code PluginMeta} classes, not assumed, since {@code getAuthors()} being plural
 * ({@code List<String>}, not a singular {@code getAuthor()}) is easy to get wrong from a
 * doc summary alone.</p>
 */
public final class RmcmModule implements PluginModule {

    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    public RmcmModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "rmcm";
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
        registrar.register(rmcmCommand(), "RigelMCMod plugin info and admin utilities (info, reload)");
    }

    private LiteralCommandNode<CommandSourceStack> rmcmCommand() {
        return Commands.literal("rmcm")
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/rmcm <info|reload>"))
                .then(Commands.literal("info").executes(this::executeInfo))
                // Deliberately no .requires() gate on this node - see executeReload for why:
                // a failed .requires() hides the node from Brigadier's parser entirely,
                // which surfaces to an under-ranked player as a confusing raw
                // "Incorrect argument for command" parse error instead of a clear
                // rejection message (the same class of issue /adminconfig's javadoc already
                // documents). The rank check happens inside the execute body instead.
                .then(Commands.literal("reload").executes(this::executeReload))
                .build();
    }

    private int executeInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        PluginMeta meta = plugin.getPluginMeta();
        String website = meta.getWebsite();

        // Component-to-Component #append chaining (NOT the Component.text()....build()
        // *builder* chain) - the builder form threw a runtime-only NoSuchMethodError on
        // a live Paper 26.1.2 server when this plugin was compiled against paper-api
        // 26.2's bundled Adventure version - see CONTRIBUTING.md rule 3 and
        // chat.ChatFormatListener's javadoc for the full story.
        Component message = Component.empty()
                .append(Component.text("RigelMCMod ", NamedTextColor.GOLD))
                .append(Component.text("v" + meta.getVersion(), NamedTextColor.YELLOW))
                .appendNewline()
                .append(Component.text("Original author: ", NamedTextColor.GRAY))
                .append(Component.text(String.join(", ", meta.getAuthors()), NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text("License: ", NamedTextColor.GRAY))
                .append(Component.text("RigelMCMod License (source-available)", NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text("Website: ", NamedTextColor.GRAY))
                .append(Component.text(
                        website == null || website.isBlank() ? "N/A" : website, NamedTextColor.AQUA));
        sender.sendMessage(message);
        return 1;
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!hasRank(ctx.getSource(), "senior_admin")) {
            sender.sendMessage(Component.text(
                    "You need Senior Admin rank or higher to reload RigelMCMod's config.", NamedTextColor.RED));
            return 0;
        }
        try {
            if (!plugin.reloadRigelConfig()) {
                sender.sendMessage(Component.text(
                        "config.yml failed to parse - check the console for the exact line/error. The"
                                + " previously loaded config is still in effect; nothing changed.",
                        NamedTextColor.RED));
                return 0;
            }
            sender.sendMessage(Component.text("config.yml reloaded.", NamedTextColor.GREEN));
            sender.sendMessage(Component.text(
                    "Note: modules.*.enabled toggles (and anything else only read at startup) still need a"
                            + " restart - values read at runtime (durations, messages, command-access rules,"
                            + " tab-list/scoreboard text, etc.) are picked up immediately.",
                    NamedTextColor.YELLOW));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to reload config.yml via /rmcm reload", e);
            sender.sendMessage(Component.text(
                    "Failed to reload config.yml - check the console for details.", NamedTextColor.RED));
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
