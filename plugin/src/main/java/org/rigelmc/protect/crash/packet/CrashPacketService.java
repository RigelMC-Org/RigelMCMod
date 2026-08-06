package org.rigelmc.protect.crash.packet;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Lifecycle for the packet-level ("E2") crash-exploit guard tier: polls for the
 * PacketEvents plugin a bounded number of times on enable (matching TFM's own
 * retry-then-give-up pattern in its real {@code CrashPacketService}), and registers
 * {@link CrashPacketListener} against PacketEvents' own already-initialized API singleton
 * if/when found. RigelMCMod never calls {@code PacketEvents.setAPI()}/{@code .load()}/
 * {@code .init()} itself - the standalone PacketEvents plugin (loaded {@code BEFORE} this
 * one, see {@code paper-plugin.yml}) already owns that full bootstrap; this only ever
 * reads the singleton it set up. The event-level ("E1", {@code protect.crash.*}) guards
 * keep working regardless of whether this tier ever manages to attach.
 */
public final class CrashPacketService {

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_INTERVAL_TICKS = 40L;

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private int attempts;

    public CrashPacketService(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    /** Call once from the owning module's {@code registerListeners}. */
    public void start() {
        if (!plugin.rigelConfig().crashPacketGuardEnabled()) {
            plugin.getLogger().info("[CrashPacketService] Packet-level crash guards disabled via config - skipping.");
            return;
        }
        attemptRegister();
    }

    private void attemptRegister() {
        attempts++;
        Plugin packetEvents = Bukkit.getPluginManager().getPlugin("packetevents");
        if (packetEvents == null) {
            packetEvents = Bukkit.getPluginManager().getPlugin("PacketEvents");
        }
        if (packetEvents != null && packetEvents.isEnabled()) {
            attach();
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            plugin.getLogger()
                    .info("[CrashPacketService] PacketEvents not present after waiting - packet-level crash"
                            + " guards and inbound rate limiting are disabled (event-level protections still"
                            + " work). Install PacketEvents to enable this tier.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::attemptRegister, RETRY_INTERVAL_TICKS);
    }

    private void attach() {
        try {
            PacketEvents.getAPI().getEventManager().registerListener(new CrashPacketListener(plugin, permissionGate));
            plugin.getLogger()
                    .info("[CrashPacketService] Attached to PacketEvents - packet-level crash guards active.");
        } catch (RuntimeException e) {
            plugin.getLogger()
                    .warning("[CrashPacketService] PacketEvents was found but attaching to it failed - packet-level"
                            + " crash guards and inbound rate limiting are disabled. Event-level protections"
                            + " still work. Error: " + e);
        }
    }
}
