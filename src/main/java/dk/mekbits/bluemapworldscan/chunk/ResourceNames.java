package dk.mekbits.bluemapworldscan.chunk;

/** Namespace handling for the resource names stored in chunks (structure keys, biomes, statuses, templates). */
final class ResourceNames {

    private ResourceNames() {
    }

    /** The name with the {@code minecraft:} namespace added when it has none. */
    static String qualified(String name) {
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    /** The name without its namespace. */
    static String withoutNamespace(String name) {
        return name.substring(name.indexOf(':') + 1);
    }
}
