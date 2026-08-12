package com.KV;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class KeyDispatchRouteTest {
    @Test
    public void mappedKey_keepsPhysicalKeyAsVisualKey() {
        KeyDispatchRoute route = KeyDispatchRoute.resolve("a", "b");

        assertEquals("a", route.visualKey);
        assertEquals("b", route.mappedOutputKey);
        assertEquals("b", route.outputKey);
    }

    @Test
    public void unmappedKey_hasNoMappedOutput() {
        KeyDispatchRoute route = KeyDispatchRoute.resolve("space", "space");

        assertEquals("space", route.visualKey);
        assertNull(route.mappedOutputKey);
        assertEquals("space", route.outputKey);
    }

    @Test
    public void ghostKey_isVisibleButNeverInjected() {
        KeyDispatchRoute route = KeyDispatchRoute.resolve("a", "b", true);

        assertEquals("a", route.visualKey);
        assertNull(route.outputKey);
    }
}
