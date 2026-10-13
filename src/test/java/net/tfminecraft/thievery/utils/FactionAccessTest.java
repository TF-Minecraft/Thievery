package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.tlibs.character.*;
import org.junit.jupiter.api.Test;

class FactionAccessTest {
    @Test void theftCooldownsNeedFactionsAndReviewedHeirs() {
        try(var api=mockStatic(SimpleFactionsApi.class); var chars=mockStatic(Characters.class)) {
            assertFalse(FactionAccess.ready());
            api.when(SimpleFactionsApi::ready).thenReturn(true); assertFalse(FactionAccess.ready());
            chars.when(Characters::legacyHeirsReady).thenReturn(true); assertTrue(FactionAccess.ready());
        }
    }
}
