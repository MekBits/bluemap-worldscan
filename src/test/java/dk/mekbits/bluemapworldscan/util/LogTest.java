package dk.mekbits.bluemapworldscan.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

class LogTest {

    private static final Logger ADDON_LOGGER = Logger.getLogger("dk.mekbits.bluemapworldscan");

    @Test
    void everyLevelCarriesThePrefixOnce() {
        Level before = ADDON_LOGGER.getLevel();
        ADDON_LOGGER.setLevel(Level.FINE);
        try (LogCapture log = new LogCapture()) {
            Log.debug("one");
            Log.info("two");
            Log.warn("three");

            assertEquals(
                    List.of("[bluemap-worldscan] one", "[bluemap-worldscan] two", "[bluemap-worldscan] three"),
                    log.messages(Level.FINE));
        } finally {
            ADDON_LOGGER.setLevel(before);
        }
    }

    @Test
    void debugIsOnlyEnabledWhenTheLoggerLetsFineThrough() {
        Level before = ADDON_LOGGER.getLevel();
        try {
            ADDON_LOGGER.setLevel(Level.INFO);
            assertFalse(Log.isDebugEnabled());
            ADDON_LOGGER.setLevel(Level.FINE);
            assertTrue(Log.isDebugEnabled());
        } finally {
            ADDON_LOGGER.setLevel(before);
        }
    }
}
