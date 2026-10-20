package dk.mekbits.bluemapworldscan.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** The pacer on a clock that only moves when it sleeps, by as much as a coarse sleep moves it. */
class PacerTest {

    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    /** A clock whose sleeps last at least {@code minimumSleep}, as on Windows. */
    private static final class CoarseClock {
        final long minimumSleep;
        long now = 1_000_000_000L;

        CoarseClock(long minimumSleep) {
            this.minimumSleep = minimumSleep;
        }

        long now() {
            return now;
        }

        void sleep(long nanos) {
            now += Math.max(nanos, minimumSleep);
        }
    }

    /** How many reads the pacer lets through in one second of the clock. */
    private static int readsInOneSecond(Pacer pacer, CoarseClock clock) {
        long end = clock.now + TimeUnit.SECONDS.toNanos(1);
        int reads = 0;
        CancellationToken cancel = new CancellationToken();
        while (clock.now < end) {
            assertTrue(pacer.await(cancel));
            reads++;
        }
        return reads;
    }

    @Test
    void sleepsThatOvershootAreMadeUpSoTheCapIsReached() {
        // A 1 ms sleep for a 0.5 ms interval: without making up the overshoot this gives 1000 a second.
        CoarseClock clock = new CoarseClock(MILLI);
        Pacer pacer = new Pacer(2000, clock::now, clock::sleep);

        int reads = readsInOneSecond(pacer, clock);

        assertTrue(reads >= 1990 && reads <= 2010, "2000 a second were allowed, " + reads + " were read");
    }

    @Test
    void timeSpentElsewhereBuysAtMostTwentyMillisecondsOfReads() {
        CoarseClock clock = new CoarseClock(0);
        Pacer pacer = new Pacer(1000, clock::now, clock::sleep);
        CancellationToken cancel = new CancellationToken();
        pacer.await(cancel);

        clock.now += TimeUnit.SECONDS.toNanos(5);
        long before = clock.now;
        int burst = 0;
        while (true) {
            pacer.await(cancel);
            if (clock.now != before) {
                break;
            }
            burst++;
        }

        // The reads due in the last 20 ms of the idle time, plus the one due now.
        assertEquals(21, burst);
    }

    @Test
    void aCapOfZeroNeverSleeps() {
        CoarseClock clock = new CoarseClock(MILLI);
        Pacer pacer = new Pacer(0, clock::now, clock::sleep);
        long before = clock.now;

        for (int i = 0; i < 10_000; i++) {
            assertTrue(pacer.await(new CancellationToken()));
        }

        assertEquals(before, clock.now);
    }

    @Test
    void aCancelWhileWaitingEndsTheWait() {
        CoarseClock clock = new CoarseClock(0);
        CancellationToken cancel = new CancellationToken();
        Pacer pacer = new Pacer(1, clock::now, nanos -> {
            cancel.cancel();
            clock.sleep(nanos);
        });
        pacer.await(cancel);

        assertFalse(pacer.await(cancel));
    }
}
