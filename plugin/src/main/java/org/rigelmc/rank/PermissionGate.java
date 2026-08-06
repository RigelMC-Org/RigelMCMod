package org.rigelmc.rank;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The permission fallback mechanism described in docs/architecture.md: every player gets
 * a {@link PermissionAttachment} reflecting their RigelMCMod rank, granting a synthetic
 * cumulative node per rank tier at or below their own (e.g. a Senior Admin holds both
 * {@code rigelmcmod.rank.senior_admin} and {@code rigelmcmod.rank.admin} and
 * {@code rigelmcmod.rank.moderator}) - so the plugin is fully functional with zero
 * permissions plugin installed. If LuckPerms/Vault are present they're used for prefix
 * rendering only (see {@code rank.bridge}); this attachment-based grant is always active
 * regardless, so RigelMCMod's own DB stays the single source of truth for who holds what.
 *
 * <p>Also maintains an in-memory {@code UUID -> rank id} cache for online players, so
 * command permission checks on the main thread never block on DB I/O - only join/rank
 * -change repopulates it via the (async) {@link RankService}.</p>
 *
 * <p>Piggybacks the same attachment to negate a couple of specific third-party
 * {@code default: op}-permission plugins' nodes for non-staff (currently just CoreProtect
 * - see {@link #COREPROTECT_STAFF_ONLY_NODES}), since {@code default: op} would otherwise
 * silently hand real admin power to every player on this Free-OP server. Not a general
 * mechanism - just the one concrete gap that's actually been hit so far.</p>
 */
public final class PermissionGate {

    /**
     * CoreProtect (if installed) ships every one of these {@code default: op} in its own
     * {@code plugin.yml} - harmless on a normal server, but on this Free-OP one (see
     * {@code core.AutoOpModule}, {@code /op} is open to everyone) that means literally
     * every player would otherwise inherit full rollback/restore/purge access purely from
     * holding vanilla operator status, regardless of RigelMCMod rank. Explicitly negated
     * for below-{@code moderator} players below, on the same attachment/at the same
     * rank-transition points as the rank-tier nodes - user-reported, see the "only allow
     * coreprotect commands for staff+" request. {@code protect.command-access} also blocks
     * the same dangerous subcommands as defense-in-depth (see config.yml), but this is the
     * actual fix: a command-layer block can be routed around by anything that calls
     * CoreProtect's API directly, a permission negation can't.
     */
    private static final String[] COREPROTECT_STAFF_ONLY_NODES = {
        "coreprotect.admin", "coreprotect.rollback", "coreprotect.restore",
        "coreprotect.purge", "coreprotect.reload", "coreprotect.teleport"
    };

    /**
     * Left granted to everyone (staff and non-staff alike) even though CoreProtect also
     * ships these as {@code default: op} - the inspector/lookup tool lets a non-staff
     * player see who's messing with their own builds without granting them any actual
     * rollback/restore/purge power. Matches the explicit request: "allow co i for ops so
     * they can see who's messing with them."
     */
    private static final String[] COREPROTECT_EVERYONE_NODES = {"coreprotect.inspect", "coreprotect.lookup"};

    /**
     * TFM's own {@code WorldEditHook.BYPASS_NODES} list, studied directly - negated for
     * non-staff for the same reason as the CoreProtect nodes above: a misconfigured
     * permissions setup granting these (whether via a plugin's own permissive default or
     * an operator mistake) can't be allowed to silently punch a hole through {@code
     * protect.worldedit.*}'s abuse-prevention limits. See {@code
     * protect.worldedit.WorldEditAbuseGuard} for the command-layer side of this.
     */
    private static final String[] WORLDEDIT_STAFF_ONLY_NODES = {
        "fawe.bypass", "fawe.bypass.regions", "fawe.limit.unlimited", "fawe.admin", "worldedit.limit.unrestricted"
    };

    private final Plugin plugin;
    private final RankService rankService;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final Map<UUID, String> onlineRankCache = new ConcurrentHashMap<>();

    public PermissionGate(@NotNull Plugin plugin, @NotNull RankService rankService) {
        this.plugin = plugin;
        this.rankService = rankService;
    }

    /** Registers every rank-tier node with Bukkit's {@code PluginManager} so it's discoverable. */
    public void registerKnownPermissions() {
        for (Rank rank : rankService.allRanks()) {
            String node = rankNode(rank.id());
            if (plugin.getServer().getPluginManager().getPermission(node) == null) {
                plugin.getServer()
                        .getPluginManager()
                        .addPermission(new org.bukkit.permissions.Permission(
                                node,
                                "Held by " + rank.displayName() + " and above",
                                org.bukkit.permissions.PermissionDefault.FALSE));
            }
        }
    }

    /**
     * Applies (or refreshes) the given player's attachment for the given rank id.
     * Must be called on the main thread; rank lookup should already have happened
     * off-thread before calling this.
     */
    public void applyRank(@NotNull Player player, @NotNull String rankId) {
        Rank targetRank = rankService.rank(rankId).orElseGet(rankService::defaultRank);
        PermissionAttachment attachment =
                attachments.computeIfAbsent(player.getUniqueId(), id -> player.addAttachment(plugin));

        // Clear previously granted rank-tier nodes, then grant every tier at or below
        // this rank's weight - a simple, cumulative ladder rather than per-node ACLs.
        for (Rank rank : rankService.allRanks()) {
            attachment.unsetPermission(rankNode(rank.id()));
        }
        for (Rank rank : rankService.allRanks()) {
            if (rank.weight() <= targetRank.weight()) {
                attachment.setPermission(rankNode(rank.id()), true);
            }
        }

        boolean isStaff = targetRank.weight()
                >= rankService.rank("moderator").map(Rank::weight).orElse(Integer.MAX_VALUE);
        for (String node : COREPROTECT_EVERYONE_NODES) {
            attachment.setPermission(node, true);
        }
        for (String node : COREPROTECT_STAFF_ONLY_NODES) {
            // Explicit true/false either way (not unset) - CoreProtect's own default: op
            // must never be allowed to show through for a non-staff player who happens to
            // hold vanilla op, see this field's javadoc.
            attachment.setPermission(node, isStaff);
        }
        for (String node : WORLDEDIT_STAFF_ONLY_NODES) {
            attachment.setPermission(node, isStaff);
        }

        onlineRankCache.put(player.getUniqueId(), rankId);
        player.recalculatePermissions();
    }

    public void clear(@NotNull Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            player.removeAttachment(attachment);
        }
        onlineRankCache.remove(player.getUniqueId());
    }

    /**
     * Fast, non-blocking check for use in command permission gating: compares the
     * cached online rank against {@code requiredRankId}'s weight. Returns {@code false}
     * for players not currently tracked (e.g. console callers should be checked
     * separately - console always passes rank checks upstream of this method).
     */
    public boolean hasAtLeastCached(@NotNull UUID uuid, @NotNull String requiredRankId) {
        String currentRankId = onlineRankCache.get(uuid);
        if (currentRankId == null) {
            return false;
        }
        int currentWeight = rankService.rank(currentRankId).map(Rank::weight).orElse(0);
        int requiredWeight =
                rankService.rank(requiredRankId).map(Rank::weight).orElse(Integer.MAX_VALUE);
        return currentWeight >= requiredWeight;
    }

    @NotNull
    public static String rankNode(@NotNull String rankId) {
        return "rigelmcmod.rank." + rankId;
    }

    @NotNull
    public List<Rank> laddersDescending() {
        return rankService.allRanks().stream()
                .sorted((a, b) -> Integer.compare(b.weight(), a.weight()))
                .toList();
    }
}
