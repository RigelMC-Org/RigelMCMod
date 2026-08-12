package org.rigelmc.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.discord.DiscordLinkDao;
import org.rigelmc.discord.DiscordLinkService;

/**
 * Fully pure-JDBC, no MockBukkit or live Discord gateway - {@link InviteCreditService}
 * never touches Discord4J at all, only {@link DiscordLinkService} (also pure JDBC) and
 * {@link EconomyService} (same).
 */
class InviteCreditServiceTest {

    private HikariDataSource dataSource;
    private EconomyService economyService;
    private DiscordLinkService discordLinkService;
    private InviteCreditService inviteCreditService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.economyService = new EconomyService(new EconomyDao(dataSource));
        this.discordLinkService = new DiscordLinkService(new DiscordLinkDao(dataSource));
        this.inviteCreditService =
                new InviteCreditService(new InviteCreditDao(dataSource), economyService, discordLinkService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void sweepCreditsAResolvedInviterPastItsEligibleTime() throws Exception {
        UUID inviterUuid = UUID.randomUUID();
        discordLinkService.consumeLinkCode(linkCodeFor(inviterUuid), "discord-inviter-1", 500L);
        inviteCreditService.schedulePendingCredit("guild-1", "abc123", "discord-inviter-1", "discord-invited-1", 100, 1000L, 2000L);

        InviteCreditService.SweepResult result = inviteCreditService.sweep(2000L);

        assertEquals(1, result.credited());
        assertEquals(0, result.stillWaitingOnLink());
        assertEquals(100L, economyService.balanceOf(inviterUuid));
    }

    @Test
    void sweepLeavesACreditPendingWhenNotYetEligible() throws Exception {
        UUID inviterUuid = UUID.randomUUID();
        discordLinkService.consumeLinkCode(linkCodeFor(inviterUuid), "discord-inviter-1", 500L);
        inviteCreditService.schedulePendingCredit("guild-1", "abc123", "discord-inviter-1", "discord-invited-1", 100, 1000L, 5000L);

        InviteCreditService.SweepResult result = inviteCreditService.sweep(2000L); // before eligible_at (5000)

        assertEquals(0, result.credited());
        assertEquals(0L, economyService.balanceOf(inviterUuid));
    }

    @Test
    void sweepLeavesACreditPendingWhenTheInviterIsntLinkedYet() throws Exception {
        inviteCreditService.schedulePendingCredit(
                "guild-1", "abc123", "discord-inviter-unlinked", "discord-invited-1", 100, 1000L, 2000L);

        InviteCreditService.SweepResult result = inviteCreditService.sweep(2000L);

        assertEquals(0, result.credited());
        assertEquals(1, result.stillWaitingOnLink());
    }

    @Test
    void sweepEventuallyPaysOutOnceTheInviterLinksLater() throws Exception {
        inviteCreditService.schedulePendingCredit(
                "guild-1", "abc123", "discord-inviter-1", "discord-invited-1", 100, 1000L, 2000L);
        InviteCreditService.SweepResult firstSweep = inviteCreditService.sweep(2000L);
        assertEquals(0, firstSweep.credited());

        UUID inviterUuid = UUID.randomUUID();
        discordLinkService.consumeLinkCode(linkCodeFor(inviterUuid), "discord-inviter-1", 2500L); // links after the fact

        InviteCreditService.SweepResult secondSweep = inviteCreditService.sweep(3000L); // no expiry - still due
        assertEquals(1, secondSweep.credited());
        assertEquals(100L, economyService.balanceOf(inviterUuid));
    }

    @Test
    void cancelForLeaveStopsAStillPendingCreditFromEverBeingPaid() throws Exception {
        UUID inviterUuid = UUID.randomUUID();
        discordLinkService.consumeLinkCode(linkCodeFor(inviterUuid), "discord-inviter-1", 500L);
        inviteCreditService.schedulePendingCredit("guild-1", "abc123", "discord-inviter-1", "discord-invited-1", 100, 1000L, 2000L);

        inviteCreditService.cancelForLeave("discord-invited-1");

        InviteCreditService.SweepResult result = inviteCreditService.sweep(2000L);
        assertEquals(0, result.credited());
        assertEquals(0L, economyService.balanceOf(inviterUuid));
    }

    @Test
    void cancelForLeaveNeverUndoesAnAlreadyCreditedReward() throws Exception {
        UUID inviterUuid = UUID.randomUUID();
        discordLinkService.consumeLinkCode(linkCodeFor(inviterUuid), "discord-inviter-1", 500L);
        inviteCreditService.schedulePendingCredit("guild-1", "abc123", "discord-inviter-1", "discord-invited-1", 100, 1000L, 2000L);
        inviteCreditService.sweep(2000L); // pays out first

        inviteCreditService.cancelForLeave("discord-invited-1"); // too late - already CREDITED

        assertEquals(100L, economyService.balanceOf(inviterUuid)); // unaffected
    }

    @Test
    void sweepIsARewardAmountSnapshotNotALiveConfigRead() throws Exception {
        UUID inviterUuid = UUID.randomUUID();
        discordLinkService.consumeLinkCode(linkCodeFor(inviterUuid), "discord-inviter-1", 500L);
        // A reward amount different from any "current config" default - proves sweep() uses
        // exactly what was passed to schedulePendingCredit, not a fresh config read.
        inviteCreditService.schedulePendingCredit("guild-1", "abc123", "discord-inviter-1", "discord-invited-1", 777, 1000L, 2000L);

        inviteCreditService.sweep(2000L);

        assertEquals(777L, economyService.balanceOf(inviterUuid));
    }

    /** Generates and immediately captures a real one-time link code via {@link DiscordLinkService}, for tests that need a linked account already present. */
    private String linkCodeFor(UUID uuid) throws Exception {
        String code = discordLinkService.createLinkCode(uuid, java.time.Duration.ofMinutes(5), 0L);
        assertTrue(code.length() > 0);
        return code;
    }
}
