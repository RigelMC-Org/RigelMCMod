package org.rigelmc.skin;

import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Pure presence detection for SkinsRestorer - genuinely new ground for this project, not a
 * TFM port: a full grep of TFM's real source turns up zero skin-related code anywhere, so
 * there's nothing to compare against or improve on here.
 *
 * <p>Deliberately does nothing beyond detection. {@code /skin} stays entirely
 * SkinsRestorer's own command - this bridge exists only as a hook point for a future policy
 * layer (e.g. gating skin changes off inside a {@code protect.area} region, or a staff-only
 * mode) that doesn't exist yet, exactly mirroring {@code worldedit.WorldEditBridge}'s own
 * stated rationale for existing ahead of any actual consumer.</p>
 */
public final class SkinBridge {

    private final Predicate<String> isPluginUsable;

    public SkinBridge() {
        this(name -> {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            return plugin != null && plugin.isEnabled();
        });
    }

    /** Test-only constructor - injects the presence/enabled check directly, no live Bukkit server needed. */
    SkinBridge(@NotNull Predicate<String> isPluginUsable) {
        this.isPluginUsable = isPluginUsable;
    }

    public boolean isAvailable() {
        return isPluginUsable.test("SkinsRestorer");
    }
}
