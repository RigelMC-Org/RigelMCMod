package org.rigelmc.worldedit;

import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Detects which WorldEdit-compatible plugin (if any) is actually usable right now - FAWE
 * preferred, vanilla WorldEdit next, or neither. Deliberately checks whether the plugin is
 * currently <b>enabled</b>, not just present on disk: a crashed-but-present FAWE must
 * degrade identically to "not installed" and fall through to the next tier, not be
 * reported as available - see the documented FAWE+Eaglercraft boot-time compatibility
 * risk (the Eaglercraft-bridging plugin's block-registry mutation at boot can crash FAWE's
 * own startup registry scan on some server setups; RigelMCMod doesn't own either plugin,
 * so this bridge's only responsibility is to never mistake a crashed FAWE for a working
 * one). See {@code WorldEditBridgeTest} for an explicit test of exactly this case.
 *
 * <p>Presence/tier detection only - {@code protect.worldedit.extent.WorldEditExtentService}
 * uses {@link #isAvailable()} to decide whether to poll for and attach its {@code
 * EditSessionEvent} extent chain at all (giving {@code /protectarea} per-block enforcement
 * against a live WorldEdit/FAWE edit, plus the selection-volume/container/blocked-type
 * caps), and {@code protect.area.ProtectAreaCommand}'s {@code -wand} form uses the same
 * "check both plugin names, either may be absent" shape (its own {@code
 * resolveWorldEditPlugin()}, needing the actual {@link
 * com.sk89q.worldedit.bukkit.WorldEditPlugin} instance rather than just a {@link Tier},
 * so it doesn't call through this class directly) to read a player's live selection. This
 * class itself stays a pure presence/tier check with no compile-time WorldEdit dependency,
 * so the fallback behavior ({@link Tier#NONE} -> decline / naive implementation) is decided
 * in exactly one place regardless of which consumer is asking.</p>
 */
public final class WorldEditBridge {

    /** Which WorldEdit-compatible plugin (if any) a consumer should target right now. */
    public enum Tier {
        FAWE,
        WORLDEDIT,
        /** Neither is usable - a consumer should fall back to its own naive implementation, or decline. */
        NONE
    }

    private final Predicate<String> isPluginUsable;

    public WorldEditBridge() {
        this(name -> {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            return plugin != null && plugin.isEnabled();
        });
    }

    /** Test-only constructor - injects the presence/enabled check directly, no live Bukkit server needed. */
    WorldEditBridge(@NotNull Predicate<String> isPluginUsable) {
        this.isPluginUsable = isPluginUsable;
    }

    @NotNull
    public Tier detect() {
        if (isPluginUsable.test("FastAsyncWorldEdit")) {
            return Tier.FAWE;
        }
        if (isPluginUsable.test("WorldEdit")) {
            return Tier.WORLDEDIT;
        }
        return Tier.NONE;
    }

    public boolean isAvailable() {
        return detect() != Tier.NONE;
    }
}
