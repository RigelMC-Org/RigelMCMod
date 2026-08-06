package org.rigelmc.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImpersonationGuardTest {

    @Test
    void detectsBracketedReservedTerms() {
        assertTrue(ImpersonationGuard.looksLikeImpersonation("[Owner]"));
        assertTrue(ImpersonationGuard.looksLikeImpersonation("&c[Admin]&r"));
        assertTrue(ImpersonationGuard.looksLikeImpersonation("[Senior Admin]"));
        assertTrue(ImpersonationGuard.looksLikeImpersonation("[SrA]")); // Rank.SENIOR_ADMIN's actual bracket text
        assertTrue(ImpersonationGuard.looksLikeImpersonation("[Mod]"));
        assertTrue(ImpersonationGuard.looksLikeImpersonation("[Console]"));
    }

    @Test
    void allowsOrdinaryTextIncludingSubstringMatches() {
        assertFalse(ImpersonationGuard.looksLikeImpersonation("Builder"));
        assertFalse(ImpersonationGuard.looksLikeImpersonation("[Admiral]")); // contains "admi" but not the term
        assertFalse(ImpersonationGuard.looksLikeImpersonation("CoolGuy99"));
        assertFalse(ImpersonationGuard.looksLikeImpersonation("&b[Builder]&r"));
    }
}
