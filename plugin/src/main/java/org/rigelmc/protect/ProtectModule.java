package org.rigelmc.protect;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * Anti-grief primitives module. Ships with tiered command access control
 * ({@link CommandAccessRegistry}, TFM-syntax-compatible) in Phase 1; blocked blocks/
 * entities/effects, anti-nuke, anti-spam, and the movement validator land in Phase 2 per
 * docs/architecture.md.
 */
public final class ProtectModule implements PluginModule {

    private final PermissionGate permissionGate;
    private CommandAccessRegistry commandRegistry;
    private RigelMCMod plugin;

    public ProtectModule(PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "protect";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;

        // Explicit PermissionDefault.FALSE registration matters a lot here, and not just
        // as boilerplate: on a Free-OP server every player is vanilla op'd (see
        // core.AutoOpModule), and Bukkit's fallback for any *unregistered* permission
        // string is "true if the player is op". Without this explicit registration,
        // BYPASS_PERMISSION would silently resolve true for every single player the
        // moment auto-op landed, defeating the entire tiered command-access system it
        // gates. See CONTRIBUTING.md's "permission defaults" note - this is the one spot
        // in the codebase that checks a raw Bukkit permission rather than RigelMCMod's
        // own rank cache, precisely because it needs to remain overridable independent
        // of rank (an explicit per-player grant, not a rank tier).
        if (plugin.getServer().getPluginManager().getPermission(BlockedCommandListener.BYPASS_PERMISSION) == null) {
            plugin.getServer()
                    .getPluginManager()
                    .addPermission(new Permission(
                            BlockedCommandListener.BYPASS_PERMISSION,
                            "Bypasses tiered command access control, including fully disabled commands",
                            PermissionDefault.FALSE));
        }

        this.commandRegistry = new CommandAccessRegistry(plugin.getLogger());
        commandRegistry.reload(plugin.rigelConfig().protectCommandAccess(), this::aliasesOf);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new BlockedCommandListener(commandRegistry, permissionGate), plugin);
    }

    /**
     * Re-reads {@code protect.command-access} so a {@code /rmcm reload} takes effect
     * immediately, with no restart needed - unlike most of {@code RigelConfig}'s other
     * accessors (read fresh on every call already), {@link CommandAccessRegistry} keeps
     * its own pre-parsed, specificity-sorted copy for a fast lookup on the hot
     * command-preprocess path, so it needs an explicit refresh call.
     */
    @Override
    public void onConfigReload(RigelConfig config) {
        if (commandRegistry != null) {
            commandRegistry.reload(config.protectCommandAccess(), this::aliasesOf);
        }
    }

    /**
     * Resolves a base command's currently-registered aliases via the live
     * {@code CommandMap} - e.g. Essentials commonly aliases {@code /vanish} to
     * {@code /v}. Without this, a {@code protect.command-access} rule written against
     * the canonical name is trivially routed around by typing a shorter alias instead -
     * TFM's own {@code CommandBlocker} resolves aliases the exact same way, for the
     * exact same reason. Returns an empty list if the command isn't currently registered
     * by anything (harmless - just means there's nothing to expand).
     */
    @NotNull
    private List<String> aliasesOf(String baseCommand) {
        Command command = plugin.getServer().getCommandMap().getCommand(baseCommand);
        return command == null ? List.of() : command.getAliases();
    }
}
