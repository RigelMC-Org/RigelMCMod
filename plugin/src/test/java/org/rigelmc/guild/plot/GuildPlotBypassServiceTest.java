package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuildPlotBypassServiceTest {

    @Test
    void toggleFlipsStateAndReturnsTheNewState() {
        GuildPlotBypassService service = new GuildPlotBypassService();
        UUID uuid = UUID.randomUUID();

        assertFalse(service.isBypassing(uuid));
        assertTrue(service.toggle(uuid)); // now bypassing
        assertTrue(service.isBypassing(uuid));
        assertFalse(service.toggle(uuid)); // now restricted again
        assertFalse(service.isBypassing(uuid));
    }

    @Test
    void clearRemovesBypassStateEvenIfNeverToggled() {
        GuildPlotBypassService service = new GuildPlotBypassService();
        UUID uuid = UUID.randomUUID();

        service.clear(uuid); // must not throw on a never-toggled player
        assertFalse(service.isBypassing(uuid));

        service.toggle(uuid);
        service.clear(uuid);
        assertFalse(service.isBypassing(uuid));
    }

    @Test
    void togglesAreIndependentPerPlayer() {
        GuildPlotBypassService service = new GuildPlotBypassService();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        service.toggle(a);

        assertTrue(service.isBypassing(a));
        assertFalse(service.isBypassing(b));
    }
}
