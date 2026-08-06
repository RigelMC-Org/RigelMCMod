package org.rigelmc.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Typed access over {@code config.yml}. Deliberately a thin wrapper around Bukkit's own
 * {@link FileConfiguration} (pure-Java YAML parsing, no live server required) rather than
 * a custom config format, so it stays trivially unit-testable - see
 * {@code RigelConfigTest} for a MockBukkit-free example.
 */
public final class RigelConfig {

    private final FileConfiguration source;

    public RigelConfig(@NotNull FileConfiguration source) {
        this.source = source;
    }

    /**
     * @return {@code server.ip-domain} - the address players actually connect to (a domain,
     *     not necessarily this backend's own address - the documented target topology puts
     *     a Velocity proxy in front). Purely informational: substituted into the
     *     {@code {ip}} placeholder in the tab list header/footer and sidebar scoreboard,
     *     and shown on the web panel as "Server IP: {@code <value>}" - nothing here ever
     *     reads it to make a networking decision.
     */
    @NotNull
    public String serverIpDomain() {
        return source.getString("server.ip-domain", "play.rigelmc.org");
    }

    /**
     * @param moduleId a module's {@link PluginModule#id()}
     * @return whether {@code modules.<moduleId>.enabled} is set, defaulting to {@code true}
     *     for every module except {@code webpanel}, which is opt-in
     */
    public boolean isModuleEnabled(@NotNull String moduleId) {
        boolean defaultValue = !"webpanel".equals(moduleId);
        return source.getBoolean("modules." + moduleId + ".enabled", defaultValue);
    }

    /**
     * @return {@code identity.online-mode} - {@code true} means treat every non-Bedrock
     *     player as a verified premium identity regardless of Bukkit's own
     *     {@code getOnlineMode()}. See the config.yml comment on this key for why that
     *     matters on a Velocity-proxied setup. Defaults to {@code false} - the safer
     *     default is to keep deferring to Bukkit's own flag rather than assume premium.
     */
    public boolean identityOnlineMode() {
        return source.getBoolean("identity.online-mode", false);
    }

    /**
     * @return {@code chat.color-codes-enabled} - whether {@code &}-color codes typed in
     *     a public chat message are translated into actual colors/formatting, the same
     *     way {@code /nick}/{@code /tag} already always do. Defaults to {@code true} and
     *     open to every player, not rank-gated - matches {@code /nick}'s own precedent
     *     (no rank requirement there either) rather than Essentials' typical
     *     permission-gated {@code essentials.chat.color}. See {@code
     *     chat.ChatFormatListener} for where this is applied.
     */
    public boolean chatColorCodesEnabled() {
        return source.getBoolean("chat.color-codes-enabled", true);
    }

    /** @return {@code protect.protectarea.visualize.enabled} - whether {@code /protectarea info <name> -show} works at all. */
    public boolean protectAreaVisualizeEnabled() {
        return source.getBoolean("protect.protectarea.visualize.enabled", true);
    }

    /** @return {@code protect.protectarea.list-page-size} - regions shown per {@code /protectarea list} page. */
    public int protectAreaListPageSize() {
        return source.getInt("protect.protectarea.list-page-size", 8);
    }

    @NotNull
    public String storageType() {
        return source.getString("storage.type", "sqlite");
    }

    public boolean isMysqlStorage() {
        String type = storageType();
        return "mysql".equalsIgnoreCase(type) || "mariadb".equalsIgnoreCase(type);
    }

    @NotNull
    public String sqliteFileName() {
        return source.getString("storage.sqlite.file", "data.db");
    }

    @NotNull
    public String mysqlHost() {
        return source.getString("storage.mysql.host", "localhost");
    }

    public int mysqlPort() {
        return source.getInt("storage.mysql.port", 3306);
    }

    @NotNull
    public String mysqlDatabase() {
        return source.getString("storage.mysql.database", "rigelmcmod");
    }

    @NotNull
    public String mysqlUsername() {
        return source.getString("storage.mysql.username", "rigelmcmod");
    }

    @NotNull
    public String mysqlPassword() {
        return source.getString("storage.mysql.password", "");
    }

    public boolean mysqlUseSsl() {
        return source.getBoolean("storage.mysql.use-ssl", false);
    }

    /** Fixed duration for the plain {@code /ban} quick-ban command (default: 24 hours). */
    @NotNull
    public Duration banDefaultDuration() {
        return Duration.ofHours(source.getLong("punish.ban.default-duration-hours", 24));
    }

    /** Lookback window for the automatic CoreProtect rollback triggered by {@code /ban}. */
    @NotNull
    public Duration banAutoRollbackLookback() {
        return Duration.ofHours(source.getLong("punish.ban.auto-rollback-lookback-hours", 1));
    }

    /**
     * Auto-unfreeze safety net (minutes) for a {@code /freeze}'d player - matches TFM's own
     * {@code FreezeData} auto-unfreeze timeout (5 minutes) so a forgotten frozen player
     * doesn't stay stuck forever. 0 disables the timeout (frozen until manually released).
     */
    public long freezeAutoUnfreezeMinutes() {
        return source.getLong("punish.freeze.auto-unfreeze-minutes", 5);
    }

    /**
     * Auto-unfreeze safety net (minutes) for {@code /tickfreeze} - same rationale as
     * {@link #freezeAutoUnfreezeMinutes}, but for the whole server's tick loop rather
     * than a single player: a global tick freeze left on with no one around to notice
     * makes the entire server appear dead (nothing grows, moves, or updates) until
     * someone thinks to run {@code /tickfreeze off}. 0 (or negative) disables the
     * timeout.
     */
    public long tickFreezeAutoUnfreezeMinutes() {
        return source.getLong("protect.tickfreeze.auto-unfreeze-minutes", 5);
    }

    /**
     * Upward velocity re-applied while a player has {@code /orbit} toggled on - matches
     * TFM's own hardcoded {@code Command_orbit} strength (10.0) as the default.
     */
    public double orbitStrength() {
        return source.getDouble("fun.orbit.strength", 10.0);
    }

    /** @return {@code fun.jumppads.mode} - one of {@code off}/{@code normal}/{@code normal_and_sideways}. TFM ref: {@code Jumppads.JumpPadMode}. */
    @NotNull
    public String jumppadMode() {
        return source.getString("fun.jumppads.mode", "off");
    }

    /** @return {@code fun.jumppads.strength} - matches TFM's own default (0.4). */
    public double jumppadStrength() {
        return source.getDouble("fun.jumppads.strength", 0.4);
    }

    public boolean landmineEnabled() {
        return source.getBoolean("fun.landmine.enabled", true);
    }

    /** @return {@code fun.landmine.max-radius} - matches TFM's own clamp ceiling (6.0). */
    public double landmineMaxRadius() {
        return source.getDouble("fun.landmine.max-radius", 6.0);
    }

    public boolean mp44Enabled() {
        return source.getBoolean("fun.mp44.enabled", true);
    }

    public boolean trailEnabled() {
        return source.getBoolean("fun.trail.enabled", true);
    }

    /**
     * @return {@code fun.spawnmob.max-amount} - the most mobs a single {@code
     *     /spawnmob} call may spawn at once, an anti-lag/anti-mob-spam cap since
     *     unlike vanilla {@code /summon} this command has no raw-NBT limit to worry
     *     about (see {@code fun.SpawnMobModule}) but could still be abused to dump
     *     hundreds of mobs in one spot.
     */
    public int spawnMobMaxAmount() {
        return source.getInt("fun.spawnmob.max-amount", 16);
    }

    /** Lookback window for {@code /tban}'s opt-in {@code -r}/{@code --rollback} flag. */
    @NotNull
    public Duration tbanRollbackLookback() {
        return Duration.ofHours(source.getLong("punish.tban.default-rollback-lookback-hours", 1));
    }

    /**
     * @return {@code punish.strikes.decay-hours} - how long since a player's last {@code
     *     /warn} before their strike count fully resets to 0 (not a gradual step-down).
     *     {@code <= 0} means strikes never decay. Default 24, matching TFM's own {@code
     *     AutoEject}/{@code StrikeList} default time window.
     */
    public long strikeDecayHours() {
        return source.getLong("punish.strikes.decay-hours", 24);
    }

    /**
     * @return {@code punish.strikes.thresholds} - strike count -> ban duration in hours
     *     (0 = kick only, negative = permanent) a {@link org.rigelmc.punish.warn.StrikeService}-tracked
     *     strike count escalates to. A strike count with no configured entry (e.g. the
     *     first strike, by default) results in a warning only. Looked up via {@code
     *     TreeMap#floorEntry} so one high-water-mark entry (e.g. {@code "4": -1}) also
     *     covers every strike count above it, matching TFM's own "3 or more" collapsing.
     */
    @NotNull
    public java.util.TreeMap<Integer, Long> strikeThresholds() {
        java.util.TreeMap<Integer, Long> result = new java.util.TreeMap<>();
        ConfigurationSection section = source.getConfigurationSection("punish.strikes.thresholds");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    result.put(Integer.parseInt(key), section.getLong(key));
                } catch (NumberFormatException e) {
                    // Skip a malformed key rather than fail the whole config load - an
                    // operator typo here shouldn't take down strike escalation entirely.
                }
            }
        }
        return result;
    }

    /**
     * @return {@code protect.command-access} as raw {@code rank:action:/command
     *     [args]:message} rule strings, for
     *     {@link org.rigelmc.protect.CommandAccessRegistry} to parse - see that class's
     *     javadoc for the full syntax (studied directly from TFM's own config format)
     */
    @NotNull
    public java.util.List<String> protectCommandAccess() {
        return source.getStringList("protect.command-access");
    }

    // ---- protect.anti-grief.* - see config.yml's own comments for the full rationale ----

    public boolean antiNukeEnabled() {
        return source.getBoolean("protect.anti-grief.anti-nuke.enabled", true);
    }

    public int antiNukeMaxBreaksPerSecond() {
        return source.getInt("protect.anti-grief.anti-nuke.max-breaks-per-second", 20);
    }

    public int antiNukeMaxPlacesPerSecond() {
        return source.getInt("protect.anti-grief.anti-nuke.max-places-per-second", 20);
    }

    public double antiNukeFreecamRange() {
        return source.getDouble("protect.anti-grief.anti-nuke.freecam-range", 8.0);
    }

    @NotNull
    public String antiNukeAction() {
        return source.getString("protect.anti-grief.anti-nuke.action", "kick");
    }

    public boolean antiSpamEnabled() {
        return source.getBoolean("protect.anti-grief.anti-spam.enabled", true);
    }

    public int antiSpamMaxChatMessagesPer10s() {
        return source.getInt("protect.anti-grief.anti-spam.chat.max-messages-per-10s", 5);
    }

    public int antiSpamRepeatWindowSeconds() {
        return source.getInt("protect.anti-grief.anti-spam.chat.repeat-window-seconds", 30);
    }

    public int antiSpamMaxRepeats() {
        return source.getInt("protect.anti-grief.anti-spam.chat.max-repeats", 3);
    }

    public int antiSpamMaxCommandsPer10s() {
        return source.getInt("protect.anti-grief.anti-spam.commands.max-commands-per-10s", 8);
    }

    @NotNull
    public String antiSpamAction() {
        return source.getString("protect.anti-grief.anti-spam.action", "kick");
    }

    public boolean antiDropEnabled() {
        return source.getBoolean("protect.anti-grief.anti-drop.enabled", true);
    }

    public int antiDropMaxDropsPer10s() {
        return source.getInt("protect.anti-grief.anti-drop.max-drops-per-10s", 15);
    }

    /**
     * @return {@code protect.anti-grief.tnt-damage.enabled} - whether TNT/other explosions
     *     are allowed to damage blocks. TFM ref: {@code ALLOW_EXPLOSIONS} - see the config.yml
     *     comment on this key for the one deliberate behavior deviation from TFM's real
     *     implementation. Default {@code false} (safety-first for a public Free-OP server).
     */
    public boolean tntDamageEnabled() {
        return source.getBoolean("protect.anti-grief.tnt-damage.enabled", false);
    }

    /**
     * @return {@code protect.anti-grief.fire-spread.enabled} - whether fire is allowed to
     *     spread to new blocks (not flint-and-steel placement itself). TFM ref: {@code
     *     ALLOW_FIRE_SPREAD}. Default {@code false}.
     */
    public boolean fireSpreadEnabled() {
        return source.getBoolean("protect.anti-grief.fire-spread.enabled", false);
    }

    public boolean movementGuardEnabled() {
        return source.getBoolean("protect.anti-grief.movement.enabled", true);
    }

    public boolean movementGuardAlertOnly() {
        return source.getBoolean("protect.anti-grief.movement.alert-only", false);
    }

    public long worldBorderRadius() {
        return source.getLong("protect.anti-grief.movement.world-border.radius", 10000);
    }

    public boolean gameruleGuardEnabled() {
        return source.getBoolean("protect.anti-grief.gamerules.enabled", true);
    }

    @NotNull
    public Duration gameruleEnforceInterval() {
        return Duration.ofMinutes(source.getLong("protect.anti-grief.gamerules.enforce-interval-minutes", 5));
    }

    /** @return configured {@code gamerule -> value} pairs re-applied on the enforce interval */
    @NotNull
    public Map<String, String> gameruleDefaults() {
        Map<String, String> result = new LinkedHashMap<>();
        ConfigurationSection section = source.getConfigurationSection("protect.anti-grief.gamerules.defaults");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                result.put(key, String.valueOf(section.get(key)));
            }
        }
        return result;
    }

    @NotNull
    public java.util.List<String> maliciousGamerules() {
        return source.getStringList("protect.anti-grief.gamerules.malicious");
    }

    public boolean gamemodeFloodGuardEnabled() {
        return source.getBoolean("protect.anti-grief.gamemode-flood.enabled", true);
    }

    public int gamemodeMaxSwitchesPer10s() {
        return source.getInt("protect.anti-grief.gamemode-flood.max-switches-per-10s", 4);
    }

    public boolean mobSpamGuardEnabled() {
        return source.getBoolean("protect.anti-grief.mob-spam.enabled", true);
    }

    public int mobSpamMaxPerWorld() {
        return source.getInt("protect.anti-grief.mob-spam.max-mobs-per-world", 200);
    }

    public int mobSpamPerTypeCooldownSeconds() {
        return source.getInt("protect.anti-grief.mob-spam.per-type-cooldown-seconds", 1);
    }

    /**
     * @return whether ordinary ambient wild mob spawning ({@code CreatureSpawnEvent}'s
     *     {@code NATURAL} reason - not spawners, breeding, eggs, or plugin-spawned mobs) is
     *     disabled server-wide, for every world, hostile and passive mobs alike. Defaults to
     *     {@code true} - an explicit product decision for this server, not TFM-inherited.
     */
    public boolean disableNaturalMobSpawns() {
        return source.getBoolean("protect.anti-grief.mob-spam.disable-natural-spawns", true);
    }

    /**
     * @return {@code protect.anti-grief.mob-spam.only-summon-allowed} - when {@code true},
     *     blocks every {@code CreatureSpawnEvent} whose reason isn't {@code COMMAND} (i.e.
     *     the vanilla {@code /summon} command) - natural ambient spawns, spawners,
     *     breeding, spawn eggs, dispensed eggs, and everything else are all blocked, not
     *     just ambient natural spawning like {@link #disableNaturalMobSpawns} alone.
     *     Default {@code false} - an explicit, more aggressive opt-in on top of that
     *     narrower toggle, not this project's own general-purpose default.
     */
    public boolean onlySummonSpawnsAllowed() {
        return source.getBoolean("protect.anti-grief.mob-spam.only-summon-allowed", false);
    }

    // ---- protect.crash.* - event-level ("E1") crash-exploit protections ----------------

    public boolean crashItemGuardEnabled() {
        return source.getBoolean("protect.crash.items.enabled", true);
    }

    public int crashItemMaxNameLength() {
        return source.getInt("protect.crash.items.max-name-length", 64);
    }

    public int crashItemMaxLoreLines() {
        return source.getInt("protect.crash.items.max-lore-lines", 24);
    }

    public int crashItemMaxLoreLineLength() {
        return source.getInt("protect.crash.items.max-lore-line-length", 256);
    }

    public int crashItemMaxBookPages() {
        return source.getInt("protect.crash.items.max-book-pages", 100);
    }

    public int crashItemMaxBookPageLength() {
        return source.getInt("protect.crash.items.max-book-page-length", 1024);
    }

    public int crashItemMaxContainerDepth() {
        return source.getInt("protect.crash.items.max-container-depth", 2);
    }

    public int crashItemMaxPotionEffects() {
        return source.getInt("protect.crash.items.max-potion-effects", 24);
    }

    public int crashItemMaxFireworkExplosions() {
        return source.getInt("protect.crash.items.max-firework-explosions", 64);
    }

    public int crashItemMaxBannerPatterns() {
        return source.getInt("protect.crash.items.max-banner-patterns", 256);
    }

    public boolean crashEntityGuardEnabled() {
        return source.getBoolean("protect.crash.entities.enabled", true);
    }

    public int crashEntityMaxNameLength() {
        return source.getInt("protect.crash.entities.max-name-length", 64);
    }

    public boolean crashSignGuardEnabled() {
        return source.getBoolean("protect.crash.signs.enabled", true);
    }

    public int crashSignMaxLineLength() {
        return source.getInt("protect.crash.signs.max-line-length", 384);
    }

    public boolean crashSpawnerGuardEnabled() {
        return source.getBoolean("protect.crash.spawners.enabled", true);
    }

    /**
     * @return {@code protect.crash.packets.enabled} - the packet-level ("E2") tier's own
     *     toggle, independent of the event-level ("E1") guards' {@code
     *     modules.crashprotect.enabled}. No-ops on its own regardless of this flag if
     *     PacketEvents isn't installed - see {@code protect.crash.packet.CrashPacketService}.
     */
    public boolean crashPacketGuardEnabled() {
        return source.getBoolean("protect.crash.packets.enabled", true);
    }

    /** @return max chat-message packets per second before further ones are dropped. 0/negative disables the check. */
    public int crashPacketMaxChatPerSecond() {
        return source.getInt("protect.crash.packets.max-chat-per-second", 10);
    }

    /** @return max command packets per second before further ones are dropped. 0/negative disables the check. */
    public int crashPacketMaxCommandsPerSecond() {
        return source.getInt("protect.crash.packets.max-commands-per-second", 15);
    }

    /** @return max movement packets per second before further ones are dropped. 0/negative disables the check. */
    public int crashPacketMaxMovementPerSecond() {
        return source.getInt("protect.crash.packets.max-movement-per-second", 100);
    }

    // ---- protect.worldedit.* - command-preprocess-level WorldEdit/FAWE abuse prevention ----

    public boolean worldEditGuardEnabled() {
        return source.getBoolean("protect.worldedit.enabled", true);
    }

    public boolean worldEditThrottleEnabled() {
        return source.getBoolean("protect.worldedit.throttle.enabled", true);
    }

    public int worldEditThrottleMaxOps() {
        return source.getInt("protect.worldedit.throttle.max-ops", 5);
    }

    public long worldEditThrottleWindowMs() {
        return source.getLong("protect.worldedit.throttle.window-ms", 1000);
    }

    /** @return violations past {@code max-ops} within one window before an auto-eject kicks in. */
    public int worldEditThrottleEjectThreshold() {
        return source.getInt("protect.worldedit.throttle.eject-threshold", 5);
    }

    /**
     * @return block type names (no leading {@code minecraft:} needed) blocked from
     *     appearing anywhere in a non-staff player's WorldEdit/FAWE command arguments -
     *     ships non-empty by default (anvils + the full gravity-block family), unlike
     *     TFM's own empty default, which is what let the originally-reported mass-anvil
     *     incident happen in the first place.
     */
    @NotNull
    public java.util.List<String> worldEditBlockedBlockTypes() {
        return source.getStringList("protect.worldedit.blocked-block-types");
    }

    /** @return max radius/size argument for WorldEdit's radius-taking commands. 0/negative disables the check. */
    public int worldEditRadiusMax() {
        return source.getInt("protect.worldedit.radius-max", 200);
    }

    /**
     * @return {@code protect.worldedit.limits.max-volume} - the most blocks a single
     *     WorldEdit/FAWE edit session may write, enforced per-block inside the actual
     *     {@code EditSessionEvent} extent chain (not just a command-preprocess string
     *     check) - matches TFM's own {@code WORLDEDIT_LIMIT_MAX} default (10000). Applies
     *     to non-staff only - see {@code protect.worldedit.extent.RigelEditExtentChain}.
     */
    public int worldEditMaxVolume() {
        return source.getInt("protect.worldedit.limits.max-volume", 10000);
    }

    /**
     * @return {@code protect.worldedit.limits.max-containers} - the most container blocks
     *     (chests, hoppers, furnaces, ...) a single non-staff WorldEdit/FAWE edit session
     *     may place - matches TFM's own {@code WORLDEDIT_MAX_CONTAINERS} default (500).
     */
    public int worldEditMaxContainers() {
        return source.getInt("protect.worldedit.limits.max-containers", 500);
    }

    /**
     * @return {@code fun.disguise.forbidden-types} - disguise types (LibsDisguises {@code
     *     DisguiseType}/Bukkit {@code EntityType} names) no player may disguise as,
     *     enforced by {@code disguise.DisguiseAbuseGuard} - empty means "use TFM's own real
     *     default list" (see {@code disguise.DisallowedDisguises}), not "nothing forbidden".
     */
    @NotNull
    public java.util.List<String> disguiseForbiddenTypes() {
        return source.getStringList("fun.disguise.forbidden-types");
    }

    /** @return {@code investigate.radar.default-radius} - {@code /radar}'s radius when none is given. */
    public int radarDefaultRadius() {
        return source.getInt("investigate.radar.default-radius", 100);
    }

    /** @return {@code investigate.radar.max-radius} - the hard cap on {@code /radar}'s requested radius. */
    public int radarMaxRadius() {
        return source.getInt("investigate.radar.max-radius", 500);
    }

    // ---- protect.cleanup.* - /mobpurge and /entitywipe ---------------------------------

    public boolean mobpurgeExcludeNamed() {
        return source.getBoolean("protect.cleanup.mobpurge.exclude-named", true);
    }

    public boolean mobpurgeExcludeTamed() {
        return source.getBoolean("protect.cleanup.mobpurge.exclude-tamed", true);
    }

    public boolean mobpurgeExcludeLeashed() {
        return source.getBoolean("protect.cleanup.mobpurge.exclude-leashed", true);
    }

    /** @return minutes between automatic {@code /mobpurge} runs, or 0 to disable auto-purge */
    public int mobpurgeAutoIntervalMinutes() {
        return source.getInt("protect.cleanup.mobpurge.auto-interval-minutes", 0);
    }

    /** @return minutes between automatic {@code /entitywipe} runs, or 0 to disable auto-wipe */
    public int entitywipeAutoIntervalMinutes() {
        return source.getInt("protect.cleanup.entitywipe.auto-interval-minutes", 0);
    }

    public boolean potionGuardEnabled() {
        return source.getBoolean("protect.anti-grief.potions.enabled", true);
    }

    public boolean potionGuardBlockLethal() {
        return source.getBoolean("protect.anti-grief.potions.block-lethal", true);
    }

    public boolean textFilterEnabled() {
        return source.getBoolean("protect.anti-grief.text-filter.enabled", false);
    }

    @NotNull
    public java.util.List<String> textFilterPatterns() {
        return source.getStringList("protect.anti-grief.text-filter.patterns");
    }

    @NotNull
    public Duration textFilterBanDuration() {
        return Duration.ofHours(source.getLong("protect.anti-grief.text-filter.ban-duration-hours", 24));
    }

    /**
     * An operator-configured prefix override for this rank from {@code rank-prefixes} in
     * {@code config.yml}, picked up live via {@code /rmcm reload} - no rebuild needed to
     * retint or relabel a rank's bracket text. Empty if unset/blank, in which case
     * callers fall back to the rank's own hardcoded default ({@code Rank#prefix()}).
     * Mirrors TFM's own config-overridable rank prefixes (its {@code VAULT_PREFIX_*}
     * entries), generalized to any rank id rather than a fixed enum of config keys.
     *
     * @param rankId a {@code Rank#id()} value, e.g. {@code "senior_admin"}
     */
    @NotNull
    public Optional<String> rankPrefixOverride(@NotNull String rankId) {
        String raw = source.getString("rank-prefixes." + rankId, "");
        return (raw == null || raw.isBlank()) ? Optional.empty() : Optional.of(raw);
    }

    @NotNull
    public String scoreboardTitle() {
        return source.getString("scoreboard.title", "<gold><bold>RigelMC");
    }

    /**
     * MiniMessage format per line; {@code {online}}/{@code {max}}/{@code {ip}} (see
     * {@link #serverIpDomain}) are substituted before parsing.
     */
    @NotNull
    public java.util.List<String> scoreboardLines() {
        return source.getStringList("scoreboard.lines");
    }

    /** MiniMessage format; {@code {ip}} (see {@link #serverIpDomain}) is substituted before parsing. */
    @NotNull
    public String tablistHeader() {
        return source.getString("tablist.header", "<gold><bold>RigelMC");
    }

    /**
     * MiniMessage format; {@code {online}}/{@code {max}}/{@code {ip}} (see
     * {@link #serverIpDomain}) are substituted before parsing.
     */
    @NotNull
    public String tablistFooter() {
        return source.getString("tablist.footer", "<gray>Online: <white>{online}</white>/<white>{max}</white>");
    }

    public boolean motdEnabled() {
        return source.getBoolean("motd.enabled", true);
    }

    /**
     * @return configured MOTD texts, MiniMessage-formatted, {@code \n} for the second
     *     line. One is chosen at random per server-list ping if more than one is
     *     configured - a TFM-style rotating MOTD.
     */
    @NotNull
    public java.util.List<String> motdEntries() {
        return source.getStringList("motd.entries");
    }

    /**
     * @return {@code titles.<titleId>} -> configured list of usernames (case-insensitive
     *     match against last-known name) auto-granted that title on join - see
     *     {@code core.PlayerLoginListener}. Config-extensible: any title id under this
     *     section works, not just the three built-in titles.
     */
    @NotNull
    public Map<String, java.util.List<String>> configuredTitleHolders() {
        Map<String, java.util.List<String>> result = new LinkedHashMap<>();
        ConfigurationSection section = source.getConfigurationSection("titles");
        if (section != null) {
            for (String titleId : section.getKeys(false)) {
                result.put(titleId, section.getStringList(titleId));
            }
        }
        return result;
    }

    @NotNull
    public String flatlandsWorldName() {
        return source.getString("world.flatlands.world-name", "flatlands");
    }

    public boolean flatlandsAutowipeEnabled() {
        return source.getBoolean("world.flatlands.autowipe.enabled", true);
    }

    @NotNull
    public Duration flatlandsAutowipeInterval() {
        return Duration.ofHours(source.getLong("world.flatlands.autowipe.interval-hours", 24));
    }

    @NotNull
    public Duration flatlandsAutowipeWarning() {
        return Duration.ofMinutes(source.getLong("world.flatlands.autowipe.warning-minutes-before", 5));
    }

    /**
     * @return {@code world.flatlands.wipe-restarts-server} - whether {@code /wipeflatlands}
     *     (manual or automatic) restarts the whole server as part of the wipe, rather than
     *     deleting/recreating the world in-place while the server keeps running. Deleting a
     *     world folder while other worlds in the same JVM are still loaded has no OS-level
     *     guarantee every file handle has actually been released (observed unreliable on
     *     some setups); a restart-based wipe instead marks the wipe pending, restarts, and
     *     deletes the folder on the fresh boot before any world is loaded at all - the only
     *     way to make the delete itself fully reliable. Defaults to {@code true} since a
     *     reliable wipe was the explicit ask; set to {@code false} to go back to the
     *     lower-disruption in-place behavior (only the flatlands world's own players are
     *     affected, not the whole server) if the restart's downtime isn't wanted.
     */
    public boolean flatlandsWipeRestartsServer() {
        return source.getBoolean("world.flatlands.wipe-restarts-server", true);
    }

    @NotNull
    public String adminWorldName() {
        return source.getString("world.adminworld.world-name", "adminworld");
    }

    /**
     * @return {@code world.spawn.send-on-join} - {@code "always"}, {@code "first_join"}
     *     (default), or {@code "never"}. The spawn location itself is DB-backed (set via
     *     {@code /setspawn}), not config - see {@code world.SpawnService}'s javadoc for why.
     */
    @NotNull
    public String spawnSendOnJoin() {
        return source.getString("world.spawn.send-on-join", "first_join");
    }

    /** @return {@code world.spawn.send-on-respawn} - send deaths to spawn instead of bed/anchor. */
    public boolean spawnSendOnRespawn() {
        return source.getBoolean("world.spawn.send-on-respawn", false);
    }

    public boolean announceEnabled() {
        return source.getBoolean("announce.broadcast.enabled", true);
    }

    @NotNull
    public Duration announceInterval() {
        return Duration.ofMinutes(source.getLong("announce.broadcast.interval-minutes", 15));
    }

    @NotNull
    public String announcePrefix() {
        return source.getString("announce.broadcast.prefix", "");
    }

    @NotNull
    public java.util.List<String> announceMessages() {
        return source.getStringList("announce.broadcast.messages");
    }

    @NotNull
    public String discordBotToken() {
        return source.getString("discord.bot-token", "");
    }

    @NotNull
    public String discordPublicChannelId() {
        return source.getString("discord.public-channel-id", "");
    }

    @NotNull
    public String discordAdminChannelId() {
        return source.getString("discord.admin-channel-id", "");
    }

    @NotNull
    public String discordConsoleChannelId() {
        return source.getString("discord.console-channel-id", "");
    }

    /** Minimum rank id allowed to execute console commands via the admin Discord channel. */
    @NotNull
    public String discordConsoleCommandMinRank() {
        return source.getString("discord.console-command-min-rank", "senior_admin");
    }

    @NotNull
    public Duration discordLinkCodeTtl() {
        return Duration.ofMinutes(source.getLong("discord.link-code-ttl-minutes", 5));
    }

    public boolean webPanelEnabled() {
        return isModuleEnabled("webpanel");
    }

    public int webPanelPort() {
        return source.getInt("webpanel.port", 8081);
    }

    @NotNull
    public String webPanelBindAddress() {
        return source.getString("webpanel.bind-address", "127.0.0.1");
    }

    /** @return how often (seconds) the web panel's in-memory snapshot is rebuilt. */
    public int webPanelRefreshSeconds() {
        return source.getInt("webpanel.refresh-seconds", 5);
    }

    /** @return whether the web panel's read-only schematics browse/download page is active. */
    public boolean webPanelSchematicsEnabled() {
        return source.getBoolean("webpanel.schematics.enabled", true);
    }

    /**
     * @return {@code webpanel.schematics.directory} - explicit override for the schematics
     *     folder the web panel lists/serves from. Empty means "auto-detect": whichever of
     *     WorldEdit/FAWE is installed, its own {@code getDataFolder()}{@code /schematics} -
     *     see {@code webpanel.SchematicsService}.
     */
    @NotNull
    public String webPanelSchematicsDirectory() {
        return source.getString("webpanel.schematics.directory", "");
    }

    @NotNull
    public FileConfiguration raw() {
        return source;
    }
}
