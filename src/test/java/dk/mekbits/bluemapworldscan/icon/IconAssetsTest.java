package dk.mekbits.bluemapworldscan.icon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import dk.mekbits.bluemapworldscan.testsupport.InMemoryAssetStorage;
import dk.mekbits.bluemapworldscan.util.Sha1;
import org.junit.jupiter.api.Test;

class IconAssetsTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private final InMemoryAssetStorage storage = new InMemoryAssetStorage("overworld");

    @Test
    void theAssetNameHoldsTheFirst12HexDigitsOfTheSha1OfThePng() {
        String name = IconAssets.assetName("village", PNG);

        assertEquals("bluemap-worldscan-icon-village-" + Sha1.hex(PNG).substring(0, 12) + ".png", name);
        assertNotEquals(name, IconAssets.assetName("village", new byte[] {1}));
    }

    @Test
    void anIconIsWrittenAndItsUrlNamesTheMap() throws IOException {
        Map<String, String> urls = IconAssets.writeAll(storage, Map.of("village", PNG));

        String name = IconAssets.assetName("village", PNG);
        assertEquals(Map.of("village", "maps/overworld/assets/" + name), urls);
        assertArrayEquals(PNG, storage.bytes(name));
    }

    @Test
    void thePngTwiceIsOneAssetWrittenOnce() throws IOException {
        IconAssets.writeAll(storage, Map.of("village", PNG));
        Map<String, String> again = IconAssets.writeAll(storage, Map.of("village", PNG));

        assertEquals(1, storage.completedWrites().size());
        assertEquals(1, storage.names().size());
        assertTrue(again.get("village").endsWith(IconAssets.assetName("village", PNG)));
    }

    @Test
    void aChangedPictureGetsANewAsset() throws IOException {
        IconAssets.writeAll(storage, Map.of("village", PNG));

        IconAssets.writeAll(storage, Map.of("village", new byte[] {9, 9}));

        assertEquals(2, storage.names().size());
    }
}
