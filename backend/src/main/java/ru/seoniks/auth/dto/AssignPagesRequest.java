package ru.seoniks.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AssignPagesRequest(
        @NotNull List<String> pages
) {}