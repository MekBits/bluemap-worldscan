package dk.mekbits.bluemapworldscan.addon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScanPassTest {

    @Test
    void theSizeWarningNamesOnlySettingsThatShrinkTheLayers() {
        String message = ScanPass.oversizedLayersMessage("overworld", 9 << 20);

        assertTrue(message.contains("overworld"), message);
        assertTrue(message.contains("9 MB"), message);
        assertTrue(message.contains("structures.disabledLayers"), message);
        assertFalse(message.contains("biomes."), message);
    }
}
