package org.rigelmc.protect.worldedit;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.protect.antigrief.PerPlayerRateLimiter;
import org.rigelmc.punish.warn.StrikeService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.world.SpawnService;

/**
 * Command-preprocess-level pre-checks against WorldEdit/FAWE abuse - TFM ref: {@code
 * bridge/WorldEditHook.java}, studied directly. This is the general-purpose, lowest-risk
 * layer (op throttle, blocked-block-type matching, radius cap), scoped to pure string/
 * regex matching on the raw command text rather than WorldEdit's own live session/
 * selection API - see {@code worldedit.WorldEditBridge}'s javadoc for the same "verified,
 * lower-risk first" scoping rationale applied elsewhere in this batch.
 *
 * <p><b>Deliberately not included in this pass</b>: selection-volume capping and
 * {@code EditSessionEvent} pipeline-level extent wrapping, both of which need real
 * WorldEdit session-API integration this pass doesn't attempt without a live server to
 * verify against - flagged as a follow-up, not silently dropped.</p>
 *
 * <p>Detects a WorldEdit/FAWE-owned command the same way {@code protect.ProtectModule}
 * resolves command aliases: via the live {@code CommandMap}, checking the resolved
 * command's owning plugin name. Robust regardless of exactly which of WorldEdit's many
 * individually-registered command labels (including its {@code //}-shorthand aliases,
 * which Bukkit resolves to a literal {@code "/set"}-style label after stripping one
 * leading slash) a player actually types, without needing to hand-enumerate them.</p>
 *
 * <p>The radius/volume caps this checks are read through {@link WorldEditLimitOverrideService}
 * (user-requested: {@code /worldeditlimit} lets a Senior Admin raise/lower them at runtime,
 * in-memory only, without editing {@code config.yml}) - see that class's own javadoc.</p>
 *
 * <p>Also applies a much tighter radius cap near the server's configured spawn point
 * (user-requested, {@code protect.worldedit.spawn-protection.*}) - see {@link
 * #checkRadius}'s own javadoc for the full spawn-protection design, split across this
 * command-preprocess layer and the deeper extent-chain one ({@code
 * extent.SpawnProtectionExtent}).</p>
 */
public final class WorldEditAbuseGuard implements Listener {

    /** TFM's own {@code RADIUS_COMMANDS} set (position-agnostic here - see {@link #checkRadius}). */
    private static final Set<String> RADIUS_COMMANDS = Set.of(
            "replacenear", "drain", "fixwater", "fixlava", "snow", "thaw", "ex", "butcher", "removeabove",
            "removebelow", "forestgen", "forest", "pumpkins", "sphere", "hsphere", "cyl", "hcyl", "pyramid",
            "removenear", "blob", "rock");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final StrikeService strikeService;
    private final WorldEditLimitOverrideService limitOverrideService;
    private final SpawnService spawnService;
    private final PerPlayerRateLimiter<UUID> throttle;

    public WorldEditAbuseGuard(
            @NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate, @NotNull StrikeService strikeService,
            @NotNull WorldEditLimitOverrideService limitOverrideService, @NotNull SpawnService spawnService) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
        this.strikeService = strikeService;
        this.limitOverrideService = limitOverrideService;
        this.spawnService = spawnService;
        this.throttle = new PerPlayerRateLimiter<>(plugin.rigelConfig().worldEditThrottleWindowMs());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        if (!plugin.rigelConfig().worldEditGuardEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
            return;
        }

        String rawMessage = event.getMessage();
        String withoutSlash = rawMessage.startsWith("/") ? rawMessage.substring(1) : rawMessage;
        String[] parts = withoutSlash.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (!isWorldEditCommand(label)) {
            return;
        }

        if (checkThrottle(event, player)) {
            return;
        }
        if (checkBlockedBlockTypes(event, player, parts)) {
            return;
        }
        checkRadius(event, player, label, parts);
    }

    private boolean isWorldEditCommand(String label) {
        Command command = Bukkit.getServer().getCommandMap().getCommand(label);
        if (!(command instanceof PluginCommand pluginCommand)) {
            return false;
        }
        String owner = pluginCommand.getPlugin().getName();
        return "WorldEdit".equalsIgnoreCase(owner) || "FastAsyncWorldEdit".equalsIgnoreCase(owner);
    }

    /** @return {@code true} if the command was cancelled (caller should stop further checks) */
    private boolean checkThrottle(PlayerCommandPreprocessEvent event, Player player) {
        if (!plugin.rigelConfig().worldEditThrottleEnabled()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        int maxOps = plugin.rigelConfig().worldEditThrottleMaxOps();
        int count = throttle.recordAndCount(uuid, System.currentTimeMillis());
        if (count <= maxOps) {
            return false;
        }

        event.setCancelled(true);
        player.sendMessage(
                Component.text("You're using WorldEdit commands too quickly - slow down.", NamedTextColor.RED));

        if (count - maxOps >= plugin.rigelConfig().worldEditThrottleEjectThreshold()) {
            try {
                strikeService.recordStrike(uuid, 24, System.currentTimeMillis());
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to record a strike for WorldEdit throttle abuse: " + e);
            }
            player.kick(Component.text("Disconnected: WorldEdit command spam.", NamedTextColor.RED));
        }
        return true;
    }

    /**
     * Exact-match after normalizing each sub-token (splitting on pattern-syntax
     * delimiters like {@code ,}/{@code %}, stripping a {@code minecraft:} namespace and
     * any {@code [block-state]} suffix) - not substring containment, which would false-
     * positive a legitimate block like {@code sandstone} against a blocked {@code sand}.
     *
     * @return {@code true} if the command was cancelled
     */
    private boolean checkBlockedBlockTypes(PlayerCommandPreprocessEvent event, Player player, String[] parts) {
        List<String> blocked = plugin.rigelConfig().worldEditBlockedBlockTypes();
        if (blocked.isEmpty()) {
            return false;
        }
        Set<String> blockedNormalized = new HashSet<>();
        for (String type : blocked) {
            blockedNormalized.add(normalize(type));
        }

        for (int i = 1; i < parts.length; i++) {
            for (String subToken : parts[i].split("[,%|]")) {
                if (blockedNormalized.contains(normalize(subToken))) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text(
                            "That WorldEdit command references a blocked block type - not permitted.",
                            NamedTextColor.RED));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * User-reported real gap, fixed here: several radius-taking commands (notably {@code
     * //hsphere}/{@code //sphere}) accept a comma-separated {@code radius,radius,radius}
     * form for a non-uniform ellipsoid, not just a single number - the original version
     * matched each whole argument token against {@link #NUMBER} with {@code .matches()},
     * which never matches a token containing a comma at all, silently skipping the check
     * entirely for that argument. Splitting each token on {@code ,} first and checking
     * every resulting sub-number closes that off without weakening anything for the
     * ordinary single-number case (a token with no comma just splits into itself).
     *
     * <p>Applies {@link RigelConfig#worldEditSpawnProtectionMaxRadius()} instead of the
     * general cap when the player is currently standing within {@link
     * RigelConfig#worldEditSpawnProtectionRadius()} of the configured spawn point
     * (user-requested) - the command-preprocess-layer half of spawn protection; see
     * {@code extent.SpawnProtectionExtent} for the deeper, per-block-position half.</p>
     */
    private void checkRadius(PlayerCommandPreprocessEvent event, Player player, String label, String[] parts) {
        if (!RADIUS_COMMANDS.contains(label)) {
            return;
        }
        int max = limitOverrideService.effectiveRadiusMax(plugin.rigelConfig().worldEditRadiusMax());
        if (isNearSpawn(player)) {
            int spawnMax = plugin.rigelConfig().worldEditSpawnProtectionMaxRadius();
            if (spawnMax >= 0 && (max <= 0 || spawnMax < max)) {
                max = spawnMax;
            }
        }
        if (max <= 0) {
            return;
        }
        for (int i = 1; i < parts.length; i++) {
            for (String subToken : parts[i].split(",")) {
                if (NUMBER.matcher(subToken).matches() && Math.abs(Double.parseDouble(subToken)) > max) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text(
                            "That radius/size exceeds the server limit (" + max + ").", NamedTextColor.RED));
                    return;
                }
            }
        }
    }

    /** @return {@code true} if spawn-protection is enabled, a spawn point is set, and {@code player} is currently within range of it. */
    private boolean isNearSpawn(Player player) {
        RigelConfig config = plugin.rigelConfig();
        if (!config.worldEditSpawnProtectionEnabled()) {
            return false;
        }
        Optional<Location> spawn = spawnService.spawnLocation();
        if (spawn.isEmpty() || !player.getWorld().equals(spawn.get().getWorld())) {
            return false;
        }
        double radius = config.worldEditSpawnProtectionRadius();
        return player.getLocation().distanceSquared(spawn.get()) <= radius * radius;
    }

    /**
     * Public (not just this class's own concern) so {@code extent.BlockedTypeExtent} can
     * apply the exact same normalization to a live WorldEdit {@code BlockType}'s id string
     * as this class already applies to a raw command-argument token - one shared
     * definition of "what does a blocked-type entry actually match," used at both the
     * command-preprocess layer (here) and the {@code EditSessionEvent} extent layer.
     */
    public static String normalize(String token) {
        String lower = token.toLowerCase(Locale.ROOT).trim();
        int percent = lower.lastIndexOf('%');
        if (percent >= 0 && percent < lower.length() - 1) {
            lower = lower.substring(percent + 1); // strip a leading pattern weight, e.g. "70%stone"
        }
        int bracket = lower.indexOf('[');
        if (bracket >= 0) {
            lower = lower.substring(0, bracket); // strip a block-state suffix, e.g. "[axis=x]"
        }
        int colon = lower.indexOf(':');
        if (colon >= 0) {
            lower = lower.substring(colon + 1); // strip a "minecraft:" namespace
        }
        return lower;
    }
}
