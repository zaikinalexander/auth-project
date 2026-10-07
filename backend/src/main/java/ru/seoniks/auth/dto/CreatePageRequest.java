package ru.seoniks.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePageRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{3,64}$") String code,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 100_000) String content
) {}
