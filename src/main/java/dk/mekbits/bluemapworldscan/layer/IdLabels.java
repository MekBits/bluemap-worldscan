package dk.mekbits.bluemapworldscan.layer;

import java.util.Locale;

/** Names made from a namespaced id, for the ids no table or language file names. */
public final class IdLabels {

    private static final String MINECRAFT = "minecraft";

    private IdLabels() {
    }

    /**
     * The words of the id's path with a capital first letter, and the namespace in brackets unless
     * it is {@code minecraft}: {@code some_pack:big_tower} gives "Big tower (some_pack)".
     *
     * @param id the id, with or without a namespace
     */
    public static String fromId(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? MINECRAFT : id.substring(0, colon);
        String path = id.substring(colon + 1);
        String words = path.replace('_', ' ').replace('/', ' ').strip();
        String label = words.isEmpty() ? path : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
        return namespace.equals(MINECRAFT) ? label : label + " (" + namespace + ")";
    }
}
