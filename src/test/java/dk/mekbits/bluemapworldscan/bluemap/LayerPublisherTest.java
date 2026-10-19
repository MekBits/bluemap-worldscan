package dk.mekbits.bluemapworldscan.bluemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Shape;
import dk.mekbits.bluemapworldscan.biome.BiomeNames;
import dk.mekbits.bluemapworldscan.config.AddonConfig;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Area;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryBlueMapMap;
import dk.mekbits.bluemapworldscan.testsupport.TestStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayerPublisherTest {

    private static final String SCRIPT = "<img src=x onerror=1>";
    private static final String ESCAPED_SCRIPT = "&lt;img src=x onerror=1&gt;";

    @TempDir
    Path temp;

    private final LayerPublisher publisher = new LayerPublisher();

    private static LayerSet set(String id, boolean hidden, int sorting, Poi... pois) {
        return new LayerSet(id, "Label of " + id, hidden, sorting, List.of(pois));
    }

    private static Poi poi(int x, int z, String icon) {
        return new Poi(x + "_70_" + z, "Village", x, 70, z, "<div>" + x + "</div>", icon, 11, 11, 5000);
    }

    private static LayersFile layers(LayerSet... sets) {
        return new LayersFile(LayersFile.FORMAT_VERSION, List.of(sets));
    }

    /** A square of 64 blocks with a square hole of 16 blocks, the fill 0x73336699. */
    private static Area areaWithAHole(String id) {
        return new Area(id, "Plains", 63f,
                List.of(new int[] {0, 0}, new int[] {0, 64}, new int[] {64, 64}, new int[] {64, 0}),
                List.of(List.of(new int[] {16, 16}, new int[] {32, 16}, new int[] {32, 32}, new int[] {16, 32})),
                0x73336699);
    }

    private static LayerSet biomeSet(String id, Area... areas) {
        return new LayerSet(id, "Biome: Plains", true, 5, List.of(), List.of(areas));
    }

    private static List<Vector2d> points(Shape shape) {
        return new ArrayList<>(List.of(shape.getPoints()));
    }

    @Test
    void aLayerBecomesAToggleableMarkerSetWithItsPoiMarkers() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");

        publisher.publish(map, layers(set("village", true, 3, poi(4, 8, "maps/overworld/assets/v.png"), poi(100, -5, null))));

        MarkerSet set = map.getMarkerSets().get("village");
        assertEquals("Label of village", set.getLabel());
        assertTrue(set.isToggleable());
        assertTrue(set.isDefaultHidden());
        assertEquals(3, set.getSorting());
        assertEquals(2, set.getMarkers().size());

        POIMarker marker = (POIMarker) set.get("4_70_8");
        assertEquals("Village", marker.getLabel());
        assertEquals("<div>4</div>", marker.getDetail());
        assertEquals(4.5, marker.getPosition().getX());
        assertEquals(70.0, marker.getPosition().getY());
        assertEquals(8.5, marker.getPosition().getZ());
        assertEquals(5000.0, marker.getMaxDistance());
        assertEquals("maps/overworld/assets/v.png", marker.getIconAddress());
        assertEquals(11, marker.getAnchor().getX());
        assertEquals(11, marker.getAnchor().getY());
    }

    @Test
    void aMarkerWithoutAnIconKeepsBlueMapsDefaultIcon() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");

        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null))));

        POIMarker marker = (POIMarker) map.getMarkerSets().get("village").get("4_70_8");
        assertEquals(new POIMarker("x", marker.getPosition()).getIconAddress(), marker.getIconAddress());
    }

    @Test
    void oneLayersFileGivesEqualSetsOnTwoMaps() {
        InMemoryBlueMapMap first = new InMemoryBlueMapMap("first");
        InMemoryBlueMapMap second = new InMemoryBlueMapMap("second");
        LayersFile layers = layers(
                set("village", false, 0, poi(4, 8, "maps/first/assets/v.png")),
                set("igloo", true, 1, poi(9, 9, null)));

        publisher.publish(first, layers);
        new LayerPublisher().publish(second, layers);

        assertEquals(first.getMarkerSets(), second.getMarkerSets());
        assertFalse(first.getMarkerSets().isEmpty());
    }

    @Test
    void anAreaWithAHoleBecomesAShapeMarkerWithOneHoleThatIsDrawnThroughTheTerrain() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");

        publisher.publish(map, layers(biomeSet("biome", areaWithAHole("0"))));

        MarkerSet set = map.getMarkerSets().get("biome");
        assertTrue(set.isDefaultHidden());
        assertEquals("Biome: Plains", set.getLabel());
        ShapeMarker marker = (ShapeMarker) set.get("0");
        assertEquals("Plains", marker.getLabel());
        assertEquals(63f, marker.getShapeY());
        assertEquals(List.of(new Vector2d(0, 0), new Vector2d(0, 64), new Vector2d(64, 64), new Vector2d(64, 0)), points(marker.getShape()));
        assertEquals(1, marker.getHoles().size());
        assertEquals(List.of(new Vector2d(16, 16), new Vector2d(32, 16), new Vector2d(32, 32), new Vector2d(16, 32)),
                points(marker.getHoles().iterator().next()));
        assertFalse(marker.isDepthTestEnabled());
        assertFalse(marker.isListed(), "hundreds of areas of one biome would fill the sidebar");
    }

    @Test
    void theOutlineHasTheColourOfTheFillAtFullOpacityNotTheApisRed() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");

        publisher.publish(map, layers(biomeSet("biome", areaWithAHole("0"))));

        ShapeMarker marker = (ShapeMarker) map.getMarkerSets().get("biome").get("0");
        assertEquals(0x33, marker.getFillColor().getRed());
        assertEquals(0x66, marker.getFillColor().getGreen());
        assertEquals(0x99, marker.getFillColor().getBlue());
        assertEquals(0.45f, marker.getFillColor().getAlpha());
        assertEquals(0x33, marker.getLineColor().getRed());
        assertEquals(0x66, marker.getLineColor().getGreen());
        assertEquals(0x99, marker.getLineColor().getBlue());
        assertEquals(1f, marker.getLineColor().getAlpha());
        assertEquals(1, marker.getLineWidth());
    }

    @Test
    void oneLayersFileWithAreasGivesEqualShapeMarkersOnTwoMaps() {
        InMemoryBlueMapMap first = new InMemoryBlueMapMap("first");
        InMemoryBlueMapMap second = new InMemoryBlueMapMap("second");
        LayersFile layers = layers(
                set("village", false, 0, poi(4, 8, "maps/first/assets/v.png")),
                biomeSet("biome", areaWithAHole("0"), areaWithAHole("1")));

        publisher.publish(first, layers);
        new LayerPublisher().publish(second, layers);

        assertEquals(first.getMarkerSets(), second.getMarkerSets());
        ShapeMarker a = (ShapeMarker) first.getMarkerSets().get("biome").get("1");
        ShapeMarker b = (ShapeMarker) second.getMarkerSets().get("biome").get("1");
        assertEquals(a, b);
        assertEquals(points(a.getHoles().iterator().next()), points(b.getHoles().iterator().next()));
        assertEquals(a.getLineColor(), b.getLineColor());
        assertEquals(a.getFillColor(), b.getFillColor());
    }

    @Test
    void aSetWithBothPointsAndAreasKeepsBoth() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        LayerSet both = new LayerSet("both", "Both", false, 0, List.of(poi(4, 8, null)), List.of(areaWithAHole("0")));

        publisher.publish(map, layers(both));

        assertEquals(Set.of("4_70_8", "0"), map.getMarkerSets().get("both").getMarkers().keySet());
    }

    @Test
    void publishingAgainWithoutABiomeSetRemovesIt() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null)), biomeSet("biome", areaWithAHole("0"))));

        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null))));

        assertEquals(Set.of("village"), map.getMarkerSets().keySet());
    }

    @Test
    void publishingAgainWithoutALayerRemovesItsSet() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null)), set("igloo", false, 1, poi(9, 9, null))));

        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null))));

        assertEquals(Set.of("village"), map.getMarkerSets().keySet());
    }

    @Test
    void publishingAgainReplacesTheMarkersOfASet() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null), poi(5, 5, null))));

        publisher.publish(map, layers(set("village", false, 0, poi(5, 5, null))));

        assertEquals(Set.of("5_70_5"), map.getMarkerSets().get("village").getMarkers().keySet());
    }

    @Test
    void setsOfOtherMapsAndOtherPublishersAreNotTouched() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        InMemoryBlueMapMap other = new InMemoryBlueMapMap("nether");
        MarkerSet foreign = MarkerSet.builder().label("Home").build();
        map.getMarkerSets().put("homes", foreign);
        publisher.publish(map, layers(set("village", false, 0, poi(4, 8, null))));
        publisher.publish(other, layers(set("fortress", false, 0, poi(1, 1, null))));

        publisher.publish(map, layers());

        assertSame(foreign, map.getMarkerSets().get("homes"));
        assertEquals(Set.of("homes"), map.getMarkerSets().keySet());
        assertNotNull(other.getMarkerSets().get("fortress"));
    }

    private static String popupOfTheOnlyArea(InMemoryBlueMapMap map, String setId) {
        MarkerSet set = map.getMarkerSets().get(setId);
        assertEquals(1, set.getMarkers().size());
        return ((ShapeMarker) set.getMarkers().values().iterator().next()).getDetail();
    }

    @Test
    void anAreaLabelWithHtmlIsEscapedInThePopupBecauseBlueMapShowsTheLabelAsHtml() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        Area area = new Area("0", SCRIPT, 63f, List.of(new int[] {0, 0}, new int[] {0, 16}, new int[] {16, 0}), List.of(), 0x73336699);

        publisher.publish(map, layers(biomeSet("biome", area)));

        ShapeMarker marker = (ShapeMarker) map.getMarkerSets().get("biome").get("0");
        assertEquals(ESCAPED_SCRIPT, marker.getDetail(), "the popup shows detail when there is one, else the raw label");
    }

    @Test
    void aBiomeNamedByAHostileLanguageFileIsEscapedInThePopup() throws IOException {
        Path jar = temp.resolve("client.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("assets/minecraft/lang/en_us.json"));
            zip.write(("{\"biome.minecraft.plains\": \"" + SCRIPT.replace("\"", "\\\"") + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        List<LayerSet> sets = LayerBuilder.buildBiomes(
                TestStates.biomes(Map.of("minecraft:plains", new int[] {0})), AddonConfig.defaults(), BiomeNames.fromClientJar(jar));

        publisher.publish(map, new LayersFile(LayersFile.FORMAT_VERSION, sets));

        assertEquals(ESCAPED_SCRIPT, popupOfTheOnlyArea(map, sets.getFirst().id()));
    }

    @Test
    void aBiomeKeyWithHtmlCharactersIsEscapedInThePopup() {
        InMemoryBlueMapMap map = new InMemoryBlueMapMap("overworld");
        List<LayerSet> sets = LayerBuilder.buildBiomes(
                TestStates.biomes(Map.of("minecraft:<b>\"x\"&'y'", new int[] {0})), AddonConfig.defaults(), BiomeNames.fromIds());

        publisher.publish(map, new LayersFile(LayersFile.FORMAT_VERSION, sets));

        String popup = popupOfTheOnlyArea(map, sets.getFirst().id());
        assertTrue(popup.contains("&lt;b&gt;&quot;x&quot;&amp;&#39;y&#39;"), popup);
        assertFalse(popup.contains("<"), popup);
    }
}
