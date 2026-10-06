package ru.seoniks.auth.security;

import org.springframework.stereotype.Component;
import ru.seoniks.auth.config.AppProperties;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Не более loginMaxAttempts неудач за loginWindow на связку IP+email.
// Счётчики живут в памяти JVM и обнуляются при рестарте — для одного инстанса этого хватает.
@Component
public class LoginRateLimiter {

    private record State(int failures, Instant windowEndsAt) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AppProperties props;

    public LoginRateLimiter(AppProperties props) {
        this.props = props;
    }

    public boolean isBlocked(String key) {
        State state = states.get(key);
        if (state == null) {
            return false;
        }
        if (Instant.now().isAfter(state.windowEndsAt())) {
            states.remove(key);
            return false;
        }
        return state.failures() >= props.security().loginMaxAttempts();
    }

    public void registerFailure(String key) {
        states.compute(key, (k, current) -> {
            Instant now = Instant.now();
            if (current == null || now.isAfter(current.windowEndsAt())) {
                return new State(1, now.plus(props.security().loginWindow()));
            }
            return new State(current.failures() + 1, current.windowEndsAt());
        });
    }

    public void reset(String key) {
        states.remove(key);
    }
}