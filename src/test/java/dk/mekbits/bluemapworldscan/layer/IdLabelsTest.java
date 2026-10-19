package dk.mekbits.bluemapworldscan.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IdLabelsTest {

    @Test
    void aVanillaIdIsWordsWithACapitalAndNoNamespace() {
        assertEquals("Old growth pine taiga", IdLabels.fromId("minecraft:old_growth_pine_taiga"));
        assertEquals("Plains", IdLabels.fromId("plains"));
    }

    @Test
    void anIdOfAnotherNamespaceNamesItInBrackets() {
        assertEquals("Big tower (some_pack)", IdLabels.fromId("some_pack:big_tower"));
        assertEquals("Deep caves (pack)", IdLabels.fromId("pack:deep/caves"));
    }
}
