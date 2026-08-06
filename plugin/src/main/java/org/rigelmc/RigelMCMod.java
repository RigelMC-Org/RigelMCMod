package org.rigelmc;

import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.announce.AnnounceModule;
import org.rigelmc.api.ban.BanProvider;
import org.rigelmc.api.rank.RankProvider;
import org.rigelmc.audit.AuditLogDao;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.chat.ChatModule;
import org.rigelmc.chat.PlayerDisplayService;
import org.rigelmc.chat.TabListBroadcaster;
import org.rigelmc.command.CommandRegistrar;
import org.rigelmc.core.AutoOpModule;
import org.rigelmc.core.ConfigUpdater;
import org.rigelmc.core.LockdownModule;
import org.rigelmc.core.TickFreezeModule;
import org.rigelmc.core.MessagesConfig;
import org.rigelmc.core.PlayerLoginListener;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.DataSourceFactory;
import org.rigelmc.data.MigrationRunner;
import org.rigelmc.data.RigelExecutors;
import org.rigelmc.data.dao.IpHistoryDao;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.WorldStateDao;
import org.rigelmc.discord.DiscordBotManager;
import org.rigelmc.discord.DiscordLinkDao;
import org.rigelmc.discord.DiscordLinkService;
import org.rigelmc.discord.DiscordModule;
import org.rigelmc.disguise.DisallowedDisguises;
import org.rigelmc.disguise.DisguiseModule;
import org.rigelmc.fun.FunModule;
import org.rigelmc.fun.SpawnMobModule;
import org.rigelmc.identity.IdentityService;
import org.rigelmc.identity.IpHasher;
import org.rigelmc.motd.MotdModule;
import org.rigelmc.myadmin.LoginMessageDao;
import org.rigelmc.myadmin.MyAdminModule;
import org.rigelmc.scoreboard.ScoreboardModule;
import org.rigelmc.scoreboard.ScoreboardService;
import org.rigelmc.skin.SkinModule;
import org.rigelmc.nick.NickDao;
import org.rigelmc.nick.NickModule;
import org.rigelmc.nick.NickService;
import org.rigelmc.protect.ProtectModule;
import org.rigelmc.protect.antigrief.AntiGriefModule;
import org.rigelmc.protect.antigrief.EntityCleanupModule;
import org.rigelmc.investigate.InvestigateModule;
import org.rigelmc.investigate.SpyService;
import org.rigelmc.protect.crash.CrashProtectModule;
import org.rigelmc.protect.area.AreaDao;
import org.rigelmc.protect.area.AreaFlagDao;
import org.rigelmc.protect.area.AreaMemberDao;
import org.rigelmc.protect.area.ProtectAreaModule;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.protect.worldedit.WorldEditProtectModule;
import org.rigelmc.webpanel.WebPanelModule;
import org.rigelmc.punish.PunishModule;
import org.rigelmc.punish.ban.BanDao;
import org.rigelmc.punish.ban.BanEnforcementListener;
import org.rigelmc.punish.ban.BanProviderImpl;
import org.rigelmc.punish.ban.BanService;
import org.rigelmc.punish.cage.CageDao;
import org.rigelmc.punish.cage.CageService;
import org.rigelmc.punish.freeze.FreezeService;
import org.rigelmc.punish.mute.MuteDao;
import org.rigelmc.punish.mute.MuteService;
import org.rigelmc.punish.warn.StrikeDao;
import org.rigelmc.punish.warn.StrikeService;
import org.rigelmc.rank.NameTagService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.PrefixService;
import org.rigelmc.rank.RankAdminModule;
import org.rigelmc.rank.RankProviderImpl;
import org.rigelmc.rank.RankRepository;
import org.rigelmc.rank.RankService;
import org.rigelmc.rank.TitleRepository;
import org.rigelmc.rank.TitleService;
import org.rigelmc.rank.VaultChatBridge;
import org.rigelmc.rmcm.RmcmModule;
import org.rigelmc.rollback.CoreProtectBridge;
import org.rigelmc.tag.TagModule;
import org.rigelmc.tag.TagService;
import org.rigelmc.vanish.VanishModule;
import org.rigelmc.world.AdminWorldService;
import org.rigelmc.world.FlatlandsService;
import org.rigelmc.world.SpawnDao;
import org.rigelmc.world.SpawnService;
import org.rigelmc.world.WorldModule;

/**
 * RigelMCMod — an admin-rank, anti-grief, and punishment toolkit for Free-OP PaperMC
 * servers, built as a modern, better-optimized alternative to TotalFreedomMod.
 *
 * <p>Original author: LightWarp.</p>
 */
public final class RigelMCMod extends JavaPlugin {

    // volatile: read from background DB-executor threads (e.g. PrefixService#refresh
    // during a join) as well as the main thread, and reassigned by /rmcm reload - needs
    // to be visible across threads without relying on incidental executor happens-before
    // timing.
    private volatile RigelConfig rigelConfig;
    // volatile for the same reason as rigelConfig above - read from command-execution
    // threads (dbExecutor callbacks building a broadcast Component) and reassigned by
    // /rmcm reload.
    private volatile MessagesConfig messages;
    private CommandRegistrar commandRegistrar;
    private HikariDataSource dataSource;
    private ExecutorService dbExecutor;
    private DiscordBotManager discordBotManager;
    private List<PluginModule> modules = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // saveDefaultConfig() only copies the bundled config.yml if the live file doesn't
        // exist at all (a fresh install). On an existing install being updated to a newer
        // RigelMCMod jar, the live file stays exactly as it was - so this fills in any
        // keys a newer version introduced without touching anything already there. See
        // ConfigUpdater's javadoc for the full rationale. A fresh install's copied file is
        // already identical to the bundled default, so this is a harmless no-op then.
        if (ConfigUpdater.update(new File(getDataFolder(), "config.yml"), getResource("config.yml"), getLogger())) {
            reloadConfig();
        }
        this.rigelConfig = new RigelConfig(getConfig());
        this.messages = loadMessagesConfig();

        try {
            initializeDataLayer();
        } catch (SQLException e) {
            getLogger()
                    .log(Level.SEVERE, "Failed to initialize the database - disabling RigelMCMod.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.commandRegistrar = new CommandRegistrar(this, modules, rigelConfig);
        commandRegistrar.registerLifecycleHandler();

        for (PluginModule module : modules) {
            if (module.isEnabled(rigelConfig)) {
                module.registerListeners(this);
                getLogger().info("Enabled module: " + module.id());
            } else {
                getLogger().info("Module disabled by config: " + module.id());
            }
        }

        getLogger().info("RigelMCMod enabled.");
    }

    @Override
    public void onDisable() {
        if (discordBotManager != null) {
            discordBotManager.shutdown();
        }
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Database executor did not terminate cleanly within 10s.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null) {
            dataSource.close();
        }
        getLogger().info("RigelMCMod disabled.");
    }

    @NotNull
    public RigelConfig rigelConfig() {
        return rigelConfig;
    }

    /** @return the loaded {@code messages.yml} - the public broadcast strings, see {@link MessagesConfig}. */
    @NotNull
    public MessagesConfig messages() {
        return messages;
    }

    /** @return every {@link PluginModule} this plugin built at startup, enabled or not - see {@code rmcm.RmcmModule}. */
    @NotNull
    public List<PluginModule> modules() {
        return modules;
    }

    /**
     * Re-reads {@code config.yml} from disk and rebuilds {@link #rigelConfig}, then gives
     * every currently-enabled module a chance to recompute any state it caches from
     * config values (see {@link PluginModule#onConfigReload}) - the mechanism behind
     * {@code /rmcm reload}. Deliberately does <b>not</b> touch {@code modules.*.enabled}:
     * a module that was already active keeps running with its existing listeners/commands
     * still registered even if its config toggle now says otherwise, and a module that
     * was disabled at startup was never constructed with listeners/commands active in the
     * first place - flipping either case live would mean dynamically
     * registering/unregistering Bukkit listeners and Brigadier command trees, which this
     * plugin doesn't support; that class of change still needs a restart.
     *
     * <p>Also re-runs {@link ConfigUpdater} against the same file before the real reload,
     * so a jar swapped in while the server keeps running (no restart) picks up any newly
     * introduced config keys immediately too, not just on the next full restart.
     *
     * <p>Reloads {@code config.yml} from disk - but only if it actually parses. Deliberately
     * does <b>not</b> just call the inherited {@link JavaPlugin#reloadConfig()} directly:
     * that method goes through {@link YamlConfiguration#loadConfiguration(File)}, whose
     * static factory swallows a broken file internally (a {@code Cannot load ...}
     * console log, nothing thrown) and still hands back a - now incomplete or empty -
     * config object, silently replacing the good in-memory config with a broken one. A
     * bad hand-edit (e.g. a stray tab character, invalid YAML) would otherwise get
     * reported back to whoever ran {@code /rmcm reload} as a successful reload while the
     * server quietly ran on a half-empty config underneath them - confirmed via a real
     * tab-indentation typo hitting exactly this in testing.
     *
     * <p>Fix: parse the file ourselves first, using the throwing instance method
     * ({@link YamlConfiguration#load(File)}) rather than the swallowing static one. Only
     * if that succeeds do we call through to the real {@link #reloadConfig()} (which,
     * parsing the same now-known-good file, will succeed too) and notify modules -
     * otherwise the previous, already-loaded config stays in effect untouched and the
     * caller is told the reload failed.
     *
     * @return {@code true} if config.yml parsed and was reloaded; {@code false} if it
     *     failed to parse (nothing changed) - see {@link
     *     org.rigelmc.rmcm.RmcmModule#executeReload} for how this is surfaced to whoever
     *     ran {@code /rmcm reload}
     */
    public boolean reloadRigelConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            new YamlConfiguration().load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.WARNING,
                    "config.yml failed to parse - keeping the previously loaded config in effect. Fix the syntax"
                            + " error below and try /rmcm reload again.",
                    e);
            return false;
        }

        ConfigUpdater.update(configFile, getResource("config.yml"), getLogger());

        reloadConfig();
        this.rigelConfig = new RigelConfig(getConfig());
        this.messages = loadMessagesConfig();
        for (PluginModule module : modules) {
            if (module.isEnabled(rigelConfig)) {
                module.onConfigReload(rigelConfig);
            }
        }
        getLogger().info("config.yml reloaded.");
        return true;
    }

    /**
     * Loads (or re-loads) {@code messages.yml} - copies the bundled default if the live
     * file doesn't exist yet, merges in any new keys a version update introduced (same
     * {@link ConfigUpdater} mechanism as {@code config.yml}), then parses it. Falls back
     * to the bundled defaults in memory (not written to disk) if the live file fails to
     * parse, rather than failing plugin startup or a reload over it - a broken
     * messages.yml should degrade to default wording, never break the plugin.
     */
    @NotNull
    private MessagesConfig loadMessagesConfig() {
        saveResource("messages.yml", false);
        File messagesFile = new File(getDataFolder(), "messages.yml");
        ConfigUpdater.update(messagesFile, getResource("messages.yml"), getLogger());
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(messagesFile);
            return new MessagesConfig(loaded);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.WARNING,
                    "messages.yml failed to parse - falling back to built-in default wording until this is"
                            + " fixed.",
                    e);
            return new MessagesConfig(new YamlConfiguration());
        }
    }

    /**
     * Builds the data layer (Hikari + migrations), every DAO/service, applies the
     * first-admin bootstrap, and assembles {@link #modules}. Split out of
     * {@link #onEnable} purely for readability - everything here still runs
     * synchronously during plugin enable, which is intentional: the plugin must not
     * finish enabling in a half-initialized state.
     */
    private void initializeDataLayer() throws SQLException {
        this.dataSource = DataSourceFactory.create(rigelConfig, getDataFolder());
        new MigrationRunner(getLogger(), rigelConfig.isMysqlStorage()).migrate(dataSource);
        this.dbExecutor = RigelExecutors.newDatabaseExecutor();

        IpHasher ipHasher = new IpHasher(getDataFolder());
        IdentityService identityService = new IdentityService(getLogger());

        PlayerDao playerDao = new PlayerDao(dataSource);
        IpHistoryDao ipHistoryDao = new IpHistoryDao(dataSource);
        AuditLogService auditLogService = new AuditLogService(new AuditLogDao(dataSource));

        RankRepository rankRepository = new RankRepository(dataSource);
        RankService rankService = new RankService(rankRepository, playerDao);
        rankService.initialize();

        TitleRepository titleRepository = new TitleRepository(dataSource);
        TitleService titleService = new TitleService(titleRepository);
        titleService.initialize();

        PermissionGate permissionGate = new PermissionGate(this, rankService);
        permissionGate.registerKnownPermissions();

        BanService banService = new BanService(new BanDao(dataSource), playerDao, ipHistoryDao, auditLogService);
        MuteService muteService = new MuteService(new MuteDao(dataSource), auditLogService);
        CoreProtectBridge coreProtectBridge = new CoreProtectBridge(getLogger());

        DiscordLinkService discordLinkService = new DiscordLinkService(new DiscordLinkDao(dataSource));
        DiscordBotManager discordBotManager = new DiscordBotManager(getLogger());

        FlatlandsService flatlandsService = new FlatlandsService(this, new WorldStateDao(dataSource), dbExecutor);
        AdminWorldService adminWorldService = new AdminWorldService(this, permissionGate);
        SpawnService spawnService = new SpawnService(this, new SpawnDao(dataSource));

        PrefixService prefixService = new PrefixService(rankService, titleService);
        NickService nickService = new NickService(new NickDao(dataSource));
        TagService tagService = new TagService(); // session-only, no DAO - see TagService's javadoc
        PlayerDisplayService displayService = new PlayerDisplayService(prefixService, nickService, tagService);
        TabListBroadcaster tabListBroadcaster = new TabListBroadcaster();

        // One shared Scoreboard for both the optional sidebar (ScoreboardService) and
        // nametag-color teams (NameTagService, always active regardless of that toggle)
        // - both need to live on the exact same instance every player's client is
        // assigned to. See NameTagService's javadoc for the full rationale.
        org.bukkit.scoreboard.Scoreboard sharedScoreboard =
                getServer().getScoreboardManager().getNewScoreboard();
        ScoreboardService scoreboardService = new ScoreboardService(sharedScoreboard);
        NameTagService nameTagService = new NameTagService(sharedScoreboard);
        VaultChatBridge vaultChatBridge = new VaultChatBridge(getLogger());
        FreezeService freezeService = new FreezeService(this);
        CageService cageService = new CageService(this, new CageDao(dataSource));
        StrikeService strikeService = new StrikeService(new StrikeDao(dataSource));
        LoginMessageDao loginMessageDao = new LoginMessageDao(dataSource);
        ProtectAreaService protectAreaService = new ProtectAreaService(
                new AreaDao(dataSource), new AreaMemberDao(dataSource), new AreaFlagDao(dataSource), permissionGate);
        DisallowedDisguises disallowedDisguises = new DisallowedDisguises(rigelConfig());

        getServer()
                .getServicesManager()
                .register(RankProvider.class, new RankProviderImpl(rankService, permissionGate, getLogger()), this,
                        org.bukkit.plugin.ServicePriority.Normal);
        getServer()
                .getServicesManager()
                .register(BanProvider.class, new BanProviderImpl(banService, getLogger()), this,
                        org.bukkit.plugin.ServicePriority.Normal);

        getServer()
                .getPluginManager()
                .registerEvents(new BanEnforcementListener(banService, ipHasher, getLogger()), this);
        getServer()
                .getPluginManager()
                .registerEvents(
                        new PlayerLoginListener(
                                this, playerDao, ipHistoryDao, ipHasher, identityService, rankService,
                                titleService, permissionGate, prefixService, nickService, tagService,
                                displayService, tabListBroadcaster, nameTagService, vaultChatBridge,
                                loginMessageDao, dbExecutor),
                        this);

        ProtectModule protectModule = new ProtectModule(permissionGate);

        List<PluginModule> built = new ArrayList<>();
        built.add(new PunishModule(
                banService, muteService, playerDao, ipHistoryDao, ipHasher, coreProtectBridge, permissionGate,
                rankService, freezeService, cageService, strikeService, auditLogService, dbExecutor));
        built.add(new ChatModule(
                muteService, permissionGate, discordBotManager, displayService, tabListBroadcaster,
                nameTagService));
        built.add(protectModule);
        built.add(new DiscordModule(
                discordLinkService, discordBotManager, rankService, permissionGate, auditLogService, dbExecutor));
        built.add(new WorldModule(flatlandsService, adminWorldService, spawnService, permissionGate));
        built.add(new AnnounceModule(permissionGate));
        built.add(new AutoOpModule(permissionGate, prefixService, displayService));
        built.add(new RankAdminModule(
                rankService, titleService, playerDao, permissionGate, prefixService, displayService, nameTagService,
                vaultChatBridge, auditLogService, dbExecutor));
        built.add(new VanishModule(permissionGate));
        built.add(new MotdModule());
        built.add(new ScoreboardModule(scoreboardService));
        built.add(new TagModule(tagService, displayService));
        built.add(new NickModule(nickService, displayService, permissionGate, dbExecutor));
        built.add(new RmcmModule(permissionGate));
        built.add(new AntiGriefModule(permissionGate, banService, dbExecutor));
        built.add(new CrashProtectModule(permissionGate));
        built.add(new WorldEditProtectModule(permissionGate, strikeService, protectAreaService));
        built.add(new ProtectAreaModule(permissionGate, protectAreaService, auditLogService, dbExecutor));
        built.add(new DisguiseModule(permissionGate, auditLogService, dbExecutor, disallowedDisguises));
        built.add(new SkinModule());
        built.add(new InvestigateModule(permissionGate, new SpyService(), playerDao, ipHistoryDao, ipHasher, dbExecutor));
        built.add(new WebPanelModule(
                playerDao, new BanDao(dataSource), new MuteDao(dataSource), permissionGate, titleService,
                dbExecutor));
        built.add(new EntityCleanupModule(permissionGate));
        built.add(new LockdownModule(rankService));
        built.add(new TickFreezeModule(permissionGate));
        built.add(new FunModule(permissionGate));
        built.add(new SpawnMobModule(permissionGate));
        built.add(new MyAdminModule(loginMessageDao, playerDao, rankService, permissionGate, dbExecutor));
        this.modules = List.copyOf(built);
        this.discordBotManager = discordBotManager;
    }
}
