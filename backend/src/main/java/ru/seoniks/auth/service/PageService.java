package ru.seoniks.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.Page;
import ru.seoniks.auth.dto.CreatePageRequest;
import ru.seoniks.auth.dto.PageDto;
import ru.seoniks.auth.dto.UpdatePageRequest;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.PageRepository;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PageService {

    static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{3,64}$");

    private final PageRepository pages;
    private final AuditService audit;

    public PageService(PageRepository pages, AuditService audit) {
        this.pages = pages;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PageDto> list() {
        return pages.findAllByOrderByCodeAsc().stream().map(PageDto::from).toList();
    }

    @Transactional
    public PageDto create(CreatePageRequest request) {
        String code = request.code() == null ? "" : request.code().trim();
        if (!CODE.matcher(code).matches()) {
            throw ApiException.badRequest(
                    "Код страницы: от 3 до 64 символов, только заглавные латинские буквы, цифры и подчёркивание");
        }
        if (pages.existsByCode(code)) {
            throw ApiException.conflict("Страница с таким кодом уже существует");
        }

        Page page = new Page();
        page.setCode(code);
        page.setTitle(request.title().trim());
        page.setContent(request.content());

        Page saved = pages.save(page);
        audit.record(AuditEvent.ADMIN_PAGE_CREATE, true, saved.getCode(),
                Map.of("code", saved.getCode(), "title", saved.getTitle()));
        return PageDto.from(saved);
    }

    // Правит заголовок и содержимое. Код не меняется: он первичный ключ, на который
    // ссылаются назначения прав, поэтому в теле запроса его просто нет.
    @Transactional
    public PageDto update(String code, UpdatePageRequest request) {
        Page page = require(code);
        String titleBefore = page.getTitle();

        page.setTitle(request.title().trim());
        page.setContent(request.content());

        audit.record(AuditEvent.ADMIN_PAGE_UPDATE, true, page.getCode(),
                Map.of("code", page.getCode(),
                        "changes", Map.of("title", titleBefore + " -> " + page.getTitle())));
        return PageDto.from(page);
    }

    @Transactional
    public void delete(String code) {
        Page page = require(code);
        // Назначения прав снимаются каскадно: у user_pages стоит
        // on delete cascade на ссылку pages(code).
        pages.delete(page);
        audit.record(AuditEvent.ADMIN_PAGE_DELETE, true, page.getCode(),
                Map.of("code", page.getCode(), "title", page.getTitle()));
    }

    private Page require(String code) {
        return pages.findById(code)
                .orElseThrow(() -> ApiException.notFound("Страница не найдена"));
    }
}
