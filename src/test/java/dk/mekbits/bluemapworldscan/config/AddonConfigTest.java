package dk.mekbits.bluemapworldscan.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddonConfigTest {

    @TempDir
    Path folder;

    private void write(String json) throws IOException {
        Files.writeString(folder.resolve("config.json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void aMissingFileGivesTheDefaultsAndIsWritten() throws IOException {
        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertEquals(AddonConfig.defaults(), config);
        assertTrue(config.scan());
        assertEquals(500, config.maxChunksPerSecond());
        assertEquals(5, config.refreshMinutes());
        assertEquals(List.of("mineshaft", "buried_treasure"), config.structures().disabledLayers());
        assertTrue(config.biomes().enabled());
        assertEquals(16, config.biomes().cellSizeChunks());
        assertEquals(List.of(), config.biomes().disabled());
        assertEquals(config, AddonConfig.loadOrCreate(folder), "the written file reads back as the same settings");
        assertTrue(Files.readString(folder.resolve("config.json")).contains("\"disabledLayers\""));
    }

    @Test
    void aFileWithOnlyScanKeepsTheOtherDefaults() throws IOException {
        write("{\"scan\": false}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertFalse(config.scan());
        assertEquals(AddonConfig.defaults().maxChunksPerSecond(), config.maxChunksPerSecond());
        assertEquals(AddonConfig.defaults().structures(), config.structures());
        assertEquals(AddonConfig.defaults().biomes(), config.biomes());
    }

    @Test
    void aNestedKeyOverridesOnlyThatKey() throws IOException {
        write("{\"biomes\": {\"cellSizeChunks\": 8}, \"structures\": {\"disabledLayers\": []}}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertEquals(8, config.biomes().cellSizeChunks());
        assertTrue(config.biomes().enabled());
        assertEquals(List.of(), config.structures().disabledLayers());
        assertTrue(config.scan());
    }

    @Test
    void aMalformedFileGivesTheDefaultsWithScanOff() throws IOException {
        write("{\"scan\": tru");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertFalse(config.scan());
        assertEquals(AddonConfig.defaults().structures(), config.structures());
        assertEquals("{\"scan\": tru", Files.readString(folder.resolve("config.json")), "the file is left for the user to fix");
    }

    @Test
    void aValueOfTheWrongTypeGivesTheDefaultsWithScanOff() throws IOException {
        write("{\"maxChunksPerSecond\": \"fast\"}");

        assertFalse(AddonConfig.loadOrCreate(folder).scan());
    }

    @Test
    void aCapOfZeroIsNoCapAndThreadsAreRead() throws IOException {
        write("{\"maxChunksPerSecond\": 0, \"scanThreads\": 4}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertTrue(config.scan());
        assertEquals(0, config.maxChunksPerSecond());
        assertEquals(4, config.scanThreads());
    }

    @Test
    void oneScanThreadIsTheDefault() throws IOException {
        write("{}");

        assertEquals(1, AddonConfig.loadOrCreate(folder).scanThreads());
    }

    @Test
    void noScanThreadsGivesTheDefaultsWithScanOff() throws IOException {
        write("{\"scanThreads\": 0}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertFalse(config.scan());
        assertEquals(1, config.scanThreads());
    }

    @Test
    void anImpossibleValueGivesTheDefaultsWithScanOff() throws IOException {
        write("{\"maxChunksPerSecond\": -1}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertFalse(config.scan());
        assertEquals(500, config.maxChunksPerSecond());
    }

    @Test
    void aFileThatIsNotAnObjectGivesTheDefaultsWithScanOff() throws IOException {
        write("[1, 2]");

        assertFalse(AddonConfig.loadOrCreate(folder).scan());
    }

    @Test
    void aCellSizeAsWideAsTheWorldBorderIsAccepted() throws IOException {
        write("{\"biomes\": {\"cellSizeChunks\": 1875000}}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertTrue(config.scan());
        assertEquals(1_875_000, config.biomes().cellSizeChunks());
    }

    @Test
    void aCellSizeWiderThanTheWorldBorderGivesTheDefaultsWithScanOff() throws IOException {
        write("{\"biomes\": {\"cellSizeChunks\": 1875001}}");

        AddonConfig config = AddonConfig.loadOrCreate(folder);

        assertFalse(config.scan());
        assertEquals(AddonConfig.defaults().biomes(), config.biomes());
    }
}
