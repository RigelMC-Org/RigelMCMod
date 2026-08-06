package org.rigelmc.vanish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VanishServiceTest {

    @Test
    void toggleFlipsStateAndReturnsTheNewState() {
        VanishService service = new VanishService();
        UUID uuid = UUID.randomUUID();

        assertFalse(service.isVanished(uuid));
        assertTrue(service.toggle(uuid)); // now vanished
        assertTrue(service.isVanished(uuid));
        assertFalse(service.toggle(uuid)); // now visible again
        assertFalse(service.isVanished(uuid));
    }

    @Test
    void clearRemovesVanishStateEvenIfNeverToggled() {
        VanishService service = new VanishService();
        UUID uuid = UUID.randomUUID();

        service.clear(uuid); // must not throw on a never-vanished player
        assertFalse(service.isVanished(uuid));

        service.toggle(uuid);
        service.clear(uuid);
        assertFalse(service.isVanished(uuid));
    }

    @Test
    void vanishedPlayersReflectsCurrentlyVanishedSet() {
        VanishService service = new VanishService();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        service.toggle(a);
        service.toggle(b);

        assertTrue(service.vanishedPlayers().contains(a));
        assertTrue(service.vanishedPlayers().contains(b));

        service.toggle(a); // un-vanish a
        assertFalse(service.vanishedPlayers().contains(a));
        assertTrue(service.vanishedPlayers().contains(b));
    }
}
