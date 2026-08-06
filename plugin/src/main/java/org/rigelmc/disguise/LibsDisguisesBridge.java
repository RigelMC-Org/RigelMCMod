package org.rigelmc.disguise;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.rank.PermissionGate;

/**
 * Thin, reflection-based bridge to LibsDisguises' {@code DisguiseAPI} - same soft-
 * integration shape as {@code rollback.CoreProtectBridge} (constructor-time detect, no
 * compile-time dependency, since LibsDisguises isn't published anywhere this project could
 * cleanly {@code compileOnly} against). TFM ref: {@code bridge.LibsDisguisesBridge}, studied
 * directly - ported the same cross-version method-resolution shape (LibsDisguises has
 * shipped both {@code Entity}- and {@code Player}-typed overloads of these two methods
 * across versions) but simplified the detection lifecycle: TFM polls with a delayed retry
 * because it can't guarantee load order, whereas this project's {@code paper-plugin.yml}
 * declares {@code LibsDisguises: load: BEFORE}, so a single constructor-time detect (same
 * as {@code CoreProtectBridge}) is already guaranteed to run after LibsDisguises has
 * finished enabling, when it's present at all.
 *
 * <p><b>Unverified against a real LibsDisguises install</b> in this sandbox (no live
 * server, no LibsDisguises jar to reflect against) - the method names/signatures below are
 * sourced from TFM's own real, working integration against the same plugin, not guessed.</p>
 */
public final class LibsDisguisesBridge {

    private final Logger logger;
    private final PermissionGate permissionGate;
    private final boolean available;
    private Method isDisguisedMethod;
    private Method undisguiseToAllMethod;

    public LibsDisguisesBridge(@NotNull Logger logger, @NotNull PermissionGate permissionGate) {
        this.logger = logger;
        this.permissionGate = permissionGate;
        this.available = detect();
    }

    public boolean isAvailable() {
        return available;
    }

    /** @return whether {@code player} is currently disguised, or {@code false} if the bridge isn't available. */
    public boolean isDisguised(@NotNull Player player) {
        if (!available) {
            return false;
        }
        try {
            return (boolean) isDisguisedMethod.invoke(null, (Entity) player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("LibsDisguises isDisguised() call failed for " + player.getName() + ": " + e);
            return false;
        }
    }

    /**
     * Undisguises every online disguised player. A no-op if the bridge isn't available.
     *
     * @param includeStaff if {@code false}, Moderator+ players are left disguised - matches
     *     TFM's own {@code isAdmin}-exempt behavior, translated onto this project's rank ladder
     */
    public void undisguiseAll(boolean includeStaff) {
        if (!available) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!includeStaff && permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
                continue;
            }
            if (!isDisguised(player)) {
                continue;
            }
            try {
                undisguiseToAllMethod.invoke(null, (Entity) player);
            } catch (ReflectiveOperationException | RuntimeException e) {
                logger.warning("LibsDisguises undisguiseToAll() call failed for " + player.getName() + ": " + e);
            }
        }
    }

    private boolean detect() {
        try {
            Plugin libsDisguises = Bukkit.getPluginManager().getPlugin("LibsDisguises");
            if (libsDisguises == null || !libsDisguises.isEnabled()) {
                return false;
            }
            ClassLoader pluginClassLoader = libsDisguises.getClass().getClassLoader();
            Class<?> disguiseApi = resolveDisguiseApiClass(pluginClassLoader);
            if (disguiseApi == null) {
                logger.warning("LibsDisguises found but its DisguiseAPI class wasn't accessible - disguise"
                        + " integration disabled.");
                return false;
            }
            if (!resolveMethods(disguiseApi, pluginClassLoader)) {
                logger.warning(
                        "LibsDisguises found but isDisguised/undisguiseToAll methods weren't accessible on"
                                + " DisguiseAPI - disguise integration disabled.");
                return false;
            }
            logger.info("LibsDisguises detected - disguise integration active.");
            return true;
        } catch (RuntimeException e) {
            logger.info("LibsDisguises not found - disguise integration disabled.");
            return false;
        }
    }

    private Class<?> resolveDisguiseApiClass(ClassLoader pluginClassLoader) {
        for (String candidate :
                new String[] {"me.libraryaddict.disguise.DisguiseAPI", "me.libraryaddict.disguise.api.DisguiseAPI"}) {
            try {
                return Class.forName(candidate, true, pluginClassLoader);
            } catch (ClassNotFoundException ignored) {
                // try the next candidate class name
            }
        }
        return null;
    }

    /** Tries the {@code Entity}-typed overload first, falls back to {@code Player}-typed (older LibsDisguises). */
    private boolean resolveMethods(Class<?> disguiseApi, ClassLoader pluginClassLoader) {
        try {
            Class<?> entityClass = Class.forName("org.bukkit.entity.Entity", true, pluginClassLoader);
            isDisguisedMethod = disguiseApi.getMethod("isDisguised", entityClass);
            undisguiseToAllMethod = disguiseApi.getMethod("undisguiseToAll", entityClass);
            return true;
        } catch (ReflectiveOperationException ignored) {
            // fall through to the Player-typed overload below
        }
        try {
            isDisguisedMethod = disguiseApi.getMethod("isDisguised", Player.class);
            undisguiseToAllMethod = disguiseApi.getMethod("undisguiseToAll", Player.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
