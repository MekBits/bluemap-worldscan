package dk.mekbits.bluemapworldscan.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddonFolderTest {

    @TempDir
    Path folder;

    @Test
    void theFolderOfAJarInPacksIsItsSiblingBesidePacks() {
        assertEquals(Path.of("/x/bluemap-worldscan").toAbsolutePath(),
                AddonFolder.fromJar(Path.of("/x/packs/a.jar")).toAbsolutePath());
    }

    @Test
    void theScannerIdIsCreatedOnceAndThenKept() throws IOException {
        String first = AddonFolder.scannerId(folder);

        assertEquals(first, AddonFolder.scannerId(folder));
        assertEquals(36, first.length(), "a UUID");
        assertNotEquals(first, AddonFolder.scannerId(Files.createDirectory(folder.resolve("other"))));
    }

    @Test
    void theStateFileNameIsTheFirst16HexDigitsOfTheSha1OfTheWorldId() {
        // sha1("world") = 7c211433f02071597741e6ff5a8ea34789abbf43
        assertEquals(folder.resolve("state").resolve("7c211433f0207159.nbt.gz"), AddonFolder.stateFile(folder, "world"));
    }

    @Test
    void withoutAResetFileNothingIsDeleted() throws IOException {
        Path state = Files.createDirectories(folder.resolve("state")).resolve("a.nbt.gz");
        Files.writeString(state, "x");

        assertFalse(AddonFolder.consumeReset(folder));
        assertTrue(Files.exists(state));
    }

    @Test
    void aResetFileDeletesTheStatesAndItself() throws IOException {
        Path states = Files.createDirectories(folder.resolve("state"));
        Files.writeString(states.resolve("a.nbt.gz"), "x");
        Files.writeString(states.resolve("b.nbt.gz.tmp"), "x");
        Files.writeString(folder.resolve("config.json"), "{}");
        Files.createFile(folder.resolve("reset"));

        assertTrue(AddonFolder.consumeReset(folder));

        assertFalse(Files.exists(folder.resolve("reset")));
        assertFalse(Files.exists(states.resolve("a.nbt.gz")));
        assertFalse(Files.exists(states.resolve("b.nbt.gz.tmp")));
        assertTrue(Files.exists(folder.resolve("config.json")), "other files are left alone");
    }

    @Test
    void aResetWithoutAStateFolderStillConsumesTheFile() throws IOException {
        Files.createFile(folder.resolve("reset"));

        assertTrue(AddonFolder.consumeReset(folder));
        assertFalse(Files.exists(folder.resolve("reset")));
    }
}
