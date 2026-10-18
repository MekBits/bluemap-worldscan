package dk.mekbits.bluemapworldscan.scan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ScanReportTest {

    private static List<String> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> String.format("pack:tower_%02d", i)).toList();
    }

    private static String summaryWithStructureIds(int count) {
        ScanReport.Builder builder = new ScanReport.Builder();
        builder.addUnknownIds(ids(count), List.of());
        return builder.build().summary();
    }

    @Test
    void anyNumberOfUnknownIdsUpToTheLimitIsListedInFull() {
        String summary = summaryWithStructureIds(ScanReport.MAX_LISTED_IDS);

        assertTrue(summary.contains("pack:tower_19"), summary);
        assertFalse(summary.contains("more"), summary);
    }

    @Test
    void unknownIdsBeyondTheLimitAreCounted() {
        String summary = summaryWithStructureIds(ScanReport.MAX_LISTED_IDS + 5);

        assertTrue(summary.contains("pack:tower_19"), summary);
        assertFalse(summary.contains("pack:tower_20"), summary);
        assertTrue(summary.contains("and 5 more"), summary);
    }
}
