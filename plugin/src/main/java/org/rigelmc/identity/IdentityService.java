package org.rigelmc.identity;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * Resolves a joining player's {@link PlayerIdentity}.
 *
 * <p>Detects Bedrock players via Floodgate's API through reflection rather than a
 * compile-time dependency: Floodgate has never published a stable (non-SNAPSHOT) API
 * release, which makes pinning it as a normal {@code compileOnly} Maven coordinate
 * fragile. Reflection keeps this a true soft integration - if Floodgate isn't installed,
 * or its API shape changes, detection just falls back to {@link PlayerIdentity#OFFLINE}
 * or {@link PlayerIdentity#JAVA} rather than failing to load.</p>
 *
 * <p>For non-Bedrock players, {@code Bukkit.getOnlineMode()} alone isn't a reliable
 * signal of "this is a verified premium account" on this project's own documented
 * Velocity-proxied topology - the backend typically runs offline-mode even for
 * legitimately premium players, since Velocity's modern forwarding handles real
 * authentication itself. {@code identity.online-mode} in config.yml lets an operator
 * assert the truth explicitly instead of trusting that unreliable flag - see
 * {@link #resolve(UUID, RigelConfig)}.</p>
 */
public final class IdentityService {

    private static final String FLOODGATE_API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";

    private final Logger logger;
    private final boolean floodgateAvailable;

    public IdentityService(@NotNull Logger logger) {
        this.logger = logger;
        this.floodgateAvailable = isClassPresent(FLOODGATE_API_CLASS);
        if (floodgateAvailable) {
            logger.info("Floodgate detected - Bedrock players will be identified via its API.");
        }
    }

    /**
     * @param playerUuid the joining player's UUID (as seen by this backend server)
     * @param config current config - consulted for {@code identity.online-mode}; pass
     *     {@code plugin.rigelConfig()} fresh each call, not a captured reference, so
     *     {@code /rmcm reload} takes effect without a restart
     * @return the resolved identity type; falls back to online-mode inference (config
     *     override, then Bukkit's own flag) if Floodgate is absent or its reflective call
     *     fails for any reason
     */
    @NotNull
    public PlayerIdentity resolve(@NotNull UUID playerUuid, @NotNull RigelConfig config) {
        if (floodgateAvailable && isFloodgatePlayer(playerUuid)) {
            return PlayerIdentity.BEDROCK;
        }
        boolean onlineMode = config.identityOnlineMode() || Bukkit.getOnlineMode();
        return onlineMode ? PlayerIdentity.JAVA : PlayerIdentity.OFFLINE;
    }

    private boolean isFloodgatePlayer(UUID playerUuid) {
        try {
            Class<?> apiClass = Class.forName(FLOODGATE_API_CLASS);
            Object instance = apiClass.getMethod("getInstance").invoke(null);
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            return (boolean) isFloodgatePlayer.invoke(instance, playerUuid);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Floodgate identity check failed, falling back to online-mode inference: " + e);
            return false;
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, IdentityService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
