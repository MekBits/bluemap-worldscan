package dk.mekbits.bluemapworldscan.util;

/**
 * The addon's log lines. They go through {@link System.Logger}, which reaches stderr on every
 * platform (BlueMap's own logger is internal), and carry a prefix to tell them apart there.
 */
public final class Log {

    private static final System.Logger LOGGER = System.getLogger("dk.mekbits.bluemapworldscan");
    private static final String PREFIX = "[bluemap-worldscan] ";

    private Log() {
    }

    public static void info(String message) {
        LOGGER.log(System.Logger.Level.INFO, PREFIX + message);
    }

    public static void warn(String message) {
        LOGGER.log(System.Logger.Level.WARNING, PREFIX + message);
    }

    public static void warn(String message, Throwable cause) {
        LOGGER.log(System.Logger.Level.WARNING, PREFIX + message, cause);
    }

    public static void error(String message, Throwable cause) {
        LOGGER.log(System.Logger.Level.ERROR, PREFIX + message, cause);
    }
}
