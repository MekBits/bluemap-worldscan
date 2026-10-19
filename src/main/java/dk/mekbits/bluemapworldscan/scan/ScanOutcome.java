package dk.mekbits.bluemapworldscan.scan;

import dk.mekbits.bluemapworldscan.state.ScanState;

/**
 * The result of one {@link WorldScanner#scan} call.
 *
 * @param state the new state; the very same instance as {@code previous} when {@code skipped}
 * @param report what the scan did
 * @param skipped the dimension folder has no {@code region} folder, so nothing was read and the
 *     caller should store nothing for the world
 * @param cancelled the scan stopped early on request; the state is consistent, and the regions it
 *     did not finish keep their previous entries
 */
public record ScanOutcome(ScanState state, ScanReport report, boolean skipped, boolean cancelled) {
}
