package dk.mekbits.bluemapworldscan.testsupport;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Waits for something another thread does. */
public final class Await {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long POLL_MILLIS = 20;

    private Await() {
    }

    /** Polls until the condition holds, and fails after 20 seconds. */
    public static void until(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timed out waiting for " + description);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + description, e);
            }
        }
    }
}
