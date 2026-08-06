package org.rigelmc.disguise;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * LibsDisguises integration: {@link DisguiseAbuseGuard} (the real fix for TFM's dead
 * forbidden-disguise-type enforcement - see {@link DisallowedDisguises}'s own javadoc), plus
 * {@code /disguisetoggle} and {@code /undisguiseall} ported directly from TFM's real {@code
 * Command_disguisetoggle}/{@code Command_undisguiseall}. Real {@code /disguise} itself stays
 * entirely LibsDisguises' own command - this module never reimplements it, only gates and
 * globally toggles it, matching TFM's own scope exactly.
 *
 * <p>Always registers its listener and both commands regardless of whether LibsDisguises is
 * actually installed (cheap no-op: {@link DisguiseAbuseGuard} only ever matches commands
 * LibsDisguises itself owns, and both commands check {@link LibsDisguisesBridge#isAvailable()}
 * before doing anything) - same rationale as {@code protect.worldedit.WorldEditProtectModule},
 * so this keeps working correctly if LibsDisguises gets installed onto an already-running
 * server.</p>
 */
public final class DisguiseModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private final DisallowedDisguises disallowedDisguises;
    private LibsDisguisesBridge libsDisguisesBridge;
    private RigelMCMod plugin;

    public DisguiseModule(
            @NotNull PermissionGate permissionGate, @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor, @NotNull DisallowedDisguises disallowedDisguises) {
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
        this.disallowedDisguises = disallowedDisguises;
    }

    @Override
    public String id() {
        return "disguise";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        this.libsDisguisesBridge = new LibsDisguisesBridge(plugin.getLogger(), permissionGate);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new DisguiseAbuseGuard(disallowedDisguises, permissionGate), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(disguiseToggleCommand(), "Globally enable/disable disguises - Senior Admin+",
                List.of("dtoggle"));
        registrar.register(undisguiseAllCommand(), "Undisguise every online player - Senior Admin+",
                List.of("uall"));
    }

    @Override
    public void onConfigReload(RigelConfig config) {
        disallowedDisguises.reload(config);
    }

    // ---- /disguisetoggle -------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> disguiseToggleCommand() {
        return Commands.literal("disguisetoggle")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(this::executeDisguiseToggle)
                .build();
    }

    private int executeDisguiseToggle(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!libsDisguisesBridge.isAvailable()) {
            sender.sendMessage(Component.text("LibsDisguises is not enabled.", NamedTextColor.RED));
            return 0;
        }

        boolean enabling = disallowedDisguises.isDisabled();
        if (!enabling) {
            // Currently enabled -> disabling: undisguise everyone first, staff included,
            // matching TFM's own Command_disguisetoggle exactly.
            libsDisguisesBridge.undisguiseAll(true);
        }
        disallowedDisguises.setDisabled(!enabling);

        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                auditLogService.record(actor, "DISGUISE_TOGGLE", null, enabling ? "enabled" : "disabled");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to audit-log /disguisetoggle", e);
            }
        });

        sender.sendMessage(Component.text(
                "Disguises are now " + (enabling ? "enabled." : "disabled."), NamedTextColor.GRAY));
        return 1;
    }

    // ---- /undisguiseall --------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> undisguiseAllCommand() {
        return Commands.literal("undisguiseall")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(this::executeUndisguiseAll)
                .build();
    }

    private int executeUndisguiseAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!libsDisguisesBridge.isAvailable()) {
            sender.sendMessage(Component.text("LibsDisguises is not enabled.", NamedTextColor.GRAY));
            return 0;
        }
        if (disallowedDisguises.isDisabled()) {
            sender.sendMessage(Component.text("Disguises are not enabled.", NamedTextColor.GRAY));
            return 0;
        }

        libsDisguisesBridge.undisguiseAll(false);

        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                auditLogService.record(actor, "UNDISGUISE_ALL", null, null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to audit-log /undisguiseall", e);
            }
        });

        sender.sendMessage(Component.text("Undisguising all non-staff players.", NamedTextColor.RED));
        return 1;
    }

    // ---- shared helpers ---------------------------------------------------------------------

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }

    private static UUID actorUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }
}
