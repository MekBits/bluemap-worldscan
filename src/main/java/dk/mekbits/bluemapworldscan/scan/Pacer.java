package dk.mekbits.bluemapworldscan.scan;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps the rate of chunk reads at the cap, over all reader threads: each read is due one interval
 * after the one before. A sleep that overshoots (on Windows a sleep lasts a millisecond or more) is
 * made up by the reads after it, but at most {@link #MAX_CREDIT_NANOS} of it, so time the scan
 * spent busy elsewhere buys only a short burst. A cap of 0 is no cap.
 */
final class Pacer {

    static final long MAX_CREDIT_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long SLEEP_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** How the pacer waits; tests replace it with one that only moves a clock. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private long next;

    Pacer(int perSecond) {
        this(perSecond, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    Pacer(int perSecond, LongSupplier clock, Sleeper sleeper) {
        this.intervalNanos = perSecond == 0 ? 0 : TimeUnit.SECONDS.toNanos(1) / perSecond;
        this.clock = clock;
        this.sleeper = sleeper;
        this.next = clock.getAsLong();
    }

    /** Waits until the next read may start; false when the scan was cancelled or interrupted meanwhile. */
    boolean await(CancellationToken cancel) {
        if (intervalNanos == 0) {
            return !cancel.isCancelled() && !Thread.currentThread().isInterrupted();
        }
        long due = reserve();
        long now = clock.getAsLong();
        while (due - now > 0) {
            if (cancel.isCancelled()) {
                return false;
            }
            try {
                sleeper.sleep(Math.min(due - now, SLEEP_SLICE_NANOS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            now = clock.getAsLong();
        }
        return !cancel.isCancelled();
    }

    /** The time the next read is due; taking it books that slot. */
    private synchronized long reserve() {
        next = Math.max(next, clock.getAsLong() - MAX_CREDIT_NANOS);
        long due = next;
        next += intervalNanos;
        return due;
    }
}
