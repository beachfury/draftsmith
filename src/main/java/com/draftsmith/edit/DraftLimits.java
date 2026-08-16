package com.draftsmith.edit;

/** Pure arithmetic for edit preflight checks; kept separate so overflow behavior is unit-testable. */
final class DraftLimits {
    static final int MAX_BLOCKS = 65_536;

    private DraftLimits() {}

    static boolean exceeds(long value, long multiplier, long limit) {
        return value > 0 && multiplier > limit / value;
    }
}

