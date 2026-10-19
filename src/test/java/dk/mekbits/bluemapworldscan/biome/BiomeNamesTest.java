package dk.mekbits.bluemapworldscan.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dk.mekbits.bluemapworldscan.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BiomeNamesTest {

    @TempDir
    Path temp;

    private Path jar(String langJson) throws IOException {
        Path jar = temp.resolve("client.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(BiomeNames.LANG_FILE));
            zip.write(langJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    void aBiomeIsNamedByTheLanguageFileOfTheClientJar() throws IOException {
        BiomeNames names = BiomeNames.fromClientJar(jar("""
                {"biome.minecraft.old_growth_pine_taiga": "Old Growth Pine Taiga",
                 "biome.some_pack.crystal.caves": "Crystal Caves", "block.minecraft.stone": "Stone"}
                """));

        assertEquals("Old Growth Pine Taiga", names.label("minecraft:old_growth_pine_taiga"));
        assertEquals("Crystal Caves", names.label("some_pack:crystal/caves"), "a slash in the path is a dot in the key");
    }

    @Test
    void aBiomeTheLanguageFileDoesNotNameIsNamedFromItsId() throws IOException {
        BiomeNames names = BiomeNames.fromClientJar(jar("{\"biome.minecraft.plains\": \"Plains\"}"));

        assertEquals("Big tower (some_pack)", names.label("some_pack:big_tower"));
        assertEquals("Sulfur caves", names.label("minecraft:sulfur_caves"));
    }

    @Test
    void withoutAClientJarEveryBiomeIsNamedFromItsId() {
        BiomeNames names = BiomeNames.fromClientJar(null);

        assertEquals("Old growth pine taiga", names.label("minecraft:old_growth_pine_taiga"));
        assertEquals("Plains", names.label("minecraft:plains"));
        assertEquals("Crystal caves (some_pack)", names.label("some_pack:crystal_caves"));
    }

    @Test
    void aJarThatCannotBeReadOrHasNoLanguageFileGivesNamesFromIdsAndALogLine() throws IOException {
        Path broken = Files.writeString(temp.resolve("broken.jar"), "not a zip");
        Path noLang = temp.resolve("empty.jar");
        try (OutputStream file = Files.newOutputStream(noLang); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("other.txt"));
            zip.closeEntry();
        }

        try (LogCapture log = new LogCapture()) {
            assertEquals("Plains", BiomeNames.fromClientJar(broken).label("minecraft:plains"));
            assertEquals("Plains", BiomeNames.fromClientJar(noLang).label("minecraft:plains"));
            assertEquals(2, log.warnings().size(), log.warnings().toString());
        }
    }

    @Test
    void aLanguageFileThatIsNotJsonObjectGivesNamesFromIds() throws IOException {
        try (LogCapture log = new LogCapture()) {
            assertEquals("Plains", BiomeNames.fromClientJar(jar("[1, 2]")).label("minecraft:plains"));
            assertEquals(1, log.warnings().size());
        }
    }
}
