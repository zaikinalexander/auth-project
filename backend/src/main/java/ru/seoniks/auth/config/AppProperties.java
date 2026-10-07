package ru.seoniks.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Refresh refresh,
        Security security,
        Admin admin,
        Audit audit
) {
    public record Jwt(
            Path privateKeyPath,
            Path publicKeyPath,
            Duration accessTokenTtl
    ) {}

    public record Refresh(
            Duration ttl,
            String cookieName,
            boolean cookieSecure
    ) {}

    public record Security(
            int loginMaxAttempts,
            Duration loginWindow
    ) {}

    public record Admin(
            String initialEmail,
            String initialPassword
    ) {}

    public record Audit(
            int retentionDays
    ) {}
}
