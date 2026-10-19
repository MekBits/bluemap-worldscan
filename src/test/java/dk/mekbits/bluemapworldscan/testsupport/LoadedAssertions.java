package dk.mekbits.bluemapworldscan.testsupport;

import dk.mekbits.bluemapworldscan.util.Loaded;

public final class LoadedAssertions {

    private LoadedAssertions() {
    }

    /** The value of a load that is expected to have succeeded. */
    public static <T> T assertOk(Loaded<T> loaded) {
        if (loaded instanceof Loaded.Ok<T>(T value)) {
            return value;
        }
        throw new AssertionError("expected a loaded value but got " + loaded);
    }
}
