package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * User-requested: caps how many blocks of a single non-staff WorldEdit/FAWE edit may land
 * within {@code protect.worldedit.spawn-protection.radius} blocks of the server's
 * configured spawn point (see {@code world.SpawnService}) - protects the shared spawn area
 * specifically, on top of (not instead of) {@link VolumeLimitExtent}'s general per-session
 * cap. A huge edit selected far away that only barely clips the spawn radius is still fine
 * up to {@code max-blocks} <i>within range</i>; the same edit centered directly on spawn
 * hits the cap almost immediately. Same "throw {@link MaxChangedBlocksException} to halt
 * mid-operation" mechanism {@link VolumeLimitExtent} already uses, not a silent per-block
 * skip like {@link ProtectAreaExtent} - the point is stopping an oversized operation near
 * spawn outright, not quietly trimming it.
 *
 * <p>Distance is checked per written block against a fixed spawn coordinate captured once
 * at construction (not re-read live per block) - {@code world.SpawnService#spawnLocation}
 * is a cheap in-memory read either way, but a single stable snapshot for the whole
 * operation avoids the (extremely unlikely, but possible) case of {@code /setspawn} moving
 * spawn mid-edit and changing which blocks count partway through.</p>
 */
final class SpawnProtectionExtent extends RigelPathCompleteExtent {

    private final RigelMCMod plugin;
    private final UUID actorUuid;
    private final int spawnX;
    private final int spawnY;
    private final int spawnZ;
    private final long radiusSquared;
    private final int maxBlocks;
    private final AtomicInteger countNearSpawn = new AtomicInteger();
    private boolean warned;

    SpawnProtectionExtent(
            @NotNull Extent parent, @NotNull RigelMCMod plugin, @NotNull UUID actorUuid, @NotNull BlockVector3 spawn,
            int radius, int maxBlocks) {
        super(parent);
        this.plugin = plugin;
        this.actorUuid = actorUuid;
        this.spawnX = spawn.x();
        this.spawnY = spawn.y();
        this.spawnZ = spawn.z();
        this.radiusSquared = (long) radius * radius;
        this.maxBlocks = maxBlocks;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block) throws WorldEditException {
        long dx = pos.x() - spawnX;
        long dy = pos.y() - spawnY;
        long dz = pos.z() - spawnZ;
        boolean withinSpawnRadius = dx * dx + dy * dy + dz * dz <= radiusSquared;
        if (withinSpawnRadius && countNearSpawn.incrementAndGet() > maxBlocks) {
            warnOnce();
            throw new MaxChangedBlocksException(maxBlocks);
        }
        return super.setBlock(pos, block);
    }

    private void warnOnce() {
        if (warned) {
            return;
        }
        warned = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(actorUuid);
            if (player != null) {
                player.sendMessage(Component.text(
                        "WorldEdit spawn-protection limit reached (" + maxBlocks + " blocks within "
                                + "protect.worldedit.spawn-protection.radius of spawn) - operation halted.",
                        NamedTextColor.RED));
            }
        });
    }
}
