package org.rigelmc.guild.plot;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.economy.EconomyService;
import org.rigelmc.economy.InsufficientFundsException;
import org.rigelmc.economy.LedgerReason;

/**
 * Orchestrates {@link PlotCosmeticDao} (purchase receipts) + {@link EconomyService} (spend) -
 * deliberately no in-memory cache, matching {@code economy.EconomyService}'s own "not read
 * on a hot per-tick path" precedent (a purchase/apply check happens once per {@code /guild
 * plot cosmetic} invocation, nowhere near hot enough to justify one). Never touches Bukkit -
 * actually placing the resulting blocks is {@link PlotCosmeticApplier#apply}'s job, called
 * separately by {@code guild.GuildCommand} after a successful {@link #buy}.
 */
public final class PlotCosmeticService {

    private final PlotCosmeticDao plotCosmeticDao;
    private final EconomyService economyService;

    public PlotCosmeticService(@NotNull PlotCosmeticDao plotCosmeticDao, @NotNull EconomyService economyService) {
        this.plotCosmeticDao = plotCosmeticDao;
        this.economyService = economyService;
    }

    public boolean isPurchased(int guildId, @NotNull PlotCosmetic cosmetic) throws SQLException {
        return plotCosmeticDao.isPurchased(guildId, cosmetic.key());
    }

    @NotNull
    public Set<String> purchasedKeysFor(int guildId) throws SQLException {
        return plotCosmeticDao.findPurchasedKeys(guildId);
    }

    public enum BuyResult { PURCHASED, ALREADY_OWNED_REAPPLIED, INSUFFICIENT_FUNDS }

    public record BuyOutcome(@NotNull BuyResult result, long newBalance) {
    }

    /**
     * If {@code guildId} doesn't already own {@code cosmetic}: debits {@code buyerUuid}'s
     * personal balance (never a shared guild treasury - no guild-bank concept in scope) and
     * records the purchase. If it's already owned, this is a free re-application - no
     * charge, {@link BuyResult#ALREADY_OWNED_REAPPLIED} - matching {@link PlotCosmetic}'s
     * "buy implies immediate apply; re-applying an owned cosmetic later is free" contract.
     * {@link PlotCosmetic#FLOOR_DEFAULT}'s zero price skips the {@link EconomyService} call
     * entirely rather than debiting zero (that call rejects a non-positive amount outright).
     *
     * <p>Never places any blocks itself - the caller applies {@link
     * PlotCosmeticApplier#blockWritesFor} afterward on success, whether this was a fresh
     * purchase or a free re-apply.</p>
     */
    @NotNull
    public synchronized BuyOutcome buy(int guildId, @NotNull UUID buyerUuid, @NotNull PlotCosmetic cosmetic, long now) throws SQLException {
        if (plotCosmeticDao.isPurchased(guildId, cosmetic.key())) {
            return new BuyOutcome(BuyResult.ALREADY_OWNED_REAPPLIED, economyService.balanceOf(buyerUuid));
        }
        if (cosmetic.price() > 0) {
            long newBalance;
            try {
                newBalance = economyService.debit(
                        buyerUuid, cosmetic.price(), LedgerReason.PLOT_COSMETIC, cosmetic.key(), buyerUuid);
            } catch (InsufficientFundsException e) {
                return new BuyOutcome(BuyResult.INSUFFICIENT_FUNDS, economyService.balanceOf(buyerUuid));
            }
            plotCosmeticDao.recordPurchase(guildId, cosmetic.key(), buyerUuid, now);
            return new BuyOutcome(BuyResult.PURCHASED, newBalance);
        }
        plotCosmeticDao.recordPurchase(guildId, cosmetic.key(), buyerUuid, now);
        return new BuyOutcome(BuyResult.PURCHASED, economyService.balanceOf(buyerUuid));
    }
}
