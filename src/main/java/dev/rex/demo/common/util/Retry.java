package dev.rex.demo.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff + jitter 유틸
 */
public final class Retry {

    private Retry() {
    }

    /**
     * attempt: 1..N
     */
    public static long backoffMillis(int attempt, long baseMillis, long maxMillis) {
        long base = Math.max(1, baseMillis);
        long max = Math.max(base, maxMillis);

        int pow = Math.max(0, attempt - 1);
        long exp;
        try {
            exp = Math.multiplyExact(base, 1L << Math.min(30, pow)); // overflow 방어
        } catch (ArithmeticException overflow) {
            exp = Long.MAX_VALUE;
        }

        long capped = Math.min(exp, max);
        long jitterBound = Math.max(1, capped / 3);
        long jitter = ThreadLocalRandom.current().nextLong(0, jitterBound);

        return Math.min(max, capped + jitter);
    }

    public static void sleepQuiet(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
