package com.KV;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LayoutGridAlignerTest {
    @Test
    public void layoutMovesAsOneGroupToNearestGrid() {
        float[][] centers = {
                {17.25f, 32.25f},
                {47.25f, 62.25f},
                {77.25f, 92.25f}
        };

        LayoutGridAligner.Result result = LayoutGridAligner.align(centers, 15f, 0.001f);

        assertEquals(-2.25f, result.offsetX, 0.0001f);
        assertEquals(-2.25f, result.offsetY, 0.0001f);
        assertEquals(3, result.groupMovedItemCount);
        assertEquals(0, result.individuallyAdjustedItemCount);
        assertEquals(30f, centers[1][0] - centers[0][0], 0.0001f);
        assertEquals(30f, centers[1][1] - centers[0][1], 0.0001f);
    }

    @Test
    public void residualLegacyDriftIsRepairedAfterGroupMove() {
        float[][] centers = {
                {17.25f, 32.25f},
                {48.10f, 61.80f}
        };

        LayoutGridAligner.Result result = LayoutGridAligner.align(centers, 15f, 0.001f);

        assertEquals(2, result.groupMovedItemCount);
        assertEquals(1, result.individuallyAdjustedItemCount);
        for (float[] center : centers) {
            assertEquals(0f, center[0] % 15f, 0.0001f);
            assertEquals(0f, center[1] % 15f, 0.0001f);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidGridSizeIsRejected() {
        LayoutGridAligner.align(new float[][]{{1f, 1f}}, 0f, 0.001f);
    }
}
