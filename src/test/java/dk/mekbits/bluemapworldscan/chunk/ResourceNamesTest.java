package dk.mekbits.bluemapworldscan.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceNamesTest {

    private static String idOfLength(int length) {
        return "pack:" + "a".repeat(length - "pack:".length());
    }

    @Test
    void anIdOfExactlyTheMaximumLengthIsValid() {
        assertTrue(ResourceNames.isValid(idOfLength(ResourceNames.MAX_LENGTH)));
    }

    @Test
    void anIdOneCharacterLongerIsNotValid() {
        assertFalse(ResourceNames.isValid(idOfLength(ResourceNames.MAX_LENGTH + 1)));
    }

    @Test
    void theCharactersOfAResourceLocationAreValidAndOthersAreNot() {
        assertTrue(ResourceNames.isValid("some-pack_1.x:path/to/deep.v-2"));
        assertFalse(ResourceNames.isValid("Pack:id"));
        assertFalse(ResourceNames.isValid("pack:id with space"));
        assertFalse(ResourceNames.isValid("a:b:c"));
        assertFalse(ResourceNames.isValid("pack:"));
        assertFalse(ResourceNames.isValid("noNamespace"));
    }
}
