package dk.mekbits.bluemapworldscan.scan;

/** Asks a running scan to stop; the scan checks it between chunks. */
public final class CancellationToken {

    private volatile boolean cancelled;

    /** Requests the stop. It cannot be undone. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
