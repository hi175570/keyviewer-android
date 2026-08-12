package com.KV;

final class LayoutGridAligner {
    private LayoutGridAligner() {}

    static Result align(float[][] centers, float gridSize, float tolerance) {
        if (centers == null || centers.length == 0) {
            return Result.NONE;
        }
        if (!Float.isFinite(gridSize) || gridSize <= 0f) {
            throw new IllegalArgumentException("gridSize must be positive and finite");
        }

        int anchorIndex = 0;
        for (int index = 1; index < centers.length; index++) {
            if (centers[index][1] < centers[anchorIndex][1]
                    || (centers[index][1] == centers[anchorIndex][1]
                    && centers[index][0] < centers[anchorIndex][0])) {
                anchorIndex = index;
            }
        }

        float offsetX = normalizeOffset(
                snap(centers[anchorIndex][0], gridSize) - centers[anchorIndex][0], tolerance);
        float offsetY = normalizeOffset(
                snap(centers[anchorIndex][1], gridSize) - centers[anchorIndex][1], tolerance);
        boolean groupMoved = offsetX != 0f || offsetY != 0f;
        if (groupMoved) {
            for (float[] center : centers) {
                center[0] += offsetX;
                center[1] += offsetY;
            }
        }

        int individuallyAdjusted = 0;
        for (float[] center : centers) {
            float snappedX = snap(center[0], gridSize);
            float snappedY = snap(center[1], gridSize);
            if (Math.abs(snappedX - center[0]) <= tolerance
                    && Math.abs(snappedY - center[1]) <= tolerance) {
                continue;
            }
            center[0] = snappedX;
            center[1] = snappedY;
            individuallyAdjusted++;
        }

        return new Result(
                offsetX,
                offsetY,
                groupMoved ? centers.length : 0,
                individuallyAdjusted);
    }

    private static float snap(float value, float gridSize) {
        return Math.round(value / gridSize) * gridSize;
    }

    private static float normalizeOffset(float value, float tolerance) {
        return Math.abs(value) <= tolerance ? 0f : value;
    }

    static final class Result {
        static final Result NONE = new Result(0f, 0f, 0, 0);

        final float offsetX;
        final float offsetY;
        final int groupMovedItemCount;
        final int individuallyAdjustedItemCount;

        Result(float offsetX, float offsetY, int groupMovedItemCount, int individuallyAdjustedItemCount) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.groupMovedItemCount = groupMovedItemCount;
            this.individuallyAdjustedItemCount = individuallyAdjustedItemCount;
        }
    }
}
