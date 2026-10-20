package dk.mekbits.bluemapworldscan.layer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The structure layers of Minecraft up to 26.3, in sidebar order, and the rule that sends a
 * structure id to its layer.
 *
 * <p>A layer owns the ids {@code minecraft:<layer id>} and {@code minecraft:<layer id>_<variant>},
 * so {@code village_taiga} lands in {@code village}, {@code mineshaft_mesa} in {@code mineshaft},
 * {@code ruined_portal_nether} in {@code ruined_portal} and every {@code abandoned_camp_<biome>}
 * in {@code abandoned_camp}. An id of no layer, from a datapack or a newer Minecraft, gets a layer
 * of its own, labelled from the id.
 *
 * <p>Layer names, icons and zoom tiers are adapted from a24k/bluemap-structures-paper (MIT, see
 * THIRD_PARTY_NOTICES.md).
 */
public final class LayerCatalog {

    private static final double WIDE = 5000;
    private static final double ZOOMED_IN = 1000;
    private static final String TEXTURES = "assets/minecraft/textures/";
    private static final String MINECRAFT = "minecraft";

    /** The ship of an end city; it is not a structure id of its own. */
    public static final Layer END_CITY_SHIP = new Layer("end_city_ship", "End city ship", item("elytra"), WIDE);

    private static final List<Layer> LAYERS = List.of(
            new Layer("abandoned_camp", "Abandoned camp", item("campfire"), WIDE),
            new Layer("ancient_city", "Ancient city", item("echo_shard"), WIDE),
            new Layer("bastion_remnant", "Bastion remnant", item("gold_ingot"), WIDE),
            new Layer("buried_treasure", "Buried treasure", item("heart_of_the_sea"), ZOOMED_IN),
            new Layer("desert_pyramid", "Desert pyramid", block("chiseled_sandstone"), WIDE),
            new Layer("end_city", "End city", item("shulker_shell"), WIDE),
            END_CITY_SHIP,
            new Layer("igloo", "Igloo", item("snowball"), WIDE),
            new Layer("jungle_pyramid", "Jungle pyramid", block("mossy_cobblestone"), WIDE),
            new Layer("mineshaft", "Mineshaft", item("iron_pickaxe"), ZOOMED_IN),
            new Layer("fortress", "Nether fortress", item("blaze_rod"), WIDE),
            new Layer("nether_fossil", "Nether fossil", item("bone"), ZOOMED_IN),
            new Layer("monument", "Ocean monument", block("prismarine_bricks"), WIDE),
            new Layer("ocean_ruin", "Ocean ruin", item("trident"), ZOOMED_IN),
            new Layer("pillager_outpost", "Pillager outpost", item("crossbow_standby"), WIDE),
            new Layer("ruined_portal", "Ruined portal", block("obsidian"), ZOOMED_IN),
            new Layer("shipwreck", "Shipwreck", item("oak_boat"), ZOOMED_IN),
            new Layer("stronghold", "Stronghold", item("ender_eye"), WIDE),
            new Layer("swamp_hut", "Swamp hut", item("cauldron"), WIDE),
            new Layer("trail_ruins", "Trail ruins", item("brush"), WIDE),
            new Layer("trial_chambers", "Trial chambers", item("trial_key"), ZOOMED_IN),
            new Layer("village", "Village", item("bell"), WIDE),
            new Layer("mansion", "Woodland mansion", item("totem_of_undying"), WIDE));

    private static final Map<String, Integer> ORDER = LAYERS.stream()
            .collect(Collectors.toUnmodifiableMap(Layer::id, LAYERS::indexOf));

    private LayerCatalog() {
    }

    /** All known layers in sidebar order (alphabetical by label). */
    public static List<Layer> layers() {
        return LAYERS;
    }

    /** The position of a layer in the sidebar order; layers outside the catalog come after it. */
    public static int order(Layer layer) {
        return ORDER.getOrDefault(layer.id(), LAYERS.size());
    }

    /** The layer a structure id belongs to. */
    public static Layer layerFor(String structureId) {
        String namespace = namespaceOf(structureId);
        String path = structureId.substring(structureId.indexOf(':') + 1);
        if (namespace.equals(MINECRAFT)) {
            for (Layer layer : LAYERS) {
                if (path.equals(layer.id()) || path.startsWith(layer.id() + "_")) {
                    return layer;
                }
            }
        }
        return unknown(namespace, path);
    }

    private static Layer unknown(String namespace, String path) {
        String label = IdLabels.fromId(namespace + ":" + path);
        String id = (namespace + "_" + path).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return new Layer(id, label, null, WIDE);
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? MINECRAFT : id.substring(0, colon);
    }

    private static String item(String name) {
        return TEXTURES + "item/" + name + ".png";
    }

    private static String block(String name) {
        return TEXTURES + "block/" + name + ".png";
    }
}
