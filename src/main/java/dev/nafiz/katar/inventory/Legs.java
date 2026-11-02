package dev.nafiz.katar.inventory;

/**
 * A journey as a bitmask over the legs of a route. Leg i runs from stop i to
 * stop i + 1, so travelling from stop {@code from} to stop {@code to} uses
 * legs from .. to - 1. A route can have at most 63 legs, the bits of a
 * PostgreSQL bigint that stay positive.
 */
public final class Legs {

    public static final int MAX_LEGS = 63;

    private Legs() {
    }

    /** The legs used between two stops, as a mask. */
    public static long mask(int from, int to) {
        if (from < 0 || to <= from || to > MAX_LEGS) {
            throw new IllegalArgumentException("no journey from stop " + from + " to stop " + to);
        }
        int width = to - from;
        long run = width == 64 ? -1L : (1L << width) - 1;
        return run << from;
    }

    /** True if two journeys would need the same seat on at least one leg. */
    public static boolean overlap(long a, long b) {
        return (a & b) != 0;
    }

    /** The number of legs in a mask. */
    public static int count(long mask) {
        return Long.bitCount(mask);
    }

    /**
     * True if the seat is free for the whole of {@code want}: none of its legs
     * are in {@code taken}.
     */
    public static boolean free(long taken, long want) {
        return (taken & want) == 0;
    }
}
