package org.rigelmc.protect.crash.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.antigrief.PerPlayerRateLimiter;
import org.rigelmc.rank.PermissionGate;

/**
 * The packet-level ("E2") crash-exploit tier - catches abuse patterns that bypass normal
 * Bukkit events entirely, complementing {@code protect.crash}'s event-level ("E1") guards.
 * TFM ref: {@code blocking/packet/CrashPacketListener.java}, studied directly, but scoped
 * down deliberately for this first pass: chat/command packet-flood rate limiting and
 * movement-packet-rate anomaly detection (inbound), and outbound entity-metadata text-field
 * length capping.
 *
 * <p><b>Deliberately not ported in this pass</b>: TFM's own raw-NBT block-entity/chunk-data
 * tag stripping and outbound item-packet (window/slot/equipment) sanitization. Correctly
 * re-encoding those packets without a live server to test against risks corrupting
 * legitimate signs/chests/spawners for every player - a materially worse outcome than
 * simply not having that specific layer yet. {@code protect.crash}'s event-level guards
 * already cover items/signs/spawners at the point they're created, which per TFM's own
 * documented threat model covers the large majority of realistic exploit vectors; this is
 * flagged as a follow-up, not silently dropped.</p>
 */
public final class CrashPacketListener extends PacketListenerAbstract {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final PerPlayerRateLimiter<UUID> chatMessages = new PerPlayerRateLimiter<>(1_000);
    private final PerPlayerRateLimiter<UUID> commands = new PerPlayerRateLimiter<>(1_000);
    private final PerPlayerRateLimiter<UUID> movementPackets = new PerPlayerRateLimiter<>(1_000);

    public CrashPacketListener(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        if (!plugin.rigelConfig().crashPacketGuardEnabled()) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.CHAT_MESSAGE) {
            handleRateLimited(event, chatMessages, plugin.rigelConfig().crashPacketMaxChatPerSecond());
        } else if (event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND
                || event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED) {
            handleRateLimited(event, commands, plugin.rigelConfig().crashPacketMaxCommandsPerSecond());
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING) {
            handleRateLimited(event, movementPackets, plugin.rigelConfig().crashPacketMaxMovementPerSecond());
        }
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (!plugin.rigelConfig().crashPacketGuardEnabled()) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            sanitizeEntityMetadata(event);
        }
    }

    private boolean isExempt(@org.jetbrains.annotations.Nullable Player player) {
        return player == null || permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator");
    }

    private void handleRateLimited(PacketReceiveEvent event, PerPlayerRateLimiter<UUID> limiter, int maxPerSecond) {
        Player player = event.getPlayer();
        if (isExempt(player) || maxPerSecond <= 0) {
            return;
        }
        int count = limiter.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > maxPerSecond) {
            event.setCancelled(true);
        }
    }

    /**
     * Generic, type-based rather than exact-field-based: any text-valued (Adventure
     * {@link Component}, directly or wrapped in an {@link Optional}) entity-metadata entry
     * whose plain-text length exceeds the configured cap is cleared. Deliberately doesn't
     * try to match PacketEvents' exact per-field {@code EntityDataType} constants (a set
     * that can shift between its versions) - any oversized text-bearing metadata field is
     * worth capping regardless of which one it is. Reuses {@code protect.crash}'s own
     * {@code crashEntityMaxNameLength} config value rather than a separate one, matching
     * TFM's own approach of sharing this exact threshold between its event- and
     * packet-level entity-name guards.
     */
    @SuppressWarnings("unchecked")
    private void sanitizeEntityMetadata(PacketSendEvent event) {
        if (!plugin.rigelConfig().crashEntityGuardEnabled()) {
            return;
        }
        int maxLength = plugin.rigelConfig().crashEntityMaxNameLength();
        WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
        List<EntityData<?>> metadata = wrapper.getEntityMetadata();
        boolean changed = false;
        for (EntityData<?> data : metadata) {
            Object value = data.getValue();
            if (value instanceof Optional<?> optional
                    && optional.isPresent()
                    && optional.get() instanceof Component component
                    && plainTextLength(component) > maxLength) {
                ((EntityData<Optional<Component>>) data).setValue(Optional.empty());
                changed = true;
            } else if (value instanceof Component component && plainTextLength(component) > maxLength) {
                ((EntityData<Component>) data).setValue(Component.empty());
                changed = true;
            }
        }
        if (changed) {
            wrapper.setEntityMetadata(metadata);
            event.markForReEncode(true);
        }
    }

    private static int plainTextLength(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component).length();
    }
}
