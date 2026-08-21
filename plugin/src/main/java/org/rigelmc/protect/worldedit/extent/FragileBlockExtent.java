package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.worldedit.FragileBlockPolicy;

/**
 * Two-tier protection against "fragile" blocks - ones that cannot support themselves and so
 * pop off into dropped items the moment their support changes. User-requested: this is the
 * same lag-bomb class as the gravity blocks already covered by {@code blocked-block-types}
 * (mass {@code FallingBlock} spawn), with {@code Item} entities instead. See {@link
 * FragileBlockPolicy} for the classification and why it needs no hand-maintained list.
 *
 * <p>Non-staff only, like every other cap in this package - it is wrapped inside {@link
 * RigelEditExtentChain}'s existing Moderator+ gate. Staff need WorldEdit to build spawn and
 * repair griefing, and without the exemption they could not paste any schematic containing
 * a torch.</p>
 *
 * <p>The two tiers, in the order they are tested (the banned set is a strict subset of the
 * fragile set, so it has to come first):</p>
 * <ul>
 *   <li><b>Banned</b> ({@code ban-high-risk}) - redstone wire/repeaters/comparators, all
 *       rails, all pressure plates, tripwire. Halts on the very first one, borrowing {@code
 *       BlockedTypeExtent}'s {@code MaxChangedBlocksException(0)} idiom.</li>
 *   <li><b>Capped</b> ({@code max-per-operation}) - everything else fragile. Counted only
 *       when in-category, and halts past the cap, borrowing {@link ContainerLimitExtent}'s
 *       idiom. Capping rather than banning is what keeps a normal schematic {@code //paste}
 *       (which almost always contains a few torches or flowers) working.</li>
 * </ul>
 */
final class FragileBlockExtent extends RigelPathCompleteExtent {

    private final RigelMCMod plugin;
    private final UUID actorUuid;
    private final boolean banHighRisk;
    private final int cap;
    private final AtomicInteger count = new AtomicInteger();
    private boolean warned;

    FragileBlockExtent(
            @NotNull Extent parent, @NotNull RigelMCMod plugin, @NotNull UUID actorUuid,
            boolean banHighRisk, int cap) {
        super(parent);
        this.plugin = plugin;
        this.actorUuid = actorUuid;
        this.banHighRisk = banHighRisk;
        this.cap = cap;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block) throws WorldEditException {
        if (banHighRisk && FragileBlockPolicy.isBannedHighRisk(block)) {
            warnOnce("That WorldEdit operation places redstone, rails, or pressure plates - not permitted.");
            throw new MaxChangedBlocksException(0);
        }
        if (FragileBlockPolicy.isFragile(block) && count.incrementAndGet() > cap) {
            warnOnce("WorldEdit fragile-block limit reached (" + cap
                    + " torches/carpets/plants/etc.) - operation halted.");
            throw new MaxChangedBlocksException(cap);
        }
        return super.setBlock(pos, block);
    }

    private void warnOnce(String message) {
        if (warned) {
            return;
        }
        warned = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(actorUuid);
            if (player != null) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        message, net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        });
    }
}
