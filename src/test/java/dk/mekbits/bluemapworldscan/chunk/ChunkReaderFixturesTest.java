package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dk.mekbits.bluemapworldscan.chunk.ChunkInfo.Kind;
import dk.mekbits.bluemapworldscan.testsupport.ChunkFixtures;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

/** {@link ChunkReader} on the reduced real chunks of the fixture world, one test per format branch. */
class ChunkReaderFixturesTest {

    private static final Pattern FIXTURE_NAME = Pattern.compile("^(.+)-dv(\\d+)(?:-cave)?-c\\.(-?\\d+)\\.(-?\\d+)$");

    private static ChunkInfo read(String fixture) throws IOException {
        return ChunkReader.read(new ByteArrayInputStream(ChunkFixtures.bytes(fixture)));
    }

    private static List<String> structureIds(ChunkInfo chunk) {
        return chunk.structures().stream().map(FoundStructure::structureId).toList();
    }

    @Test
    void anEndCityOfDataVersion1976HasItsShipBoxAndItsEndHighlands() throws IOException {
        String fixture = "the_end-1.13-1.14-ship-dv1976-c.322.-158";

        ChunkInfo chunk = read(fixture);

        assertEquals(Kind.MODERN, chunk.kind());
        assertEquals(1976, chunk.dataVersion());
        assertEquals(322, chunk.chunkX());
        assertEquals(-158, chunk.chunkZ());
        assertEquals(List.of("minecraft:end_city"), structureIds(chunk));
        assertEquals(Set.of("minecraft:end_highlands"), chunk.biomes());
        FoundStructure city = chunk.structures().getFirst();
        assertArrayEquals(shipBoxOf(fixture, "Level", "Structures", "Starts", "EndCity"), city.endShipBox());
        assertFalse(Arrays.equals(city.box(), city.endShipBox()), "the city's box spans more than its ship");
    }

    @Test
    void aProtoChunkOfDataVersion1976KeepsItsEndCityButHasNoBiomes() throws IOException {
        ChunkInfo chunk = read("the_end-1.13-1.14-proto-dv1976-c.361.-156");

        assertEquals(List.of("minecraft:end_city"), structureIds(chunk));
        assertEquals(Set.of(), chunk.biomes());
    }

    @Test
    void aChunkWithStatusBiomesOfDataVersion1976HasItsBiomes() throws IOException {
        ChunkInfo chunk = read("the_end-1.13-1.14-protobio-dv1976-c.28.135");

        assertEquals(Set.of("minecraft:end_highlands"), chunk.biomes());
    }

    @Test
    void numericNetherBiomesOfDataVersion2567AreNamed() throws IOException {
        ChunkInfo chunk = read("the_nether-1.15-1.17-biome-dv2567-c.45.-71");

        assertEquals(Set.of("minecraft:nether_wastes", "minecraft:crimson_forest", "minecraft:warped_forest"), chunk.biomes());
        assertEquals(Set.of(), chunk.unknownBiomeIds());
    }

    @Test
    void aChunkAtStatusStructureStartsHasNoBiomesEvenWithABiomeArray() throws IOException {
        ChunkInfo chunk = read("the_end-1.15-1.17-protobio-dv2724-c.117.-328");

        assertEquals(Set.of(), chunk.biomes());
    }

    @Test
    void aNetherChunkWithTwoStartsGivesBothInKeyOrder() throws IOException {
        ChunkInfo chunk = read("the_nether-1.15-1.17-struct-bastion_remnant-dv2724-c.-8.344");

        assertEquals(List.of("minecraft:bastion_remnant", "minecraft:nether_fossil"), structureIds(chunk));
    }

    @Test
    void aVillageOfDataVersion2865ThroughItsInvalidPlaceholdersIsOneVillagePlains() throws IOException {
        String fixture = "overworld-1.18-1.18.1-struct-village-dv2865-c.-61.-420";
        CompoundBinaryTag starts = ChunkFixtures.tree(fixture).getCompound("structures").getCompound("starts");
        assertEquals(18, starts.keySet().size(), "the fixture holds the village and 17 INVALID placeholders");

        ChunkInfo chunk = read(fixture);

        assertEquals(List.of("minecraft:village_plains"), structureIds(chunk));
        assertEquals(Set.of("minecraft:plains"), chunk.biomes());
    }

    @Test
    void aFortressInAProtoChunkOfDataVersion2865IsKeptWithoutBiomes() throws IOException {
        ChunkInfo chunk = read("the_nether-1.18-1.18.1-struct-fortress-dv2865-c.-47.-206");

        assertEquals(List.of("minecraft:fortress"), structureIds(chunk));
        assertEquals(Set.of(), chunk.biomes());
    }

    @Test
    void caveBiomesOfAChunkWithBareStatusFullAreRead() throws IOException {
        ChunkInfo chunk = read("overworld-1.18.2plus-biome-dv3117-cave-c.-429.-248");

        assertEquals(Set.of("minecraft:birch_forest", "minecraft:deep_dark", "minecraft:dripstone_caves",
                "minecraft:forest", "minecraft:meadow", "minecraft:plains"), chunk.biomes());
    }

    @Test
    void biomesOfAChunkWithPrefixedStatusFullAreRead() throws IOException {
        ChunkInfo chunk = read("overworld-1.18.2plus-biome-dv3465-cave-c.-232.408");

        assertEquals(Set.of("minecraft:beach", "minecraft:deep_lukewarm_ocean", "minecraft:dripstone_caves",
                "minecraft:forest", "minecraft:grove", "minecraft:lukewarm_ocean", "minecraft:meadow"), chunk.biomes());
    }

    @Test
    void aMonumentInAProtoChunkIsKeptWithoutBiomes() throws IOException {
        ChunkInfo chunk = read("overworld-1.18.2plus-proto-dv4903-c.-730.943");

        assertEquals(List.of("minecraft:monument"), structureIds(chunk));
        assertEquals(Set.of(), chunk.biomes());
    }

    @Test
    void aChunkAtStatusBiomesHasItsBiomes() throws IOException {
        ChunkInfo chunk = read("overworld-1.18.2plus-protobio-dv4903-c.346.941");

        assertEquals(Set.of("minecraft:deep_dark", "minecraft:forest"), chunk.biomes());
    }

    @Test
    void aChunkAtStatusInitializeLightHasItsBiomes() throws IOException {
        ChunkInfo chunk = read("the_nether-1.18.2plus-proto-dv4790-c.-64.348");

        assertEquals(Set.of("minecraft:nether_wastes"), chunk.biomes());
    }

    @Test
    void anEndCityShipOfDataVersion3117HasAShipBox() throws IOException {
        String fixture = "the_end-1.18.2plus-ship-dv3117-c.-678.5";

        ChunkInfo chunk = read(fixture);

        assertEquals(List.of("minecraft:end_city"), structureIds(chunk));
        assertArrayEquals(shipBoxOf(fixture, "structures", "starts", "minecraft:end_city"), chunk.structures().getFirst().endShipBox());
    }

    @Test
    void aMonumentBoxIsTheUnionOfItsPieces() throws IOException {
        String fixture = "overworld-1.18.2plus-struct-monument-dv4903-c.-440.-27";
        ListBinaryTag pieces = ChunkFixtures.tree(fixture).getCompound("structures").getCompound("starts")
                .getCompound("minecraft:monument").getList("Children", BinaryTagTypes.COMPOUND);
        int[] expected = pieces.getCompound(0).getIntArray("BB");
        for (int i = 1; i < pieces.size(); i++) {
            int[] bb = pieces.getCompound(i).getIntArray("BB");
            for (int axis = 0; axis < 3; axis++) {
                expected[axis] = Math.min(expected[axis], bb[axis]);
                expected[axis + 3] = Math.max(expected[axis + 3], bb[axis + 3]);
            }
        }

        FoundStructure monument = read(fixture).structures().getFirst();

        assertArrayEquals(expected, monument.box());
        assertNull(monument.endShipBox());
    }

    /** Every fixture reads, sits where its file name says, and holds ids the tables know. */
    @Test
    void everyFixtureReadsAtItsNamedPositionWithKnownIds() throws IOException {
        List<String> names = ChunkFixtures.names();
        assertEquals(209, names.size());

        for (String name : names) {
            Matcher matcher = FIXTURE_NAME.matcher(name);
            assertTrue(matcher.matches(), name);
            ChunkInfo chunk = read(name);

            assertEquals(Kind.MODERN, chunk.kind(), name);
            assertEquals(Integer.parseInt(matcher.group(2)), chunk.dataVersion(), name);
            assertEquals(Integer.parseInt(matcher.group(3)), chunk.chunkX(), name);
            assertEquals(Integer.parseInt(matcher.group(4)), chunk.chunkZ(), name);
            assertEquals(Set.of(), chunk.unknownStructureIds(), name);
            assertEquals(Set.of(), chunk.unknownBiomeIds(), name);

            String what = matcher.group(1);
            if (what.contains("-struct-")) {
                assertFalse(chunk.structures().isEmpty(), name);
                if (chunk.dataVersion() >= 2970) {
                    String structure = what.substring(what.indexOf("-struct-") + "-struct-".length());
                    assertTrue(structureIds(chunk).contains("minecraft:" + structure), name);
                }
            }
            if (what.endsWith("-ship")) {
                FoundStructure city = chunk.structures().stream()
                        .filter(structure -> structure.structureId().equals(StructureIds.END_CITY))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no end city in " + name));
                assertNotNull(city.endShipBox(), name);
            }
            if (what.contains("-biome")) {
                assertFalse(chunk.biomes().isEmpty(), name);
            }
        }
    }

    /** The box of the {@code ship} piece of the end city at the given path, read without the code under test. */
    private static int[] shipBoxOf(String fixture, String... path) {
        CompoundBinaryTag tag = ChunkFixtures.tree(fixture);
        for (int i = 0; i < path.length - 1; i++) {
            tag = tag.getCompound(path[i]);
        }
        ListBinaryTag pieces = tag.getCompound(path[path.length - 1]).getList("Children", BinaryTagTypes.COMPOUND);
        for (int i = 0; i < pieces.size(); i++) {
            if ("ship".equals(pieces.getCompound(i).getString("Template", null))) {
                return pieces.getCompound(i).getIntArray("BB");
            }
        }
        throw new AssertionError("no ship piece in " + fixture);
    }
}
