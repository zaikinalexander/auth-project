package ru.seoniks.auth.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.dto.PageContentDto;
import ru.seoniks.auth.error.ApiException;

import java.util.Map;

@RestController
@RequestMapping("/api/pages")
public class PageController {

    private static final Map<String, PageContentDto> PAGES = Map.of(
            "TEST_PAGE_1", new PageContentDto("TEST_PAGE_1", "Тестовая страница 1",
                    "Содержимое первой тестовой страницы. Доступ выдан администратором."),
            "TEST_PAGE_2", new PageContentDto("TEST_PAGE_2", "Тестовая страница 2",
                    "Содержимое второй тестовой страницы. Доступ выдан администратором."),
            "TEST_PAGE_3", new PageContentDto("TEST_PAGE_3", "Тестовая страница 3",
                    "Содержимое третьей тестовой страницы. Доступ выдан администратором.")
    );

    // Право проверяется здесь: фронтенд только прячет ссылки и данные не защищает.
    @GetMapping("/{code}")
    @PreAuthorize("@access.hasPage(#code)")
    public PageContentDto getPage(@PathVariable String code) {
        PageContentDto page = PAGES.get(code);
        if (page == null) {
            throw ApiException.notFound("Страница не найдена");
        }
        return page;
    }
}