package org.rigelmc.disguise;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * Forbidden disguise-type list plus the global disguises-enabled toggle - TFM ref: {@code
 * disguise.DisallowedDisguises}, studied directly. Ported with one real fix: TFM defines
 * this exact forbidden-type check ({@code isAllowed(String)}) and even exposes it publicly
 * through {@code LibsDisguisesBridge#isDisguiseAllowed}, but a full grep of TFM's own
 * codebase turns up <b>zero callers</b> of either method anywhere - the config option does
 * nothing, and any player can disguise as any type LibsDisguises supports, including the
 * ones TFM's own default list claims to block (a live wither/ender-dragon disguise, for
 * instance). {@link DisguiseAbuseGuard} is what actually wires this up.
 *
 * <p>Deliberately config-loaded for the forbidden-type set (reloadable) but purely
 * in-memory for the enabled/disabled flag (resets to enabled on every restart) - matching
 * TFM's own real split exactly: its {@code disabled} field has no config backing at all,
 * only the forbidden-type set does.</p>
 *
 * <p>Depends on {@link RigelConfig} directly (not the full {@code RigelMCMod} plugin
 * instance) purely so this stays trivially unit-testable with a plain {@code
 * YamlConfiguration} - same rationale as {@link RigelConfig}'s own "keeps config parsing
 * trivially unit-testable" design note.</p>
 */
public final class DisallowedDisguises {

    /** TFM's own real default list, used only if {@code fun.disguise.forbidden-types} is empty. */
    private static final List<String> DEFAULT_FORBIDDEN = List.of(
            "FISHING_HOOK", "ITEM_FRAME", "ENDER_DRAGON", "PLAYER", "GIANT", "GHAST", "MAGMA_CUBE", "SLIME",
            "DROPPED_ITEM", "ENDER_CRYSTAL", "AREA_EFFECT_CLOUD", "WITHER");

    private RigelConfig config;
    private volatile Set<String> forbidden;
    private volatile boolean disabled;

    public DisallowedDisguises(@NotNull RigelConfig config) {
        this.config = config;
        reload();
    }

    /** Reloads the forbidden-type set from config - call after {@code /rmcm reload}. */
    public void reload() {
        List<String> configured = config.disguiseForbiddenTypes();
        Set<String> normalized = new HashSet<>();
        for (String type : configured.isEmpty() ? DEFAULT_FORBIDDEN : configured) {
            normalized.add(type.toUpperCase(Locale.ROOT));
        }
        this.forbidden = normalized;
    }

    /** @param config the freshly-rebuilt configuration, used for every {@link #reload} from now on */
    public void reload(@NotNull RigelConfig config) {
        this.config = config;
        reload();
    }

    /** @return {@code true} unless {@code disguiseTypeName} (case-insensitive) is on the forbidden list. */
    public boolean isAllowed(@NotNull String disguiseTypeName) {
        return !forbidden.contains(disguiseTypeName.toUpperCase(Locale.ROOT));
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }
}
