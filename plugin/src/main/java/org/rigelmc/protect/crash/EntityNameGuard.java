package org.rigelmc.protect.crash;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Strips an oversized custom entity name before the client ever renders it - TFM ref:
 * {@code EntityNameValidator.java}. This is a simpler, plain-text-length-only version:
 * TFM's own also screens for unsafe translatable-component/format-specifier payloads via
 * NMS reflection, deliberately out of scope here - see {@link ItemGuard}'s javadoc for the
 * same "public Paper API only" scoping rationale this whole package follows.
 */
public final class EntityNameGuard implements Listener {

    private final RigelMCMod plugin;

    public EntityNameGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAddToWorld(@NotNull EntityAddToWorldEvent event) {
        if (!plugin.rigelConfig().crashEntityGuardEnabled()) {
            return;
        }
        Entity entity = event.getEntity();
        Component name = entity.customName();
        if (name == null) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        if (plain.length() > plugin.rigelConfig().crashEntityMaxNameLength()) {
            entity.customName(null);
            entity.setCustomNameVisible(false);
        }
    }
}
