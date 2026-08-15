package org.rigelmc.protect.worldedit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.protect.worldedit.extent.WorldEditExtentService;
import org.rigelmc.punish.warn.StrikeService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.world.SpawnService;

/**
 * WorldEdit/FAWE integration, two tiers: the command-preprocess-level abuse prevention
 * layer ({@link WorldEditAbuseGuard}, always registered), and the real {@code
 * EditSessionEvent} extent-chain tier ({@link WorldEditExtentService}, attaches only once a
 * usable WorldEdit-compatible plugin is actually present) that gives {@code /protectarea}
 * (see {@code protect.area.ProtectAreaModule}) per-block enforcement against a live
 * WorldEdit/FAWE edit, plus the selection-volume/container/blocked-type caps the
 * command-preprocess layer alone can only approximate via string matching. Always
 * registers the command-preprocess listener - cheap no-op per command when neither
 * WorldEdit nor FAWE is installed, since {@link WorldEditAbuseGuard} only ever matches
 * commands actually owned by one of those plugins - rather than gating registration on
 * {@link WorldEditBridge#isAvailable()} at enable time, so this keeps working correctly if
 * WorldEdit/FAWE gets installed onto an already-running server; the extent-chain tier does
 * its own bounded polling for exactly that reason (see {@link WorldEditExtentService}).
 *
 * <p>Also contributes {@code /worldeditlimit} (user-requested: a command-configurable
 * radius/volume limit, not just a {@code config.yml} value) - Senior Admin, in-game or
 * console. Both tiers already exempt Moderator+ entirely regardless of this command's
 * current setting ({@link WorldEditAbuseGuard}/{@code extent.RigelEditExtentChain}'s own
 * existing floor - unchanged by this addition), so "bypass for staff" was already true
 * before this command existed; what this adds is a way to raise or lower the limit
 * <i>non-staff players</i> are held to, right now, without editing {@code config.yml} and
 * restarting. See {@link WorldEditLimitOverrideService}'s own javadoc for why this is
 * deliberately in-memory only, never written back to {@code config.yml}.</p>
 *
 * <p>Also applies much tighter WorldEdit caps near the server's configured spawn point
 * (user-requested, {@code protect.worldedit.spawn-protection.*}), split across both
 * tiers - a cheap radius pre-check at the command-preprocess layer ({@link
 * WorldEditAbuseGuard#checkRadius}) and a precise per-block-position cap at the
 * extent-chain layer ({@code extent.SpawnProtectionExtent}) - reading the live spawn
 * point from {@link SpawnService} (the same one {@code /setspawn}/{@code /spawn}
 * use).</p>
 */
public final class WorldEditProtectModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final StrikeService strikeService;
    private final ProtectAreaService protectAreaService;
    private final SpawnService spawnService;
    private final WorldEditLimitOverrideService limitOverrideService = new WorldEditLimitOverrideService();
    private RigelMCMod plugin;

    public WorldEditProtectModule(
            @NotNull PermissionGate permissionGate, @NotNull StrikeService strikeService,
            @NotNull ProtectAreaService protectAreaService, @NotNull SpawnService spawnService) {
        this.permissionGate = permissionGate;
        this.strikeService = strikeService;
        this.protectAreaService = protectAreaService;
        this.spawnService = spawnService;
    }

    @Override
    public String id() {
        return "worldeditprotect";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new WorldEditAbuseGuard(plugin, permissionGate, strikeService, limitOverrideService, spawnService),
                        plugin);
        new WorldEditExtentService(plugin, protectAreaService, permissionGate, limitOverrideService, spawnService).start();
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(
                worldEditLimitCommand(), "Senior Admin - view or change the WorldEdit radius/volume limit", List.of());
    }

    private LiteralCommandNode<CommandSourceStack> worldEditLimitCommand() {
        return Commands.literal("worldeditlimit")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(this::executeStatus)
                .then(Commands.literal("radius")
                        .executes(this::executeStatus)
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(this::executeSetRadius)))
                .then(Commands.literal("volume")
                        .executes(this::executeStatus)
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(this::executeSetVolume)))
                .then(Commands.literal("reset").executes(this::executeReset))
                .build();
    }

    private int executeStatus(CommandContext<CommandSourceStack> ctx) {
        RigelConfig config = plugin.rigelConfig();
        int radius = limitOverrideService.effectiveRadiusMax(config.worldEditRadiusMax());
        int volume = limitOverrideService.effectiveVolumeMax(config.worldEditMaxVolume());
        ctx.getSource().getSender().sendMessage(Component.text(
                "WorldEdit limits - radius: " + radius + (limitOverrideService.radiusMaxOverride() != null ? " (overridden)" : " (config default)")
                        + ", volume: " + volume + (limitOverrideService.volumeMaxOverride() != null ? " (overridden)" : " (config default)")
                        + ". Staff (Moderator+) bypass both entirely. Usage: /worldeditlimit radius|volume <value>|reset",
                NamedTextColor.GOLD));
        return 1;
    }

    private int executeSetRadius(CommandContext<CommandSourceStack> ctx) {
        int value = IntegerArgumentType.getInteger(ctx, "value");
        limitOverrideService.setRadiusMax(value);
        ctx.getSource().getSender().sendMessage(Component.text(
                "WorldEdit radius limit set to " + value + " for non-staff (session-only - resets to the"
                        + " config.yml value on restart).",
                NamedTextColor.GREEN));
        return 1;
    }

    private int executeSetVolume(CommandContext<CommandSourceStack> ctx) {
        int value = IntegerArgumentType.getInteger(ctx, "value");
        limitOverrideService.setVolumeMax(value);
        ctx.getSource().getSender().sendMessage(Component.text(
                "WorldEdit volume limit set to " + value + " for non-staff (session-only - resets to the"
                        + " config.yml value on restart).",
                NamedTextColor.GREEN));
        return 1;
    }

    private int executeReset(CommandContext<CommandSourceStack> ctx) {
        limitOverrideService.clearAll();
        ctx.getSource().getSender().sendMessage(Component.text(
                "WorldEdit radius/volume limits reset to their config.yml values.", NamedTextColor.GREEN));
        return 1;
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
