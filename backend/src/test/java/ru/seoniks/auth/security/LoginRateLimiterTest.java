package ru.seoniks.auth.security;

import org.junit.jupiter.api.Test;
import ru.seoniks.auth.config.AppProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static final String KEY = "203.0.113.7|user@seoniks.ru";

    private LoginRateLimiter limiter(int maxAttempts, Duration window) {
        return new LoginRateLimiter(new AppProperties(
                null, null, new AppProperties.Security(maxAttempts, window), null));
    }

    @Test
    void unknownKeyIsNotBlocked() {
        assertThat(limiter(5, Duration.ofMinutes(5)).isBlocked(KEY)).isFalse();
    }

    @Test
    void failuresBelowLimitDoNotBlock() {
        LoginRateLimiter limiter = limiter(5, Duration.ofMinutes(5));

        for (int i = 0; i < 4; i++) {
            limiter.registerFailure(KEY);
        }

        assertThat(limiter.isBlocked(KEY)).isFalse();
    }

    @Test
    void fifthFailureBlocks() {
        LoginRateLimiter limiter = limiter(5, Duration.ofMinutes(5));

        for (int i = 0; i < 5; i++) {
            limiter.registerFailure(KEY);
        }

        assertThat(limiter.isBlocked(KEY)).isTrue();
    }

    @Test
    void resetUnblocks() {
        LoginRateLimiter limiter = limiter(5, Duration.ofMinutes(5));
        for (int i = 0; i < 5; i++) {
            limiter.registerFailure(KEY);
        }
        assertThat(limiter.isBlocked(KEY)).isTrue();

        limiter.reset(KEY);

        assertThat(limiter.isBlocked(KEY)).isFalse();
    }

    @Test
    void expiredWindowUnblocks() throws InterruptedException {
        LoginRateLimiter limiter = limiter(5, Duration.ofMillis(120));
        for (int i = 0; i < 5; i++) {
            limiter.registerFailure(KEY);
        }
        assertThat(limiter.isBlocked(KEY)).isTrue();

        Thread.sleep(250);

        assertThat(limiter.isBlocked(KEY)).isFalse();
    }

    @Test
    void failureAfterExpiredWindowStartsNewCount() throws InterruptedException {
        LoginRateLimiter limiter = limiter(5, Duration.ofMillis(120));
        for (int i = 0; i < 5; i++) {
            limiter.registerFailure(KEY);
        }
        Thread.sleep(250);

        limiter.registerFailure(KEY);

        assertThat(limiter.isBlocked(KEY)).isFalse();
    }

    @Test
    void keysAreIndependent() {
        LoginRateLimiter limiter = limiter(5, Duration.ofMinutes(5));
        for (int i = 0; i < 5; i++) {
            limiter.registerFailure(KEY);
        }

        assertThat(limiter.isBlocked(KEY)).isTrue();
        assertThat(limiter.isBlocked("203.0.113.7|other@seoniks.ru")).isFalse();
    }
}
