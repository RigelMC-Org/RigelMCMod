package org.rigelmc.protect.antigrief;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * User-requested, after a live incident: a player placed a repeating command block running
 * {@code /give all netherite_axe}, which fired hundreds of times per second and handed the
 * whole server netherite. Command blocks are now blocked outright - they cannot be placed,
 * and any that already exist delete themselves the moment they next try to run a command.
 *
 * <p><b>The hole this closes.</b> {@code protect.command-access} is enforced by {@code
 * protect.BlockedCommandListener}, whose only hook is {@code PlayerCommandPreprocessEvent}
 * and which calls {@code event.getPlayer()} unconditionally - it is structurally player-only.
 * A command block is not a player, so <i>every</i> command-access rule was bypassed by one,
 * including unconditional {@code n:b:} "nobody" rules. It matters more on this server than
 * most: every player is auto-op'd ({@code core.AutoOpModule}), so anyone could place and
 * program a command block by default.
 *
 * <p>TFM ref: {@code blocking.command.CommandBlocker#onServerCommand}, studied directly from
 * its real source - it hooks the same {@link ServerCommandEvent} and tests the same {@code
 * BlockCommandSender}/{@code CommandMinecart} pair, confirming the hook choice against a
 * production implementation rather than a guess. There is no dedicated command-block event
 * in the Paper API (verified against the 26.1.2 jar); this is the only one that sees them.
 *
 * <p><b>The positive {@code instanceof} allowlist in {@link #onServerCommand} is
 * load-bearing, not stylistic.</b> {@code RemoteServerCommandEvent extends
 * ServerCommandEvent}, so a guard written as "block anything that isn't a player" would
 * silently break <b>RCON</b> - and plain console commands and every plugin-dispatched
 * command ({@code /store grant}, {@code /vote record}) would go with it. Matching only the
 * two command-block sender types can never do that.
 *
 * <p><b>Alert throttling is also load-bearing.</b> A repeating command block fires every
 * tick; calling {@link AntiGriefSupport#notifyStaff} per blocked execution would itself be
 * the lag bomb the guard exists to stop (that helper does no throttling of its own). At most
 * one alert per command-block location per {@code alert-interval-seconds} is emitted. TFM
 * ships a {@code log_interval_ticks} setting for exactly this reason.
 *
 * <p>Reads {@code plugin.rigelConfig()} fresh on every event rather than caching a reference
 * - see {@link AntiNukeGuard}'s javadoc for why.</p>
 */
public final class CommandBlockGuard implements Listener {

    /**
     * Safety cap on {@link #lastAlertAt}. Entries are only ever added when an alert actually
     * fires, and with {@code remove-on-execute} on the offending block is deleted moments
     * later, so this map stays tiny in practice - this only exists so a pathological world
     * full of command blocks can't grow it without bound.
     */
    private static final int MAX_TRACKED_ALERT_LOCATIONS = 256;

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    /** Command-block location -> when it last produced a staff alert. See the class javadoc. */
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    public CommandBlockGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    /**
     * {@code LOWEST} so the cancellation lands before anything else can act on the command,
     * and deliberately <b>without</b> {@code ignoreCancelled} - if another plugin cancelled
     * the command first we still want to remove the block, otherwise it keeps re-firing
     * forever.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(@NotNull ServerCommandEvent event) {
        CommandSender sender = event.getSender();
        boolean fromCommandBlock = sender instanceof BlockCommandSender;
        boolean fromCommandMinecart = sender instanceof CommandMinecart;
        // Positive allowlist - see the class javadoc. Console, RCON (RemoteServerCommandEvent
        // extends this event), and plugin-dispatched commands can never match these two.
        if (!plugin.rigelConfig().commandBlockGuardEnabled() || (!fromCommandBlock && !fromCommandMinecart)) {
            return;
        }
        event.setCancelled(true);

        RigelConfig config = plugin.rigelConfig();
        if (fromCommandBlock) {
            Block block = ((BlockCommandSender) sender).getBlock();
            maybeAlert(config, locationKey(block), "at " + block.getX() + "," + block.getY() + "," + block.getZ()
                    + " in " + block.getWorld().getName(), event.getCommand());
            if (config.commandBlockRemoveOnExecute()) {
                // Next tick, not now - removing a block entity from inside its own tick is
                // asking for trouble.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCommandBlock(block.getType())) {
                        block.setType(Material.AIR);
                    }
                });
            }
            return;
        }

        CommandMinecart minecart = (CommandMinecart) sender;
        maybeAlert(config, minecart.getUniqueId().toString(), "minecart " + minecart.getUniqueId(), event.getCommand());
        if (config.commandBlockRemoveOnExecute()) {
            Bukkit.getScheduler().runTask(plugin, minecart::remove);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        RigelConfig config = plugin.rigelConfig();
        if (!config.commandBlockGuardEnabled()) {
            return;
        }
        Material placed = event.getBlockPlaced().getType();
        if (!isBlockedAdminBlock(placed, config.commandBlockBlockStructureBlocks())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "Placing " + placed.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                        + " is not allowed on this server.",
                NamedTextColor.RED));
    }

    /**
     * A command minecart is an entity, not a block, so {@link BlockPlaceEvent} never sees it -
     * this covers placing the item on a rail, a dispenser placing one, and anything else that
     * spawns one.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(@NotNull EntitySpawnEvent event) {
        if (plugin.rigelConfig().commandBlockGuardEnabled() && event.getEntity() instanceof CommandMinecart) {
            event.setCancelled(true);
        }
    }

    /** At most one staff alert + log line per source per {@code alert-interval-seconds} - see the class javadoc. */
    private void maybeAlert(RigelConfig config, String key, String whereDescription, String command) {
        long intervalMillis = Math.max(config.commandBlockAlertIntervalSeconds(), 0) * 1000L;
        long now = System.currentTimeMillis();
        Long previous = lastAlertAt.get(key);
        if (previous != null && now - previous < intervalMillis) {
            return;
        }
        if (lastAlertAt.size() > MAX_TRACKED_ALERT_LOCATIONS) {
            lastAlertAt.clear();
        }
        lastAlertAt.put(key, now);

        String message = "[CommandBlock] Blocked a command block " + whereDescription + " running: " + command;
        plugin.getLogger().warning(message);
        AntiGriefSupport.notifyStaff(permissionGate, Component.text(message, NamedTextColor.RED));
    }

    private static String locationKey(Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }

    /** The three command-block variants - the ones that can actually run a command. */
    static boolean isCommandBlock(@NotNull Material material) {
        return material == Material.COMMAND_BLOCK
                || material == Material.CHAIN_COMMAND_BLOCK
                || material == Material.REPEATING_COMMAND_BLOCK;
    }

    /**
     * Pure decision, split out so it is unit-testable without a live server - matching this
     * project's existing "keep the decision pure, keep the Bukkit wrapper thin" pattern (see
     * {@code guild.plot.PlotWorldTerrain}).
     *
     * @param includeStructureBlocks whether {@code protect.anti-grief.command-blocks.block-structure-blocks}
     *     is on. Structure and jigsaw blocks can't run commands, but they're the same class of
     *     op-only admin block, and on a Free-OP server every player can place them.
     */
    public static boolean isBlockedAdminBlock(@NotNull Material material, boolean includeStructureBlocks) {
        if (isCommandBlock(material)) {
            return true;
        }
        return includeStructureBlocks && (material == Material.STRUCTURE_BLOCK || material == Material.JIGSAW);
    }
}
