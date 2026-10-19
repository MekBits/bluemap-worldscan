package dk.mekbits.bluemapworldscan.util;

/**
 * The result of loading something that may not be there or may be damaged. Callers decide what a
 * missing or failed load means; none of them throws.
 */
public sealed interface Loaded<T> permits Loaded.Missing, Loaded.Ok, Loaded.Failed {

    /** There is nothing stored. */
    record Missing<T>() implements Loaded<T> {
    }

    /** The value was loaded. */
    record Ok<T>(T value) implements Loaded<T> {
    }

    /** Something is stored but cannot be used; {@code reason} is fit for a log line. */
    record Failed<T>(String reason) implements Loaded<T> {
    }
}
