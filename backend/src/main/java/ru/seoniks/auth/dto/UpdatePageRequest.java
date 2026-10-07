package ru.seoniks.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Код страницы сюда намеренно не входит: он задаётся один раз при создании
// и служит первичным ключом, на который ссылаются назначения прав.
public record UpdatePageRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 100_000) String content
) {}
