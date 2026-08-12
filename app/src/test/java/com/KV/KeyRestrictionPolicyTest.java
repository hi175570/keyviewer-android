package com.KV;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KeyRestrictionPolicyTest {
    @Test
    public void disabledPolicy_neverBlocks() {
        assertFalse(KeyRestrictionPolicy.shouldBlock("q", false, false, "q"));
        assertFalse(KeyRestrictionPolicy.shouldBlockUnknownCode(200, false));
    }

    @Test
    public void enabledPolicy_blocksKeysMissingFromKv() {
        assertTrue(KeyRestrictionPolicy.shouldBlock("q", false, true, ""));
        assertFalse(KeyRestrictionPolicy.shouldBlock("q", true, true, ""));
        assertTrue(KeyRestrictionPolicy.shouldBlockUnknownCode(200, true));
    }

    @Test
    public void forcedList_blocksVisibleKeyAndNormalizesInput() {
        assertTrue(KeyRestrictionPolicy.shouldBlock("space", true, true,
                " A，space; f1  a "));
        assertEquals("a, space, f1",
                KeyRestrictionPolicy.normalizeBlockedKeys(" A，space; f1  a "));
    }

    @Test
    public void escapeAndWindowsKeys_areAlwaysProtected() {
        assertFalse(KeyRestrictionPolicy.shouldBlock("escape", false, true, "escape"));
        assertFalse(KeyRestrictionPolicy.shouldBlock("lmeta", false, true, "lwin"));
        assertFalse(KeyRestrictionPolicy.shouldBlock("rmeta", false, true, "rwin"));
        assertFalse(KeyRestrictionPolicy.shouldBlockUnknownCode(1, true));
        assertFalse(KeyRestrictionPolicy.shouldBlockUnknownCode(125, true));
        assertFalse(KeyRestrictionPolicy.shouldBlockUnknownCode(126, true));
        assertEquals("a", KeyRestrictionPolicy.normalizeBlockedKeys(
                "esc, windows, lwin, rwin, a"));
    }
}
