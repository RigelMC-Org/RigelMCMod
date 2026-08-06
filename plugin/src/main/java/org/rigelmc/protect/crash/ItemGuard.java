package org.rigelmc.protect.crash;

import java.util.List;
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
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
 * <p>Reads {@code plugin.rigelConfig()} fresh on every event rather than caching a
 * reference - see {@code protect.antigrief.AntiNukeGuard}'s javadoc for why.</p>
 */
public final class ItemGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;

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
        return isCursed(stack, 0);
    }

    private boolean isCursed(@Nullable ItemStack stack, int depth) {
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
        if (meta instanceof BookMeta bookMeta) {
            List<Component> pages = bookMeta.pages();
            if (pages.size() > config.crashItemMaxBookPages()) {
                return true;
            }
            for (Component page : pages) {
                if (plainTextLength(page) > config.crashItemMaxBookPageLength()) {
                    return true;
                }
            }
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
                if (isCursed(contained, depth + 1)) {
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
                if (isCursed(contained, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int plainTextLength(@Nullable Component component) {
        return component == null ? 0 : PlainTextComponentSerializer.plainText().serialize(component).length();
    }
}
