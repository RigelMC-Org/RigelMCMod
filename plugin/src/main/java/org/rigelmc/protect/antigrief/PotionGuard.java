package org.rigelmc.protect.antigrief;

import java.util.Collection;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Blocks a thrown splash potion carrying a lethal instant-damage effect (or an excessive
 * effect count, a cheap proxy for a maliciously stacked potion) from a non-staff thrower -
 * TFM ref: PotionBlocker.java. Deliberately narrow: only the specific "can outright kill
 * someone" case is blocked, not splash potions in general (buffs, slowness, etc. are all
 * still normal, expected PvP/utility tools on a Free-OP server). Reads {@code
 * plugin.rigelConfig()} fresh on every event - see {@link AntiNukeGuard}'s javadoc for why.
 */
public final class PotionGuard implements Listener {

    private static final int MAX_NORMAL_EFFECT_COUNT = 4;

    private final RigelMCMod plugin;

    public PotionGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(@NotNull PotionSplashEvent event) {
        if (!plugin.rigelConfig().potionGuardEnabled() || !plugin.rigelConfig().potionGuardBlockLethal()) {
            return;
        }
        ThrownPotion potion = event.getPotion();
        if (potion.getShooter() instanceof Player) {
            return; // only the auto-op'd masses need this guard against each other, not staff PvP tools
        }

        Collection<PotionEffect> effects = potion.getEffects();
        if (effects.size() > MAX_NORMAL_EFFECT_COUNT || containsLethalEffect(effects)) {
            event.setCancelled(true);
        }
    }

    private static boolean containsLethalEffect(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().equals(PotionEffectType.INSTANT_DAMAGE) && effect.getAmplifier() >= 1) {
                return true;
            }
        }
        return false;
    }
}
