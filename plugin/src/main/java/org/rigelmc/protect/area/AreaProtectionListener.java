package org.rigelmc.protect.area;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bukkit-event enforcement for every {@link AreaFlag} - ported from TFM's real {@code
 * ProtectArea} handler list, each mapped onto the 6-flag model instead of TFM's single
 * blanket "protected" boolean, with the exemption check being {@link
 * ProtectAreaService#isBypassing} (member/owner or Moderator+) everywhere there's an
 * acting player. See {@code protect.area}'s package documentation for the full flag table.
 *
 * <p>Closes one real TFM coverage gap: {@link #onInventoryMove} ({@code
 * InventoryMoveItemEvent}, hopper/container-to-container transfer) has no TFM equivalent at
 * all - TFM only covers item-entity pickup ({@code EntityPickupItemEvent}/{@code
 * InventoryPickupItemEvent}), so a hopper chain can silently siphon items straight out of a
 * "protected" region in real TFM.</p>
 *
 * <p>Adds one flag with no TFM equivalent at all: {@link #onMove} ({@link AreaFlag#ENTRY}) -
 * TFM's protection only ever stops modification, never entry itself.</p>
 */
public final class AreaProtectionListener implements Listener {

    private static final Component PROTECTED_MESSAGE =
            Component.text("This area is protected.", NamedTextColor.RED);
    private static final long DENY_MESSAGE_COOLDOWN_MILLIS = 1500L;

    private final ProtectAreaService service;
    private final Map<UUID, Long> lastDenyMessageAt = new ConcurrentHashMap<>();

    public AreaProtectionListener(@NotNull ProtectAreaService service) {
        this.service = service;
    }

    // ---- BUILD --------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        deny(event.getPlayer(), event.getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        deny(event.getPlayer(), event.getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(@NotNull PlayerBucketEmptyEvent event) {
        deny(event.getPlayer(), event.getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(@NotNull PlayerBucketFillEvent event) {
        deny(event.getPlayer(), event.getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(@NotNull BlockIgniteEvent event) {
        deny(event.getPlayer(), event.getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(@NotNull BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE) {
            return;
        }
        denyUnconditional(event.getBlock(), AreaFlag.BUILD, event);
    }

    /** Unconditional - no acting player, matching TFM's own "no admin gate" behavior on this one. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(@NotNull EntityChangeBlockEvent event) {
        denyUnconditional(event.getBlock(), AreaFlag.BUILD, event);
    }

    /** Unconditional, matching TFM. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(@NotNull BlockBurnEvent event) {
        denyUnconditional(event.getBlock(), AreaFlag.BUILD, event);
    }

    /** Unconditional, matching TFM. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockFade(@NotNull BlockFadeEvent event) {
        denyUnconditional(event.getBlock(), AreaFlag.BUILD, event);
    }

    /** Only cancels flow INTO a protected region from outside it - matches TFM's exact scoping. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(@NotNull BlockFromToEvent event) {
        Block to = event.getToBlock();
        World world = to.getWorld();
        Optional<AreaRegion> toRegion = service.effectiveRegionAt(world.getName(), to.getX(), to.getY(), to.getZ());
        if (toRegion.isEmpty() || toRegion.get().effectiveFlag(AreaFlag.BUILD)) {
            return;
        }
        Block from = event.getBlock();
        Optional<AreaRegion> fromRegion =
                service.effectiveRegionAt(world.getName(), from.getX(), from.getY(), from.getZ());
        if (fromRegion.isPresent() && fromRegion.get().id() == toRegion.get().id()) {
            return; // flow staying within the same region is fine
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(@NotNull BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isDeniedUnconditional(block.getRelative(event.getDirection()), AreaFlag.BUILD)
                    || isDeniedUnconditional(block, AreaFlag.BUILD)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(@NotNull BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isDeniedUnconditional(block, AreaFlag.BUILD)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(@NotNull HangingPlaceEvent event) {
        deny(event.getPlayer(), event.getEntity().getLocation().getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(@NotNull HangingBreakByEntityEvent event) {
        Player player = event.getRemover() instanceof Player p ? p : null;
        deny(player, event.getEntity().getLocation().getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDestroy(@NotNull VehicleDestroyEvent event) {
        Player player = event.getAttacker() instanceof Player p ? p : null;
        deny(player, event.getVehicle().getLocation().getBlock(), AreaFlag.BUILD, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(@NotNull SignChangeEvent event) {
        deny(event.getPlayer(), event.getBlock(), AreaFlag.BUILD, event);
    }

    // ---- EXPLOSIONS ---------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(@NotNull EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isDeniedUnconditional(block, AreaFlag.EXPLOSIONS));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(@NotNull BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isDeniedUnconditional(block, AreaFlag.EXPLOSIONS));
    }

    // ---- INTERACT -----------------------------------------------------------------------

    /** Physical (pressure plate/crop trample) and right-click-with-an-item-in-hand only - matches TFM's exact scoping. */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        boolean relevant = event.getAction() == Action.PHYSICAL
                || (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getItem() != null);
        if (relevant) {
            deny(event.getPlayer(), block, AreaFlag.INTERACT, event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        deny(event.getPlayer(), event.getRightClicked().getLocation().getBlock(), AreaFlag.INTERACT, event);
    }

    // ---- PVP ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        deny(victim, victim.getLocation().getBlock(), AreaFlag.PVP, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(@NotNull PotionSplashEvent event) {
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player player && isDenied(player, AreaFlag.PVP)) {
                event.setIntensity(affected, 0.0D);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringPotionSplash(@NotNull LingeringPotionSplashEvent event) {
        Location at = event.getEntity().getLocation();
        Optional<AreaRegion> region = service.effectiveRegionAt(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (region.isPresent() && !region.get().effectiveFlag(AreaFlag.PVP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaEffectCloudApply(@NotNull AreaEffectCloudApplyEvent event) {
        Iterator<LivingEntity> iterator = event.getAffectedEntities().iterator();
        while (iterator.hasNext()) {
            LivingEntity affected = iterator.next();
            if (affected instanceof Player player && isDenied(player, AreaFlag.PVP)) {
                iterator.remove();
            }
        }
    }

    // ---- ITEM_PICKUP ----------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onItemPickup(@NotNull EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            deny(player, player.getLocation().getBlock(), AreaFlag.ITEM_PICKUP, event);
        }
    }

    /** Hopper pickup - no bypass check needed, hoppers aren't players, matching TFM. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickupItem(@NotNull InventoryPickupItemEvent event) {
        denyUnconditional(event.getInventory().getLocation(), AreaFlag.ITEM_PICKUP, event);
    }

    /**
     * Hopper/container-to-container transfer - see this class's javadoc, this is the real
     * TFM coverage gap this closes. Unconditional, same rationale as hopper pickup above.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(@NotNull InventoryMoveItemEvent event) {
        if (isDeniedUnconditional(event.getSource().getLocation(), AreaFlag.ITEM_PICKUP)
                || isDeniedUnconditional(event.getDestination().getLocation(), AreaFlag.ITEM_PICKUP)) {
            event.setCancelled(true);
        }
    }

    // ---- ENTRY - new, no TFM equivalent --------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // cheap early-out - only re-check on an actual block-to-block transition
        }
        deny(event.getPlayer(), to.getBlock(), AreaFlag.ENTRY, event);
    }

    // ---- stray-item sweep -----------------------------------------------------------------

    /**
     * Removes {@link Item} entities sitting inside any {@link AreaFlag#ITEM_PICKUP}-denied
     * region - matches TFM's own periodic sweep (its {@code ITEM_SWEEP_RATE}, every 40
     * ticks). Scoped per-region via {@link World#getNearbyEntities(BoundingBox)} rather than
     * a full {@code world.getEntities()} scan, since the region list is typically far
     * smaller than the entity count on a busy server.
     */
    public void sweepStrayItems() {
        for (AreaRegion region : service.list()) {
            if (!region.enabled() || region.effectiveFlag(AreaFlag.ITEM_PICKUP)) {
                continue;
            }
            World world = Bukkit.getWorld(region.record().world());
            if (world == null) {
                continue;
            }
            BoundingBox box = new BoundingBox(
                    region.record().minX(), region.record().minY(), region.record().minZ(),
                    region.record().maxX() + 1.0, region.record().maxY() + 1.0, region.record().maxZ() + 1.0);
            for (Entity entity : world.getNearbyEntities(box)) {
                if (entity instanceof Item) {
                    entity.remove();
                }
            }
        }
    }

    // ---- shared helpers -------------------------------------------------------------------

    /** Cancels {@code event} (via {@link org.bukkit.event.Cancellable}) and notifies {@code player} if denied. */
    private void deny(@Nullable Player player, @NotNull Block block, @NotNull AreaFlag flag, @NotNull org.bukkit.event.Cancellable event) {
        if (isDenied(player, block, flag)) {
            event.setCancelled(true);
            if (player != null) {
                notify(player);
            }
        }
    }

    /** No acting player at all - always enforced, matching TFM's "no admin gate" handlers. */
    private void denyUnconditional(@NotNull Block block, @NotNull AreaFlag flag, @NotNull org.bukkit.event.Cancellable event) {
        if (isDeniedUnconditional(block, flag)) {
            event.setCancelled(true);
        }
    }

    private void denyUnconditional(@NotNull Location location, @NotNull AreaFlag flag, @NotNull org.bukkit.event.Cancellable event) {
        if (isDeniedUnconditional(location, flag)) {
            event.setCancelled(true);
        }
    }

    private boolean isDenied(@Nullable Player player, @NotNull Block block, @NotNull AreaFlag flag) {
        Optional<AreaRegion> region = service.effectiveRegionAt(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (region.isEmpty() || region.get().effectiveFlag(flag)) {
            return false;
        }
        return player == null || !service.isBypassing(player, region.get());
    }

    private boolean isDenied(@NotNull Player player, @NotNull AreaFlag flag) {
        return isDenied(player, player.getLocation().getBlock(), flag);
    }

    private boolean isDeniedUnconditional(@NotNull Block block, @NotNull AreaFlag flag) {
        return isDenied(null, block, flag);
    }

    private boolean isDeniedUnconditional(@NotNull Location location, @NotNull AreaFlag flag) {
        return isDenied(null, location.getBlock(), flag);
    }

    /** Rate-limited so a rapid string of denials (e.g. walking into an ENTRY-denied wall) doesn't spam chat. */
    private void notify(@NotNull Player player) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMessageAt.get(player.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastDenyMessageAt.put(player.getUniqueId(), now);
        player.sendMessage(PROTECTED_MESSAGE);
    }
}
