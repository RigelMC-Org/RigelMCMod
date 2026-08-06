package org.rigelmc.core;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.chat.PlayerDisplayService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.PrefixService;

/**
 * Wraps {@link AutoOpListener} as a toggleable module (default on - see config.yml), and
 * ships RigelMCMod's own replacements for vanilla {@code /op}, {@code /opall}, and {@code
 * /deop} - TFM-style, studied directly from its {@code Command_op}/{@code
 * Command_opall}/{@code Command_deop}.
 *
 * <ul>
 *   <li>{@code /op [player]} - open to <b>everyone</b>, not just staff. Matches TFM's own
 *       design intentionally: on a Free-OP server, vanilla operator status carries no
 *       RigelMCMod rank/permission of its own (that's {@link PermissionGate}'s job, backed
 *       by RigelMCMod's own DB-tracked rank, entirely independent of Bukkit's {@code
 *       isOp()} flag) - so letting any player restore their own or another online player's
 *       op status can't itself escalate anyone's real privilege, it just undoes whatever a
 *       troll did with {@code /deop}. {@code protect.command-access} in config.yml no
 *       longer blocks the bare {@code op} literal for this reason - see the comment there.</li>
 *   <li>{@code /opall} - re-ops every currently online player at once, Moderator+ only
 *       (this deliberately diverges from TFM, whose equivalent is open to everyone - a
 *       server-wide mass action earns a Staff gate here).</li>
 *   <li>{@code /deop <player>} - strips a player's operator status, Moderator+ only, online
 *       players only (see {@link #executeDeop} for why offline targets aren't supported).</li>
 *   <li>{@code /deopall} - the mass-action counterpart to {@code /deop}, same as {@code
 *       /opall} is to {@code /op}. Moderator+ only, same rationale as {@code /opall}.</li>
 * </ul>
 */
public final class AutoOpModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final PrefixService prefixService;
    private final PlayerDisplayService displayService;
    private RigelMCMod plugin;

    public AutoOpModule(
            @NotNull PermissionGate permissionGate,
            @NotNull PrefixService prefixService,
            @NotNull PlayerDisplayService displayService) {
        this.permissionGate = permissionGate;
        this.prefixService = prefixService;
        this.displayService = displayService;
    }

    /**
     * Corrects the generic {@code [OP]} chat/tab-list bracket immediately after any
     * op-status change here - user-reported: a deopped default-rank player kept showing
     * {@code [OP]} in chat because nothing in this class ever touched the cached prefix,
     * see {@code rank.PrefixService}'s javadoc for the full mechanism this feeds into.
     */
    private void refreshOpDisplay(@NotNull Player player) {
        prefixService.applyOpStatus(player.getUniqueId(), player.isOp());
        displayService.apply(player);
    }

    @Override
    public String id() {
        return "autoop";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new AutoOpListener(), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(opCommand(),
                "Op yourself, or another online player - open to everyone, TFM-style");
        registrar.register(opAllCommand(), "Op every online player at once - Moderator+");
        registrar.register(deopCommand(), "Remove an online player's operator status - Moderator+");
        registrar.register(deopAllCommand(), "Remove operator status from every online player - Moderator+");
    }

    // ---- /op [player] --------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> opCommand() {
        return Commands.literal("op")
                .executes(this::executeOpSelf)
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(this::executeOpTarget))
                .build();
    }

    private int executeOpSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            return CommandUsage.show(sender, "/op <player>");
        }
        applyOp(sender, player);
        return 1;
    }

    private int executeOpTarget(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }
        applyOp(sender, target);
        return 1;
    }

    private void applyOp(CommandSender sender, Player target) {
        target.setOp(true);
        refreshOpDisplay(target);
        target.sendMessage(plugin.messages().get("op.self", "<yellow>You are now op!"));
        if (!target.equals(sender)) {
            sender.sendMessage(plugin.messages().get("op.other", "<aqua>Opped {target}.", "target", target.getName()));
        }
    }

    // ---- /opall ----------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> opAllCommand() {
        return Commands.literal("opall").executes(this::executeOpAll).build();
    }

    private int executeOpAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandSender sender = source.getSender();
        if (!hasRank(source, "moderator")) {
            sender.sendMessage(Component.text(
                    "You need Moderator rank or higher to op everyone on the server at once.",
                    NamedTextColor.RED));
            return 0;
        }

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setOp(true);
            refreshOpDisplay(player);
            count++;
        }
        broadcastExcept(sender, plugin.messages().get(
                "opall.public", "<aqua>{sender} opped everyone on the server ({count} player(s)).",
                "sender", sender.getName(), "count", String.valueOf(count)));
        return 1;
    }

    // ---- /deop <player> ---------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> deopCommand() {
        return Commands.literal("deop")
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/deop <player>"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(this::executeDeop))
                .build();
    }

    /**
     * Online players only, unlike TFM's {@code OfflinePlayer}-accepting equivalent - a
     * deliberate scope cut, not an oversight: {@link AutoOpListener} re-ops every player
     * unconditionally on join (that's the entire point of a Free-OP server), so deopping
     * someone who's already offline wouldn't stick past their next login anyway - it'd be
     * symbolic at best. Gating on an online {@link Player} keeps this consistent with the
     * rest of the codebase's online-only commands (e.g. the admin world guest list) and
     * avoids the deprecated, network-blocking {@code Bukkit.getOfflinePlayer(String)}
     * lookup entirely.
     */
    private int executeDeop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandSender sender = source.getSender();
        if (!hasRank(source, "moderator")) {
            sender.sendMessage(Component.text(
                    "You need Moderator rank or higher to deop a player.", NamedTextColor.RED));
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "No online player found matching '" + targetName + "'. Only online players can be"
                            + " deopped - RigelMCMod re-ops everyone on join, so deopping someone offline"
                            + " wouldn't stick.",
                    NamedTextColor.RED));
            return 0;
        }

        target.setOp(false);
        refreshOpDisplay(target);
        target.sendMessage(plugin.messages().get("deop.target", "<yellow>You are no longer op!"));
        broadcastExcept(sender, plugin.messages().get("deop.public", "<aqua>{sender} deopped {target}.",
                "sender", sender.getName(), "target", target.getName()));
        return 1;
    }

    /** Public admin-action announcement, matching TFM's own broadcast convention - see {@code /smite}/{@code /opall}. */
    private void broadcastExcept(CommandSender sender, Component component) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(component);
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(component);
        }
    }

    // ---- /deopall ---------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> deopAllCommand() {
        return Commands.literal("deopall").executes(this::executeDeopAll).build();
    }

    private int executeDeopAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandSender sender = source.getSender();
        if (!hasRank(source, "moderator")) {
            sender.sendMessage(Component.text(
                    "You need Moderator rank or higher to deop everyone on the server at once.",
                    NamedTextColor.RED));
            return 0;
        }

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setOp(false);
            refreshOpDisplay(player);
            count++;
        }
        broadcastExcept(sender, plugin.messages().get(
                "deopall.public", "<aqua>{sender} deopped everyone on the server ({count} player(s)).",
                "sender", sender.getName(), "count", String.valueOf(count)));
        return 1;
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
