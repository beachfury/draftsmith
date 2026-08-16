package com.draftsmith.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftSafetyTest {
    @Test
    void multiplicationLimitCheckDoesNotOverflow() {
        assertFalse(DraftLimits.exceeds(0, Long.MAX_VALUE, DraftLimits.MAX_BLOCKS));
        assertFalse(DraftLimits.exceeds(256, 256, DraftLimits.MAX_BLOCKS));
        assertTrue(DraftLimits.exceeds(65_536, 2, DraftLimits.MAX_BLOCKS));
        assertTrue(DraftLimits.exceeds(Long.MAX_VALUE, 2, Long.MAX_VALUE));
    }

    @Test
    void shapeEstimatesRejectLargeAllocationsButAllowReasonableShells() {
        assertEquals(200, DraftShapes.estimate(DraftShapes.Shape.SQUARE, false, 10, 2, 1));
        assertTrue(DraftShapes.estimate(DraftShapes.Shape.SPHERE, false, 64, 1, 1) > DraftLimits.MAX_BLOCKS);
        assertTrue(DraftShapes.estimate(DraftShapes.Shape.CYLINDER, false, 256, 128, 1) > DraftLimits.MAX_BLOCKS);
        assertTrue(DraftShapes.estimate(DraftShapes.Shape.SPHERE, true, 64, 1, 1) < DraftLimits.MAX_BLOCKS);
    }
}

