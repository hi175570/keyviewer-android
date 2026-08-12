package com.KV;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FontReferenceTest {
    @Test
    public void logicalReferences_areRecognizedWithoutTreatingImportedFilesAsLogical() {
        assertTrue(FontReference.isLogical(FontReference.MAPLESTORY));
        assertTrue(FontReference.isLogical(FontReference.ADOFAI));
        assertTrue(FontReference.isLogical("system:Microsoft JhengHei"));
        assertFalse(FontReference.isLogical("imported-font.ttf"));
        assertFalse(FontReference.isLogical(""));
    }

    @Test
    public void emptyReference_displaysMaplestoryAsDefault() {
        assertEquals("Maplestory", FontReference.displayName(""));
    }
}
