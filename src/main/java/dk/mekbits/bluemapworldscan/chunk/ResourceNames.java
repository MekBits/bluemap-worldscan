package dk.mekbits.bluemapworldscan.chunk;

import java.util.regex.Pattern;

/** Namespace handling for the resource names stored in chunks (structure keys, biomes, statuses, templates). */
final class ResourceNames {

    /** Minecraft's resource location: a namespace of {@code [a-z0-9_.-]}, a colon, a path that may also hold {@code /}. */
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Minecraft only limits an id to its 32767 character string; real ids are far shorter and this one reaches labels and logs. */
    static final int MAX_LENGTH = 256;

    private ResourceNames() {
    }

    /** Whether the namespaced name is one Minecraft accepts as an id and is at most {@link #MAX_LENGTH} characters. */
    static boolean isValid(String qualifiedName) {
        return qualifiedName.length() <= MAX_LENGTH && RESOURCE_LOCATION.matcher(qualifiedName).matches();
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
