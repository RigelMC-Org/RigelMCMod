package org.rigelmc.protect.crash;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.protect.antigrief.PerPlayerRateLimiter;
import org.rigelmc.rank.PermissionGate;

/**
 * Screens items for the crash-exploit payload shapes TFM's own {@code ItemScanner}/{@code
 * ItemValidator} defend against (studied directly from its real source), scoped to Paper's
 * <b>public</b> API only rather than TFM's NMS-reflection-based {@code RawNbtInspector}:
 * oversized/excessive display name, lore, book pagination, potion-effect/firework-
 * explosion/banner-pattern counts, and container-in-container nesting (shulker-in-shulker
 * or bundle-in-bundle - the classic recursive-open crash vector). Moderator+ are exempt -
 * staff are trusted to build special-purpose items without getting flagged.
 *
 * <p><b>"Decimator" exploit</b> (user-reported): a crafted item, often with bloated/
 * anomalous metadata, rapid-fired between main hand and offhand via a client-side script -
 * each swap forces the server to broadcast an equipment-sync packet containing the full
 * item to every nearby observer, so a fast enough swap rate overloads the packet-processing
 * thread regardless of whether the item's content alone would trip the checks above.
 * {@link #onSwapHands} closes this two ways: it runs the exact same {@link #isCursed}
 * check this guard already applies everywhere else (a gap before this - swapping hands
 * doesn't fire {@link InventoryClickEvent}/{@link InventoryDragEvent} at all), and
 * independently rate-limits the swap itself via {@link PerPlayerRateLimiter}, so even a
 * "clean" item can't be swapped fast enough to matter.</p>
 *
 * <p><b>User-reported real freeze, two gaps closed</b>: a chest containing an item with
 * expensive-to-decode NBT froze the main thread when CoreProtect's own interact-logging
 * forced a synchronous decode of it on right-click (this happens at the NMS block-entity
 * load layer, before any Bukkit event this plugin could hook even fires - the only real fix
 * is stopping the cursed item from ever being placed, not detecting it after the fact).
 * Two paths previously let a cursed item reach a chest without ever running {@link
 * #isCursed}: editing a book directly (fires {@link PlayerEditBookEvent}, not {@link
 * InventoryClickEvent}/{@link InventoryDragEvent} - matches a log showing repeated vanilla
 * "Book edited too quickly!" kicks, vanilla's own *rate* limit on this path, not a *content*
 * one) and a hopper/dropper auto-transferring an item between containers (fires {@link
 * InventoryMoveItemEvent}, which nothing here checked either) - both are now hooked with
 * the exact same {@link #isCursed} check every other path already uses. {@link #isCursed}
 * itself also gained a total-nested-item-*count* cap alongside its existing *depth* cap
 * (see {@code crashItemMaxNestedItemCount}) - many sibling containers at an allowed depth
 * could otherwise still add up to an expensive decode without ever tripping the depth
 * check.</p>
 *
 * <p>Reads {@code plugin.rigelConfig()} fresh on every event rather than caching a
 * reference - see {@code protect.antigrief.AntiNukeGuard}'s javadoc for why.</p>
 */
public final class ItemGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final PerPlayerRateLimiter<UUID> handSwaps = new PerPlayerRateLimiter<>(1_000);

    public ItemGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isExempt(player)) {
            return;
        }
        if (isCursed(event.getCurrentItem()) || isCursed(event.getCursor())) {
            event.setCancelled(true);
            warn(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isExempt(player)) {
            return;
        }
        if (isCursed(event.getOldCursor())) {
            event.setCancelled(true);
            warn(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilPrepare(@NotNull PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player) || isExempt(player)) {
            return;
        }
        if (isCursed(event.getResult())) {
            event.setResult(null);
        }
    }

    /**
     * User-reported gap: editing a book fires this event, not {@link InventoryClickEvent}/
     * {@link InventoryDragEvent} - the only other paths this guard's book-size check ran
     * from - so a player could build an oversized/malformed book entirely through the book
     * GUI without ever tripping it. See this class's own javadoc for the log pattern this
     * closes.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEditBook(@NotNull PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) {
            return;
        }
        if (bookExceedsLimits(event.getNewBookMeta(), plugin.rigelConfig())) {
            event.setCancelled(true);
            warn(player);
        }
    }

    /**
     * User-reported gap: a hopper/dropper auto-transferring an item between containers
     * never fires {@link InventoryClickEvent}/{@link InventoryDragEvent} either - a cursed
     * item could reach a chest this way without a player ever directly clicking it in.
     * No player/exemption check here - there's no player performing the transfer, and this
     * only ever fires for an item that's already cursed regardless of who built the hopper
     * chain.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemMove(@NotNull InventoryMoveItemEvent event) {
        if (isCursed(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * Sweeps a joining player's inventory for items that already exceed the current
     * limits - catches anything carried over from before this guard existed, or given
     * while offline through some other means. Can't cancel a join, so this just removes
     * the offending item(s) rather than blocking anything.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        boolean removedAny = false;
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isCursed(inventory.getItem(i))) {
                inventory.setItem(i, null);
                removedAny = true;
            }
        }
        if (removedAny) {
            plugin.getLogger()
                    .warning("Removed one or more items exceeding safety limits from " + player.getName()
                            + "'s inventory on join.");
        }
    }

    /** See this class's own javadoc for the "Decimator" exploit this specifically defends against. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) {
            return;
        }
        if (isCursed(event.getMainHandItem()) || isCursed(event.getOffHandItem())) {
            event.setCancelled(true);
            warn(player);
            return;
        }
        int maxPerSecond = plugin.rigelConfig().crashItemMaxHandSwapsPerSecond();
        if (maxPerSecond <= 0) {
            return;
        }
        int count = handSwaps.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > maxPerSecond) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        handSwaps.clear(event.getPlayer().getUniqueId());
    }

    private boolean isExempt(Player player) {
        return !plugin.rigelConfig().crashItemGuardEnabled()
                || permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator");
    }

    private static void warn(Player player) {
        player.sendMessage(Component.text(
                "That item was blocked - it doesn't meet server safety limits.", NamedTextColor.RED));
    }

    /** @return {@code true} if this item (or anything nested inside it) exceeds a configured safety limit. */
    public boolean isCursed(@Nullable ItemStack stack) {
        return isCursed(stack, 0, new int[1]);
    }

    /**
     * @param itemCount a single-element running total shared across this whole recursive
     *     walk (not per-branch) - every non-air item visited anywhere in the tree increments
     *     it once, so many sibling containers at an allowed depth (breadth, not depth) still
     *     trip {@code crashItemMaxNestedItemCount} even though none of them individually
     *     exceeds {@code crashItemMaxContainerDepth} - see this class's own javadoc.
     */
    private boolean isCursed(@Nullable ItemStack stack, int depth, int[] itemCount) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        RigelConfig config = plugin.rigelConfig();
        ItemMeta meta = stack.getItemMeta();

        if (meta.hasDisplayName() && plainTextLength(meta.displayName()) > config.crashItemMaxNameLength()) {
            return true;
        }
        if (meta.hasLore()) {
            List<Component> lore = meta.lore();
            if (lore != null) {
                if (lore.size() > config.crashItemMaxLoreLines()) {
                    return true;
                }
                for (Component line : lore) {
                    if (plainTextLength(line) > config.crashItemMaxLoreLineLength()) {
                        return true;
                    }
                }
            }
        }
        if (meta instanceof BookMeta bookMeta && bookExceedsLimits(bookMeta, config)) {
            return true;
        }
        if (meta instanceof PotionMeta potionMeta
                && potionMeta.getCustomEffects().size() > config.crashItemMaxPotionEffects()) {
            return true;
        }
        if (meta instanceof SuspiciousStewMeta stewMeta
                && stewMeta.getCustomEffects().size() > config.crashItemMaxPotionEffects()) {
            return true;
        }
        if (meta instanceof FireworkMeta fireworkMeta
                && fireworkMeta.getEffects().size() > config.crashItemMaxFireworkExplosions()) {
            return true;
        }
        if (meta instanceof BannerMeta bannerMeta
                && bannerMeta.numberOfPatterns() > config.crashItemMaxBannerPatterns()) {
            return true;
        }
        if (meta instanceof BundleMeta bundleMeta) {
            if (depth >= config.crashItemMaxContainerDepth()) {
                return !bundleMeta.getItems().isEmpty();
            }
            for (ItemStack contained : bundleMeta.getItems()) {
                if (exceedsNestedItemCount(contained, itemCount, config) || isCursed(contained, depth + 1, itemCount)) {
                    return true;
                }
            }
        }
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.hasBlockState()
                && blockStateMeta.getBlockState() instanceof Container container) {
            ItemStack[] contents = container.getInventory().getContents();
            if (contents == null) {
                return false;
            }
            if (depth >= config.crashItemMaxContainerDepth()) {
                for (ItemStack contained : contents) {
                    if (contained != null && !contained.getType().isAir()) {
                        return true;
                    }
                }
                return false;
            }
            for (ItemStack contained : contents) {
                if (exceedsNestedItemCount(contained, itemCount, config) || isCursed(contained, depth + 1, itemCount)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Extracted out of {@link #isCursed} so {@link #onEditBook} can reuse it directly. */
    private static boolean bookExceedsLimits(@NotNull BookMeta meta, @NotNull RigelConfig config) {
        List<Component> pages = meta.pages();
        if (pages.size() > config.crashItemMaxBookPages()) {
            return true;
        }
        for (Component page : pages) {
            if (plainTextLength(page) > config.crashItemMaxBookPageLength()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Increments the shared running total for one non-air nested item and reports whether
     * it's now over {@code crashItemMaxNestedItemCount} - the breadth counterpart to {@link
     * RigelConfig#crashItemMaxContainerDepth}'s depth cap, see this class's own javadoc.
     */
    private static boolean exceedsNestedItemCount(
            @Nullable ItemStack contained, int[] itemCount, @NotNull RigelConfig config) {
        if (contained == null || contained.getType().isAir()) {
            return false;
        }
        itemCount[0]++;
        return itemCount[0] > config.crashItemMaxNestedItemCount();
    }

    private static int plainTextLength(@Nullable Component component) {
        return component == null ? 0 : PlainTextComponentSerializer.plainText().serialize(component).length();
    }
}
