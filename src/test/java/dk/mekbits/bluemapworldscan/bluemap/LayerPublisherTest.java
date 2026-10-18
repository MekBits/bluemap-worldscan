package dk.mekbits.bluemapworldscan.bluemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import dk.mekbits.bluemapworldscan.layer.LayersFile;
import dk.mekbits.bluemapworldscan.layer.LayersFile.LayerSet;
import dk.mekbits.bluemapworldscan.layer.LayersFile.Poi;
import dk.mekbits.bluemapworldscan.testsupport.InMemoryBlueMapMap;
import org.junit.jupiter.api.Test;

class LayerPublisherTest {

    private final LayerPublisher publisher = new LayerPublisher();

    private static LayerSet set(String id, boolean hidden, int sorting, Poi... pois) {
        return new LayerSet(id, "Label of " + id, hidden, sorting, List.of(pois), List.of());
    }

    private static Poi poi(int x, int z, String icon) {
        return new Poi(x + "_70_" + z, "Village", x, 70, z, "<div>" + x + "</div>", icon, 11, 11, 5000);
    }

    private static LayersFile layers(LayerSet... sets) {
        return new LayersFile(1, List.of(sets));
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
}
