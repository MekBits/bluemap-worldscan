package dk.mekbits.bluemapworldscan.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What one scan did. The counts describe the chunks that were decided on: a region cut off by a
 * cancel leaves no trace here, as it leaves none in the state.
 *
 * <p>The chunk counts are disjoint, and {@code read + pre113} equals the sum of
 * {@link #dataVersionBuckets()} (and of {@link #dataVersions()}).
 *
 * @param read chunks of 1.13 or later that were read and taken into the state
 * @param unchanged chunks whose header timestamp equals the stored one, not read
 * @param failed chunks that could not be read or parsed (their old entries and timestamp are
 *     kept), and region files that could not be opened (counted once each)
 * @param wrongPosition chunks whose own position differs from their slot in the region file
 * @param changedDuringRead chunks whose header entry or external file changed while they were read
 * @param pre113 chunks saved before 1.13 (or without a DataVersion): their timestamp is stored,
 *     nothing else of them
 * @param unsupportedCompression chunks stored with a compression type the addon cannot read
 * @param dataVersionBuckets the DataVersions of the chunks that were read or counted as pre-1.13,
 *     in the buckets {@code < 1466}, {@code 1466-2199}, {@code 2200-2831}, {@code 2832-2843},
 *     {@code 2844-2969} and {@code >= 2970}, in that order, all present
 * @param dataVersions the same by distinct DataVersion (for debug logging)
 * @param unknownStructureIds structure ids found that are not in the catalog
 * @param unknownBiomeIds biome ids found that are not in the catalog
 */
public record ScanReport(
        long read,
        long unchanged,
        long failed,
        long wrongPosition,
        long changedDuringRead,
        long pre113,
        long unsupportedCompression,
        Map<String, Long> dataVersionBuckets,
        SortedMap<Integer, Long> dataVersions,
        SortedSet<String> unknownStructureIds,
        SortedSet<String> unknownBiomeIds) {

    static final String[] BUCKET_LABELS = {
        "< 1466", "1466-2199", "2200-2831", "2832-2843", "2844-2969", ">= 2970"
    };

    /** A report of a scan that did nothing. */
    public static ScanReport empty() {
        return new Builder().build();
    }

    /** One line for the log: the counts, the DataVersion buckets that are not empty and the unknown ids. */
    public String summary() {
        StringBuilder text = new StringBuilder()
                .append("read ").append(read)
                .append(", unchanged ").append(unchanged)
                .append(", failed ").append(failed)
                .append(", wrong position ").append(wrongPosition)
                .append(", changed during read ").append(changedDuringRead)
                .append(", pre-1.13 ").append(pre113)
                .append(", unsupported compression ").append(unsupportedCompression);
        String buckets = dataVersionBuckets.entrySet().stream()
                .filter(bucket -> bucket.getValue() > 0)
                .map(bucket -> bucket.getKey() + ": " + bucket.getValue())
                .collect(Collectors.joining(", "));
        if (!buckets.isEmpty()) {
            text.append("; DataVersions ").append(buckets);
        }
        if (!unknownStructureIds.isEmpty()) {
            text.append("; unknown structure ids ").append(unknownStructureIds);
        }
        if (!unknownBiomeIds.isEmpty()) {
            text.append("; unknown biome ids ").append(unknownBiomeIds);
        }
        return text.toString();
    }

    /** Collects the counts while a scan runs. */
    static final class Builder {
        long read;
        long unchanged;
        long failed;
        long wrongPosition;
        long changedDuringRead;
        long pre113;
        long unsupportedCompression;
        private final long[] buckets = new long[BUCKET_LABELS.length];
        private final SortedMap<Integer, Long> dataVersions = new TreeMap<>();
        private final SortedSet<String> unknownStructureIds = new TreeSet<>();
        private final SortedSet<String> unknownBiomeIds = new TreeSet<>();

        void addDataVersion(int dataVersion) {
            buckets[bucketOf(dataVersion)]++;
            dataVersions.merge(dataVersion, 1L, Long::sum);
        }

        void addUnknownIds(Iterable<String> structureIds, Iterable<String> biomeIds) {
            structureIds.forEach(unknownStructureIds::add);
            biomeIds.forEach(unknownBiomeIds::add);
        }

        void add(Builder other) {
            read += other.read;
            unchanged += other.unchanged;
            failed += other.failed;
            wrongPosition += other.wrongPosition;
            changedDuringRead += other.changedDuringRead;
            pre113 += other.pre113;
            unsupportedCompression += other.unsupportedCompression;
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] += other.buckets[i];
            }
            other.dataVersions.forEach((version, count) -> dataVersions.merge(version, count, Long::sum));
            unknownStructureIds.addAll(other.unknownStructureIds);
            unknownBiomeIds.addAll(other.unknownBiomeIds);
        }

        ScanReport build() {
            Map<String, Long> bucketCounts = new LinkedHashMap<>();
            for (int i = 0; i < buckets.length; i++) {
                bucketCounts.put(BUCKET_LABELS[i], buckets[i]);
            }
            return new ScanReport(
                    read, unchanged, failed, wrongPosition, changedDuringRead, pre113, unsupportedCompression,
                    Collections.unmodifiableMap(bucketCounts),
                    Collections.unmodifiableSortedMap(new TreeMap<>(dataVersions)),
                    Collections.unmodifiableSortedSet(new TreeSet<>(unknownStructureIds)),
                    Collections.unmodifiableSortedSet(new TreeSet<>(unknownBiomeIds)));
        }

        private static int bucketOf(int dataVersion) {
            if (dataVersion < 1466) {
                return 0;
            }
            if (dataVersion <= 2199) {
                return 1;
            }
            if (dataVersion <= 2831) {
                return 2;
            }
            if (dataVersion <= 2843) {
                return 3;
            }
            if (dataVersion <= 2969) {
                return 4;
            }
            return 5;
        }
    }
}
