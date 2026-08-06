package org.rigelmc.rollback;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Thin, reflection-based bridge to CoreProtect's public rollback API - backs the
 * mandatory rollback {@code /ban} always triggers and the opt-in {@code -r}/
 * {@code --rollback} flag on {@code /tban}. CoreProtect is a strongly-recommended
 * companion plugin, not reimplemented; if it isn't installed, callers just skip the
 * rollback step and the ban itself still succeeds - see docs/architecture.md's
 * "rollback/" module design.
 *
 * <p>Reflection (rather than a compile-time dependency) because CoreProtect isn't
 * published to a Maven repository this project can depend on cleanly - the same
 * soft-integration pattern used for Floodgate in {@link org.rigelmc.identity.IdentityService}.
 * The {@code performRollback} signature below is sourced from CoreProtect's own public
 * API docs (API v11); <b>this has not been exercised against a live CoreProtect
 * install</b> in this session, only compiled against the documented shape reflectively -
 * treat the exact radius/location semantics as unverified until confirmed against a real
 * server, per docs/architecture.md's Phase 3 CoreProtect integration note.</p>
 */
public final class CoreProtectBridge {

    private static final int MINIMUM_API_VERSION = 11;

    private final Logger logger;
    private final boolean available;
    private Object api; // net.coreprotect.CoreProtectAPI instance, held reflectively

    public CoreProtectBridge(@NotNull Logger logger) {
        this.logger = logger;
        this.available = detect();
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Rolls back the named player's actions over the given lookback window. Per
     * CoreProtect's own API contract this must run off the main thread - callers must
     * already be on an async context (e.g. RigelMCMod's DB executor) before calling
     * this. A no-op (logged, non-fatal) if CoreProtect isn't installed or the reflective
     * call fails for any reason - a rollback failure must never fail the ban itself.
     */
    public void rollbackPlayer(@NotNull String playerName, @NotNull Duration lookback) {
        if (!available) {
            logger.info("CoreProtect not installed - skipping automatic rollback for " + playerName + ".");
            return;
        }
        try {
            Method performRollback = api.getClass()
                    .getMethod(
                            "performRollback",
                            int.class,
                            List.class,
                            List.class,
                            List.class,
                            List.class,
                            List.class,
                            int.class,
                            org.bukkit.Location.class);
            int timeSeconds = (int) lookback.toSeconds();
            performRollback.invoke(
                    api, timeSeconds, List.of(playerName), List.of(), List.of(), List.of(), List.of(), 0, null);
            logger.info("Triggered CoreProtect rollback for " + playerName + " (last " + lookback + ").");
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("CoreProtect rollback call failed for " + playerName + ": " + e);
        }
    }

    /**
     * Wipes CoreProtect's entire logging/rollback database. Not a reflective internal
     * reset - purging isn't part of CoreProtect's <i>public</i> API (only {@code
     * performRollback}/{@code performRestore}/lookup methods are), so this instead
     * dispatches CoreProtect's own {@code /co purge} console command, exactly the way an
     * operator would type it themselves. Backs {@code /wipecoreprotect confirm} in
     * {@code punish.PunishModule}.
     *
     * <p>Real CoreProtect requires its own destructive commands (rollback/restore/purge)
     * to be run a second time by the same sender, shortly after the first, as a built-in
     * confirmation safeguard - the first dispatch only arms it. This dispatches the same
     * console command twice, a few ticks apart, so a single {@code /wipecoreprotect
     * confirm} satisfies that from one RigelMCMod command invocation. If that assumption
     * turns out to be wrong (e.g. CoreProtect doesn't actually require re-confirmation,
     * or uses a different window), the second dispatch is simply a harmless no-op with
     * nothing left to purge - either way this is safe to call. <b>Unverified against a
     * live CoreProtect install</b> in this session, same caveat as {@link #rollbackPlayer}
     * - confirm the double-dispatch actually completes a full wipe against a real server
     * before relying on this in production.</p>
     *
     * <p>Must be called from the main thread (dispatches through Bukkit's synchronous
     * command map, and schedules the follow-up dispatch via {@link
     * org.bukkit.scheduler.BukkitScheduler}).</p>
     *
     * @param plugin used only to schedule the follow-up dispatch a few ticks later
     */
    public void purgeAll(@NotNull org.bukkit.plugin.Plugin plugin) {
        if (!available) {
            logger.warning("Cannot wipe CoreProtect's database - CoreProtect isn't installed or isn't ready.");
            return;
        }
        org.bukkit.command.CommandSender console = Bukkit.getConsoleSender();
        // "1s" - purge everything logged 1 second ago or earlier, i.e. everything for all
        // practical purposes. CoreProtect's time parser requires a positive value.
        Bukkit.dispatchCommand(console, "co purge 1s");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.dispatchCommand(console, "co purge 1s");
            logger.info("Dispatched 'co purge 1s' twice via console to wipe CoreProtect's database "
                    + "(second dispatch confirms CoreProtect's own repeat-to-confirm prompt, if any).");
        }, 5L);
    }

    private boolean detect() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (plugin == null || !plugin.isEnabled()) {
                return false;
            }
            Object apiCandidate = plugin.getClass().getMethod("getAPI").invoke(plugin);
            boolean enabled = (boolean) apiCandidate.getClass().getMethod("isEnabled").invoke(apiCandidate);
            int version = (int) apiCandidate.getClass().getMethod("APIVersion").invoke(apiCandidate);
            if (!enabled || version < MINIMUM_API_VERSION) {
                logger.info(
                        "CoreProtect found but not ready (enabled=" + enabled + ", apiVersion=" + version
                                + ") - automatic rollback disabled.");
                return false;
            }
            this.api = apiCandidate;
            logger.info("CoreProtect detected (API v" + version + ") - ban rollback integration active.");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.info("CoreProtect not found - automatic rollback on ban is disabled.");
            return false;
        }
    }
}
