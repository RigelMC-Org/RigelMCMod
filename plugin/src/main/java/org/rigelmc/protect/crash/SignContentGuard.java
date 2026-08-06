package org.rigelmc.protect.crash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Blocks placing a sign with a line of text past a safety length cap - TFM ref: {@code
 * SignValidator.java}. Simplified to a plain-text-length check; TFM's own also screens for
 * cursed translatable-component payloads via its {@code ComponentScanner}, deliberately
 * out of scope here - see {@link ItemGuard}'s javadoc for this package's "public Paper API
 * only" scoping rationale. Moderator+ are exempt.
 */
public final class SignContentGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;

    public SignContentGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(@NotNull SignChangeEvent event) {
        if (!plugin.rigelConfig().crashSignGuardEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
            return;
        }
        int maxLength = plugin.rigelConfig().crashSignMaxLineLength();
        for (Component line : event.lines()) {
            if (plainTextLength(line) > maxLength) {
                event.setCancelled(true);
                player.sendMessage(Component.text(
                        "That sign text was blocked - a line exceeds the server's safety limit.",
                        NamedTextColor.RED));
                return;
            }
        }
    }

    private static int plainTextLength(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component).length();
    }
}
