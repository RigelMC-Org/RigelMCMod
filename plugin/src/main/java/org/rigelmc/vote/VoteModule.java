package org.rigelmc.vote;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;

/**
 * {@code /vote record <player>} - console/RCON-only, meant to be the "run this command on
 * vote" target configured in an external vote-listener plugin's own reward settings.
 * Deliberately does not soft-depend on any specific vote plugin's own Bukkit event (e.g.
 * NuVotifier's {@code VotifierEvent}) - nearly every vote plugin supports "run a command on
 * a successful vote" as a universal feature, so a plain command target here works
 * regardless of which one an operator ends up choosing, rather than locking this project to
 * one specific plugin's API. Same console-only security-boundary reasoning as {@code
 * store.StoreModule}/{@code rank.RankAdminModule}'s {@code /adminconfig} - a player must
 * never be able to trigger their own vote reward.
 */
public final class VoteModule implements PluginModule {

    private final VoteRecordService voteRecordService;
    private final PlayerDao playerDao;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public VoteModule(
            @NotNull VoteRecordService voteRecordService, @NotNull PlayerDao playerDao, @NotNull ExecutorService dbExecutor) {
        this.voteRecordService = voteRecordService;
        this.playerDao = playerDao;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "vote";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(voteCommand(), "Console/RCON only - record a vote and grant its reward", List.of());
    }

    private LiteralCommandNode<CommandSourceStack> voteCommand() {
        return Commands.literal("vote")
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/vote record <player>"))
                .then(Commands.literal("record")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/vote record <player>"))
                        .then(Commands.argument("player", StringArgumentType.word()).executes(this::executeRecord)))
                .build();
    }

    private int executeRecord(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(
                    "/vote can only be run from the server console or RCON - it's meant to be triggered by an"
                            + " external vote-listener plugin, never by a player.",
                    NamedTextColor.RED));
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "player");
        RigelConfig config = plugin.rigelConfig();

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sync(() -> sender.sendMessage(Component.text(
                            "No player found matching '" + targetName + "' - they must have joined at least once.",
                            NamedTextColor.RED)));
                    return;
                }
                VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                        targetUuidOpt.get(), System.currentTimeMillis(), config.voteRewardPerVote(), config.voteStreakWindow(),
                        config.voteStreakBonuses(), config.voteMilestoneBonuses(), config.voteMilestoneTitles());
                sync(() -> sender.sendMessage(Component.text(describeOutcome(targetName, outcome), NamedTextColor.GREEN)));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Database error handling /vote record", e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred. Check the console.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    @NotNull
    private String describeOutcome(String targetName, VoteRecordService.VoteOutcome outcome) {
        StringBuilder sb = new StringBuilder("Recorded a vote for ")
                .append(targetName).append(" - streak ").append(outcome.currentStreak())
                .append(", total ").append(outcome.totalVotes()).append('.');
        if (outcome.baseReward() > 0) {
            sb.append(" Granted ").append(outcome.baseReward()).append(" Coins.");
        }
        if (outcome.streakBonus() != null) {
            sb.append(" Streak bonus: ").append(outcome.streakBonus()).append(" Coins.");
        }
        if (outcome.milestoneBonus() != null) {
            sb.append(" Milestone bonus: ").append(outcome.milestoneBonus()).append(" Coins.");
        }
        if (outcome.titleGranted() != null) {
            sb.append(" Milestone title granted: ").append(outcome.titleGranted()).append('.');
        }
        return sb.toString();
    }

    /** Resolves an online-or-offline player name to a UUID via {@link PlayerDao} - runs off the main thread. */
    private Optional<UUID> resolveUuid(String name) throws SQLException {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        return playerDao.findByLastKnownName(name).map(PlayerRecord::uuid);
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
