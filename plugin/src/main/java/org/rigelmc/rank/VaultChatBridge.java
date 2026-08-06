package org.rigelmc.rank;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Pushes RigelMCMod's composed rank/title prefix into Vault's {@code Chat} API
 * ({@code net.milkbowl.vault.chat.Chat}) on join and on every rank/title change, so a
 * Vault-aware chat-formatting plugin (EssentialsChat is the common one on a Free-OP
 * server run alongside Essentials, per this project's design) renders the correct,
 * current prefix.
 *
 * <p>This exists because RigelMCMod's own direct chat/tab-list rendering
 * ({@code chat.ChatFormatListener}, {@code chat.PlayerDisplayService}) can be silently
 * superseded by another plugin's own formatter that doesn't source from either the
 * vanilla chat format string or {@code Player#playerListName} at all - e.g. EssentialsChat
 * building its own display name from whatever a permissions/chat-bridge plugin has
 * registered with Vault. Fighting that plugin for final control of the chat event or the
 * tab-list entry is a losing, fragile battle (confirmed in real testing); cooperating by
 * writing correct data to the same shared API it already reads from is far more robust.
 * RigelMCMod's own rendering stays active regardless - this is additive, for the common
 * case where Vault plus a Chat-capable plugin actually is present, not a replacement for
 * standalone operation.</p>
 *
 * <p>Reflection-based (like {@code rollback.CoreProtectBridge}) rather than a
 * compile-time dependency - VaultAPI isn't published anywhere this project's build
 * resolves from (Maven Central/PaperMC's repo), only JitPack, and adding an extra build
 * repository for one optional soft-integration isn't worth it. Tries both Vault Chat API
 * shapes (the modern {@code OfflinePlayer}-based overload, falling back to the older
 * world-scoped {@code String} one) since which is present depends on the installed Vault
 * version and this project has no Vault jar to verify against directly.</p>
 */
public final class VaultChatBridge {

    private final Logger logger;
    private final boolean available;
    private Object chat; // net.milkbowl.vault.chat.Chat instance, held reflectively

    public VaultChatBridge(@NotNull Logger logger) {
        this.logger = logger;
        this.available = detect();
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Sets this player's Vault chat prefix to the given legacy ({@code &}-coded) text -
     * the exact string {@code rank.PrefixService} already composes for chat/tab-list
     * rendering. No-op if Vault / a Chat provider isn't present. Best-effort: any failure
     * is logged at {@code FINE}, never thrown - a Vault push failing must never break
     * rank assignment itself.
     */
    public void setPrefix(@NotNull OfflinePlayer player, @NotNull String legacyPrefix) {
        if (!available) {
            return;
        }
        try {
            setPrefixModern(player, legacyPrefix);
        } catch (NoSuchMethodException modernMissing) {
            trySetPrefixLegacy(player, legacyPrefix);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.FINE, "Vault setPlayerPrefix failed for " + player.getName(), e);
        }
    }

    private void setPrefixModern(OfflinePlayer player, String prefix) throws ReflectiveOperationException {
        Method method = chat.getClass().getMethod("setPlayerPrefix", OfflinePlayer.class, String.class);
        method.invoke(chat, player, prefix);
    }

    private void trySetPrefixLegacy(OfflinePlayer player, String prefix) {
        try {
            // Older Vault Chat API: world-scoped, name-based. A null world applies
            // globally per Vault's own documented convention for that overload.
            Method method =
                    chat.getClass().getMethod("setPlayerPrefix", String.class, String.class, String.class);
            method.invoke(chat, (String) null, player.getName(), prefix);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.FINE, "Vault setPlayerPrefix (legacy overload) failed for " + player.getName(), e);
        }
    }

    private boolean detect() {
        try {
            Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat");
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(chatClass);
            if (provider == null) {
                logger.info(
                        "Vault present but no Chat provider registered - prefixes rely on RigelMCMod's own"
                                + " chat/tab-list rendering only.");
                return false;
            }
            this.chat = provider.getProvider();
            logger.info(
                    "Vault Chat provider detected (" + provider.getPlugin().getName()
                            + ") - pushing rank/title prefixes into it too.");
            return true;
        } catch (ClassNotFoundException e) {
            logger.info("Vault not found - prefixes rely on RigelMCMod's own chat/tab-list rendering only.");
            return false;
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Failed to detect Vault Chat provider", e);
            return false;
        }
    }
}
