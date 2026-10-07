package ru.seoniks.auth.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.Page;
import ru.seoniks.auth.dto.PageContentDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.PageRepository;
import ru.seoniks.auth.service.AuditService;

import java.util.Map;

@RestController
@RequestMapping("/api/pages")
public class PageController {

    private final PageRepository pages;
    private final AuditService audit;

    public PageController(PageRepository pages, AuditService audit) {
        this.pages = pages;
        this.audit = audit;
    }

    // Право проверяется здесь: фронтенд только прячет ссылки и данные не защищает.
    // Отказ фиксирует AccessChecker — он срабатывает раньше тела метода.
    @GetMapping("/{code}")
    @PreAuthorize("@access.hasPage(#code)")
    public PageContentDto getPage(@PathVariable String code) {
        Page page = pages.findById(code)
                .orElseThrow(() -> ApiException.notFound("Страница не найдена"));
        audit.record(AuditEvent.PAGE_VIEW, true, code, Map.of("code", code));
        return new PageContentDto(page.getCode(), page.getTitle(), page.getContent());
    }
}
