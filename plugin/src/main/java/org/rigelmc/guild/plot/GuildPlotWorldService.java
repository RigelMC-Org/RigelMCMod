package org.rigelmc.guild.plot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.guild.GuildDao;
import org.rigelmc.guild.GuildRecord;
import org.rigelmc.protect.area.AreaFlag;
import org.rigelmc.protect.area.AreaRecord;
import org.rigelmc.protect.area.AreaRegion;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.world.CleanroomGeneratorBridge;

/**
 * A guild's plot as a literal {@code rigel_protect_areas} row - see docs/architecture.md
 * "Guild system: roster, roles, and the plot world" and the plan's own §0.1 for the full
 * rationale: reusing {@link ProtectAreaService} directly gets block-edit AND WorldEdit/FAWE
 * protection (via {@code protect.worldedit.extent.ProtectAreaExtent}) for guild plots with
 * zero new event-handling code, on top of the {@link AreaFlag#MOB_SPAWN} addition this
 * sub-phase adds for the one griefing vector that infrastructure didn't already cover.
 *
 * <p><b>Deliberately Bukkit-free in every method {@code guild.GuildService} calls</b>
 * ({@link #assignPlotForGuild}, {@link #syncMemberAdded}, {@link #syncMemberRemoved},
 * {@link #syncOwner}, {@link #releasePlot}) - grid geometry and Y bounds are passed in as
 * plain parameters (see {@link PlotGridSettings}) rather than read from a live {@link World}
 * or {@link RigelConfig}, exactly matching {@code GuildService#create}'s own established
 * "stays config/Bukkit-free, caller passes primitives in" pattern. This is what keeps
 * {@code GuildServiceTest} able to exercise the full create/join/leave/disband lifecycle -
 * including plot assignment - against a real {@link ProtectAreaService} and a real temp-file
 * SQLite database, no MockBukkit, matching {@code ProtectAreaServiceTest}'s own established
 * "real service, Proxy-faked {@code Plugin}" precedent.</p>
 *
 * <p>The two methods that DO need a live Bukkit {@link World} - {@link #ensureWorldExists}
 * (world creation on enable) and {@link #plotTeleportLocation} ({@code /guild plot tp}) -
 * take a {@link RigelMCMod} parameter rather than holding one as a field, are only ever
 * called from {@code guild.GuildModule}/{@code guild.GuildCommand}, and are the one part of
 * this class's behavior the existing "cannot be verified in this sandbox" list already
 * covers (no live Bukkit world container here) - matching {@code world.FlatlandsService}'s
 * own "only the pure decision logic is unit-tested" precedent.</p>
 *
 * <p><b>Y bounds are a fixed band around {@code ground-y}</b>, not a live world's real
 * height limits - {@link #PLOT_Y_BELOW_GROUND}/{@link #PLOT_Y_ABOVE_GROUND} below/above
 * {@code guild.plotworld.ground-y}, generous enough for normal building (170 blocks total)
 * while staying comfortably inside every modern Overworld-type dimension's actual height
 * range regardless of the exact value an operator picks for {@code ground-y}. This avoids
 * needing a live {@code World} object during plot assignment at all (see this class's own
 * "Bukkit-free" javadoc above) at the cost of not literally covering a plot's entire
 * theoretical build column - an acceptable trade for a guild plot, which is meant to be a
 * modest cosmetic build, not a full-height mega-project.</p>
 *
 * <p><b>Slot occupancy</b> is its own tiny in-memory cache ({@link #occupiedSlots}, rebuilt
 * from {@link GuildDao#findAll()} on {@link #loadPersistedState()}), not a query against
 * {@code GuildService}'s own cache - deliberately, to avoid a circular constructor
 * dependency between the two services (this class doesn't need {@code GuildService} at all;
 * {@code GuildService} depends on this class, never the other way around).</p>
 *
 * <p><b>Known sharp edge</b>: a plot's protect-area is named {@code "guild-plot-<guildId>"}
 * in the same {@code rigel_protect_areas} table every admin-created {@code /protectarea}
 * region lives in. An admin manually creating a region with a colliding name would interfere
 * with a guild's plot - extremely unlikely in practice (nobody types that name by
 * coincidence) and not specially guarded against, same category of accepted edge case as
 * {@code AreaDao}'s case-insensitive name uniqueness already is for admin regions among
 * themselves.</p>
 *
 * <p><b>Creative-plotworld-style lockdown</b> (user-requested): a single boundary region
 * ({@link #BOUNDARY_AREA_NAME}) spans the entire plot world and denies {@link
 * #BOUNDARY_FLAGS} for everyone - no owner, no members, so only Moderator+ (via {@link
 * ProtectAreaService#isBypassing}'s existing rank floor) can act inside it at all. Every
 * individual guild plot is then created with {@code allowNest = true} so it nests inside
 * the boundary and, via the exact same overlap/priority resolution {@code /protectarea
 * create -nest} already uses ({@link AreaRegion#selectEffective}), wins within its own
 * footprint for its own members - reusing the nesting system rather than building a second
 * one. Net effect: a player can freely walk the whole plot world, but can only build/break/
 * interact/PVP/spawn mobs/pick up items inside a plot they're actually a member of - not in
 * the gaps between plots, not on an unclaimed grid slot, and not in anyone else's plot.</p>
 */
public final class GuildPlotWorldService {

    /** How far below {@code ground-y} a plot's protected column extends - see class javadoc. */
    public static final int PLOT_Y_BELOW_GROUND = 20;
    /** How far above {@code ground-y} a plot's protected column extends - see class javadoc. */
    public static final int PLOT_Y_ABOVE_GROUND = 150;

    /** The single whole-plot-world lockdown region's name - see class javadoc. */
    private static final String BOUNDARY_AREA_NAME = "guild-plotworld-boundary";
    /**
     * How many grid rows of headroom the boundary region's Z extent gets beyond {@code
     * grid-columns} worth of X extent - rows grow unboundedly as guilds are created (slot
     * indices never stop incrementing), so this has to be generous rather than exact. 2000
     * rows at even a very large 1000-block {@code plot-size} is two million blocks of
     * headroom - comfortably beyond any real Free-OP server's guild count, and still nowhere
     * near Minecraft's own ±29,999,984 world-border limit. {@link #ensureBoundaryProtectionExists}
     * recomputes and resizes the boundary against current config on every call (including
     * every plugin enable), so a config change that needs more headroom self-heals without
     * any manual step.
     */
    private static final int BOUNDARY_ROW_CAPACITY = 2000;
    /** Generic, comfortably-covers-everything Y range for the boundary - independent of {@link #PLOT_Y_BELOW_GROUND}/{@link #PLOT_Y_ABOVE_GROUND}'s narrower per-plot band, so building above/below any single plot's own protected column is also locked down. */
    private static final int BOUNDARY_MIN_Y = -64;
    private static final int BOUNDARY_MAX_Y = 319;
    private static final List<AreaFlag> BOUNDARY_FLAGS =
            List.of(AreaFlag.BUILD, AreaFlag.EXPLOSIONS, AreaFlag.MOB_SPAWN, AreaFlag.INTERACT, AreaFlag.PVP, AreaFlag.ITEM_PICKUP);

    private static final List<AreaFlag> GRIEF_PROTECTION_FLAGS =
            List.of(AreaFlag.BUILD, AreaFlag.EXPLOSIONS, AreaFlag.MOB_SPAWN, AreaFlag.INTERACT, AreaFlag.PVP);

    private final GuildDao guildDao;
    private final ProtectAreaService protectAreaService;
    private final CleanroomGeneratorBridge cleanroomGeneratorBridge;
    private final Set<Integer> occupiedSlots = ConcurrentHashMap.newKeySet();

    public GuildPlotWorldService(@NotNull GuildDao guildDao, @NotNull ProtectAreaService protectAreaService) {
        this(guildDao, protectAreaService, new CleanroomGeneratorBridge());
    }

    /** Test-only constructor - injects the generator bridge directly, matching {@code FlatlandsService}'s own shape. */
    GuildPlotWorldService(
            @NotNull GuildDao guildDao, @NotNull ProtectAreaService protectAreaService,
            @NotNull CleanroomGeneratorBridge cleanroomGeneratorBridge) {
        this.guildDao = guildDao;
        this.protectAreaService = protectAreaService;
        this.cleanroomGeneratorBridge = cleanroomGeneratorBridge;
    }

    /** Rebuilds the slot-occupancy cache from every guild's persisted {@code plot_slot_index}. Call once on enable. */
    public void loadPersistedState() throws SQLException {
        occupiedSlots.clear();
        for (GuildRecord record : guildDao.findAll()) {
            if (record.plotSlotIndex() != null) {
                occupiedSlots.add(record.plotSlotIndex());
            }
        }
    }

    /** The fixed grid/height parameters a plot is assigned with - read from config by the caller, see class javadoc. */
    public record PlotGridSettings(int plotSize, int plotGap, int gridColumns, int minY, int maxY) {

        /** @return settings derived from {@code guild.plotworld.*} config - {@code minY}/{@code maxY} from the ground-relative band, see class javadoc. */
        @NotNull
        public static PlotGridSettings fromConfig(@NotNull RigelConfig config) {
            int groundY = config.guildPlotGroundY();
            return new PlotGridSettings(
                    config.guildPlotSize(), config.guildPlotGap(), config.guildPlotGridColumns(),
                    groundY - PLOT_Y_BELOW_GROUND, groundY + PLOT_Y_ABOVE_GROUND);
        }
    }

    /** @param plotAreaId {@code null} if assignment failed (see {@link #assignPlotForGuild}'s javadoc). */
    public record PlotAssignmentOutcome(boolean assigned, @Nullable Integer plotAreaId, @Nullable Integer plotSlotIndex) {

        @NotNull
        static PlotAssignmentOutcome failed() {
            return new PlotAssignmentOutcome(false, null, null);
        }

        @NotNull
        static PlotAssignmentOutcome assigned(int plotAreaId, int plotSlotIndex) {
            return new PlotAssignmentOutcome(true, plotAreaId, plotSlotIndex);
        }
    }

    /**
     * Allocates the next free grid slot, creates its backing {@code rigel_protect_areas}
     * row (owned by {@code ownerUuid} directly - {@link ProtectAreaService#create}'s
     * {@code actor} parameter becomes the row's owner, see that method), and applies the
     * standard griefing-protection flag overrides ({@link #GRIEF_PROTECTION_FLAGS} deny;
     * {@link AreaFlag#ENTRY}/{@link AreaFlag#ITEM_PICKUP} stay at their allow defaults so
     * plots remain visitable and don't need any special-casing for a member's own items).
     *
     * <p>Created with {@code allowNest = true} - a plot always overlaps {@link
     * #BOUNDARY_AREA_NAME} (which spans the whole plot world, see class javadoc), and
     * {@link ProtectAreaService#create}'s own nesting logic auto-assigns it a priority one
     * higher than the boundary, so it correctly wins within its own footprint. Callers
     * should therefore call {@link #ensureBoundaryProtectionExists} once before ever
     * calling this, so the boundary already exists and every plot's priority is computed
     * against it deterministically - {@code guild.GuildModule} does this on every enable.</p>
     *
     * <p>Never throws grid geometry back at the caller as a failure - slot indices are
     * unbounded (rows just keep incrementing), so the only realistic failure mode is
     * {@link ProtectAreaService#create} itself rejecting the call (it shouldn't, since
     * fresh grid cells never overlap anything but the boundary by construction - a non-OK
     * result here means something is genuinely wrong, logged as a warning by the caller via
     * the returned {@code assigned() == false} outcome). Guild creation itself never
     * hard-fails on a plot-assignment failure - see {@code GuildService#create}'s javadoc.</p>
     */
    @NotNull
    public synchronized PlotAssignmentOutcome assignPlotForGuild(
            int guildId, @NotNull UUID ownerUuid, @NotNull String worldName, @NotNull PlotGridSettings settings, long now)
            throws SQLException {
        int slotIndex = nextFreeSlotIndex();
        PlotGridAllocator.PlotBounds bounds =
                PlotGridAllocator.boundsForSlot(slotIndex, settings.plotSize(), settings.plotGap(), settings.gridColumns());

        ProtectAreaService.CreateOutcome outcome = protectAreaService.create(
                plotAreaName(guildId), worldName, bounds.minX(), settings.minY(), bounds.minZ(),
                bounds.maxX(), settings.maxY(), bounds.maxZ(), true, ownerUuid, now);
        if (outcome.result() != ProtectAreaService.CreateResult.OK || outcome.region() == null) {
            return PlotAssignmentOutcome.failed();
        }

        AreaRegion region = outcome.region();
        // Re-fetches the region before each call rather than reusing the same stale
        // reference throughout the loop - ProtectAreaService#setFlag builds its new
        // override map from the AreaRegion instance it's given, so passing the same
        // (increasingly stale) instance to every call would have each iteration silently
        // clobber the previous one's override instead of accumulating them (confirmed the
        // hard way - see GuildServiceTest#createdPlotDeniesBuildExplosionsMobSpawnInteractAndPvp).
        for (AreaFlag flag : GRIEF_PROTECTION_FLAGS) {
            AreaRegion current = protectAreaService.find(region.name()).orElseThrow();
            protectAreaService.setFlag(current, flag, false);
        }
        occupiedSlots.add(slotIndex);
        return PlotAssignmentOutcome.assigned(region.id(), slotIndex);
    }

    private int nextFreeSlotIndex() {
        int slot = 0;
        while (occupiedSlots.contains(slot)) {
            slot++;
        }
        return slot;
    }

    /** Grants {@code member} plot bypass, if this guild has an assigned plot - a silent no-op otherwise. */
    public void syncMemberAdded(int guildId, @NotNull UUID member, @Nullable UUID actor, long now) throws SQLException {
        Optional<AreaRegion> region = protectAreaService.find(plotAreaName(guildId));
        if (region.isPresent()) {
            protectAreaService.addMember(region.get(), member, actor, now);
        }
    }

    /** Revokes {@code member}'s plot bypass, if this guild has an assigned plot - a silent no-op otherwise. */
    public void syncMemberRemoved(int guildId, @NotNull UUID member) throws SQLException {
        Optional<AreaRegion> region = protectAreaService.find(plotAreaName(guildId));
        if (region.isPresent()) {
            protectAreaService.removeMember(region.get(), member);
        }
    }

    /** Moves plot ownership to {@code newOwner}, if this guild has an assigned plot - a silent no-op otherwise. */
    public void syncOwner(int guildId, @NotNull UUID newOwner, @Nullable UUID actor, long now) throws SQLException {
        Optional<AreaRegion> region = protectAreaService.find(plotAreaName(guildId));
        if (region.isPresent()) {
            protectAreaService.setOwner(region.get(), newOwner, actor, now);
        }
    }

    /** Deletes this guild's plot region entirely and frees its grid slot for reuse - called from {@code GuildService#disband}. */
    public synchronized void releasePlot(int guildId, @Nullable Integer plotSlotIndex) throws SQLException {
        Optional<AreaRegion> region = protectAreaService.find(plotAreaName(guildId));
        if (region.isPresent()) {
            protectAreaService.delete(region.get());
        }
        if (plotSlotIndex != null) {
            occupiedSlots.remove(plotSlotIndex);
        }
    }

    @NotNull
    private static String plotAreaName(int guildId) {
        return "guild-plot-" + guildId;
    }

    /**
     * Creates (or, if the currently configured grid parameters have changed since it was
     * last created, resizes) the whole-plot-world lockdown region - see class javadoc.
     * Idempotent and safe to call on every enable; {@code guild.GuildModule} does exactly
     * that, always before any guild plot can possibly be assigned (see {@link
     * #assignPlotForGuild}'s own javadoc for why the ordering matters).
     */
    public synchronized void ensureBoundaryProtectionExists(
            @NotNull String worldName, @NotNull PlotGridSettings settings, long now) throws SQLException {
        BoundaryBounds desired = computeBoundaryBounds(settings);
        Optional<AreaRegion> existing = protectAreaService.find(BOUNDARY_AREA_NAME);
        if (existing.isPresent()) {
            AreaRegion region = existing.get();
            AreaRecord record = region.record();
            boolean matches = record.world().equals(worldName)
                    && record.minX() == desired.minX() && record.minY() == desired.minY() && record.minZ() == desired.minZ()
                    && record.maxX() == desired.maxX() && record.maxY() == desired.maxY() && record.maxZ() == desired.maxZ();
            if (!matches) {
                protectAreaService.update(
                        region, worldName, desired.minX(), desired.minY(), desired.minZ(),
                        desired.maxX(), desired.maxY(), desired.maxZ(), true, null, now);
            }
            return;
        }

        ProtectAreaService.CreateOutcome outcome = protectAreaService.create(
                BOUNDARY_AREA_NAME, worldName, desired.minX(), desired.minY(), desired.minZ(),
                desired.maxX(), desired.maxY(), desired.maxZ(), true, null, now);
        if (outcome.result() != ProtectAreaService.CreateResult.OK || outcome.region() == null) {
            return; // extremely unlikely (a fresh, otherwise-empty plot world) - nothing more to do if it somehow fails
        }
        AreaRegion region = outcome.region();
        for (AreaFlag flag : BOUNDARY_FLAGS) {
            AreaRegion current = protectAreaService.find(region.name()).orElseThrow();
            protectAreaService.setFlag(current, flag, false);
        }
    }

    /** Pure geometry - no I/O. See {@link #BOUNDARY_ROW_CAPACITY}'s javadoc for why the Z extent is generous rather than exact. */
    @NotNull
    private static BoundaryBounds computeBoundaryBounds(@NotNull PlotGridSettings settings) {
        int cell = settings.plotSize() + settings.plotGap();
        int width = settings.gridColumns() * cell;
        int depth = BOUNDARY_ROW_CAPACITY * cell;
        return new BoundaryBounds(-8, BOUNDARY_MIN_Y, -8, width + 8, BOUNDARY_MAX_Y, depth + 8);
    }

    private record BoundaryBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /**
     * The DB/protection half of a full plotworld reset - deletes every guild's plot region
     * and the boundary region itself, and clears the slot-occupancy cache. Does not touch
     * the Bukkit world itself, any guild's {@code plot_area_id}/{@code plot_slot_index}
     * columns, or reassign new plots - see {@code GuildService#resetPlotWorld}, which
     * orchestrates the full reset (including calling {@link #ensureBoundaryProtectionExists}
     * again and reassigning every guild a fresh plot) around this and the separate
     * Bukkit-side world wipe ({@link #evacuateAndUnloadWorld}/{@link #deleteWorldFolder}).
     */
    public synchronized void resetAll() throws SQLException {
        for (GuildRecord record : guildDao.findAll()) {
            Optional<AreaRegion> region = protectAreaService.find(plotAreaName(record.id()));
            if (region.isPresent()) {
                protectAreaService.delete(region.get());
            }
        }
        Optional<AreaRegion> boundary = protectAreaService.find(BOUNDARY_AREA_NAME);
        if (boundary.isPresent()) {
            protectAreaService.delete(boundary.get());
        }
        occupiedSlots.clear();
    }

    // ---- Bukkit-touching - GuildModule/GuildCommand only, never GuildService --------------

    /**
     * Creates the guild plot world if it doesn't already exist (main-thread only, mirrors
     * {@code FlatlandsService#ensureWorldExists}), (re-)attaches {@link
     * GuildPlotWorldPopulator} so every chunk generated from this point forward gets a
     * visible road/border network (see that class's own javadoc for why this runs on every
     * enable, not just world creation, and why it never touches already-generated chunks),
     * then unconditionally calls {@link #ensureBoundaryProtectionExists} - every time, not
     * just when the world itself needed creating, since the boundary is its own separate DB
     * row that can independently be missing (e.g. after {@link #resetAll} clears it) even
     * when the Bukkit world already exists. Failures there are logged, not fatal to plugin
     * enable.
     */
    public void ensureWorldExists(@NotNull RigelMCMod plugin) {
        RigelConfig config = plugin.rigelConfig();
        String name = config.guildPlotWorldName();
        World world = Bukkit.getWorld(name);
        if (world == null) {
            WorldCreator creator = new WorldCreator(name).generateStructures(false);
            cleanroomGeneratorBridge.resolveGenerator(name, config.guildPlotGenerationParams())
                    .ifPresentOrElse(creator::generator, () -> creator.type(WorldType.FLAT));

            world = creator.createWorld();
            if (world != null) {
                world.setSpawnFlags(false, false);
                world.setSpawnLocation(0, config.guildPlotGroundY() + 1, 0);
            }
            plugin.getLogger().info("Created guild plot world '" + name + "'.");
        }
        if (world != null) {
            world.getPopulators().add(new GuildPlotWorldPopulator(
                    config.guildPlotSize(), config.guildPlotGap(), config.guildPlotGroundY()));
        }

        try {
            ensureBoundaryProtectionExists(name, PlotGridSettings.fromConfig(config), System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to ensure the guild plot world's boundary protection"
                    + " exists - guild plots may not be fully protected until this is fixed and the plugin"
                    + " restarts.", e);
        }
    }

    /** @return the center of the given plot slot, one block above the flat ground - or empty if the plot world isn't loaded. */
    @NotNull
    public Optional<Location> plotTeleportLocation(@NotNull RigelMCMod plugin, int plotSlotIndex) {
        RigelConfig config = plugin.rigelConfig();
        World world = Bukkit.getWorld(config.guildPlotWorldName());
        if (world == null) {
            return Optional.empty();
        }
        PlotGridAllocator.PlotBounds bounds = PlotGridAllocator.boundsForSlot(
                plotSlotIndex, config.guildPlotSize(), config.guildPlotGap(), config.guildPlotGridColumns());
        double centerX = (bounds.minX() + bounds.maxX()) / 2.0 + 0.5;
        double centerZ = (bounds.minZ() + bounds.maxZ()) / 2.0 + 0.5;
        return Optional.of(new Location(world, centerX, config.guildPlotGroundY() + 1, centerZ));
    }

    /** The outcome of {@link #evacuateAndUnloadWorld} - distinguishes "nothing to delete" from "still in use, do not delete anything." */
    public enum WorldUnloadResult { NOT_LOADED, UNLOADED, UNLOAD_FAILED }

    /** @param folder non-null only when {@code result() == UNLOADED} - the folder to hand to {@link #deleteWorldFolder} next. */
    public record WorldUnloadOutcome(@NotNull WorldUnloadResult result, @Nullable File folder) {
    }

    /**
     * First step of a full world wipe (used by {@code /wipeguildplots confirm}) - evacuates
     * anyone currently in the plot world to the main world's spawn, then unloads it. Mirrors
     * {@code FlatlandsService#performWipe}'s in-place wipe exactly, including capturing the
     * folder path <i>before</i> unloading (the only reliable source for it - a world can't be
     * asked for its folder once unloaded). Main-thread only.
     *
     * @return {@link WorldUnloadResult#NOT_LOADED} if the world wasn't loaded at all (nothing
     *     to delete, safe to proceed straight to recreating it); {@link
     *     WorldUnloadResult#UNLOAD_FAILED} if Bukkit refused to unload it (most likely a
     *     player teleport hasn't fully landed yet) - callers must abort rather than deleting
     *     files out from under a world Bukkit still considers loaded; {@link
     *     WorldUnloadResult#UNLOADED} with the captured folder on success.
     */
    @NotNull
    public WorldUnloadOutcome evacuateAndUnloadWorld(@NotNull RigelMCMod plugin) {
        String name = plugin.rigelConfig().guildPlotWorldName();
        World world = Bukkit.getWorld(name);
        if (world == null) {
            return new WorldUnloadOutcome(WorldUnloadResult.NOT_LOADED, null);
        }
        File folder = world.getWorldFolder();
        World safeWorld = Bukkit.getWorlds().get(0);
        for (Player player : world.getPlayers()) {
            player.teleport(safeWorld.getSpawnLocation());
            player.sendMessage(Component.text(
                    "The guild plot world is being reset - you've been moved to spawn.", NamedTextColor.YELLOW));
        }
        if (!Bukkit.unloadWorld(world, false)) {
            return new WorldUnloadOutcome(WorldUnloadResult.UNLOAD_FAILED, null);
        }
        return new WorldUnloadOutcome(WorldUnloadResult.UNLOADED, folder);
    }

    /** Deletes {@code folder} and everything under it. Blocking file I/O - call off the main thread (e.g. via {@code dbExecutor}, matching {@code FlatlandsService}'s own reuse of it for this same kind of work). */
    public void deleteWorldFolder(@NotNull File folder, @NotNull Logger logger) {
        if (!folder.exists()) {
            logger.warning("[wipeguildplots] Expected guild plot world folder does not exist at "
                    + folder.getAbsolutePath() + " - nothing to delete.");
            return;
        }
        int[] counts = {0, 0}; // {deleted, failed}
        try (java.util.stream.Stream<Path> paths = Files.walk(folder.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                    counts[0]++;
                } catch (IOException e) {
                    counts[1]++;
                    logger.log(Level.WARNING, "Failed to delete " + path + " during guild plot world reset", e);
                }
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to walk guild plot world folder for deletion", e);
            return;
        }
        logger.info("[wipeguildplots] Deleted " + counts[0] + " file(s)/folder(s) under " + folder.getAbsolutePath()
                + (counts[1] > 0 ? (", " + counts[1] + " failed - see above") : "") + ".");
    }
}
