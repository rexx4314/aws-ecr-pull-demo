package dev.rex.demo.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 지수 백오프(Exponential backoff)와 지터(jitter)를 제공하는 유틸 클래스
 * <p>
 * 재시도 로직에서 대기 시간을 계산할 때 사용
 */
public final class Retry {

    /**
     * 인스턴스화 금지
     */
    private Retry() {
    }

    /**
     * 재시도 시 대기할 밀리초를 계산
     * <p>
     * 동작:
     * - attempt는 1부터 시작한다고 가정 (attempt: 1..N).
     * - baseMillis를 기준으로 2^(attempt-1) 배수로 증가하되 overflow를 방어하고 maxMillis로 제한
     * - 최종값에 지터를 추가(0 .. capped/3)하여 반환
     *
     * @param attempt    재시도 시도 횟수(1 이상 권장)
     * @param baseMillis 기본 대기 시간(밀리초)
     * @param maxMillis  허용되는 최대 대기 시간(밀리초)
     * @return 계산된 대기 시간(밀리초)
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

    /**
     * 지정된 밀리초만큼 현재 스레드를 sleep
     * <p>
     * InterruptedException이 발생하면 인터럽트 상태를 복원하고 반환
     *
     * @param millis 잠자는 시간(밀리초). 0 이하이면 즉시 반환
     */
    public static void sleepQuiet(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
