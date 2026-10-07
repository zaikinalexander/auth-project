package ru.seoniks.auth.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.dto.CreatePageRequest;
import ru.seoniks.auth.dto.PageDto;
import ru.seoniks.auth.dto.UpdatePageRequest;
import ru.seoniks.auth.service.PageService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/pages")
// Второе закрытие поверх правила /api/admin/** из SecurityConfig.
@PreAuthorize("hasRole('ADMIN')")
public class AdminPageController {

    private final PageService pageService;

    public AdminPageController(PageService pageService) {
        this.pageService = pageService;
    }

    @GetMapping
    public List<PageDto> list() {
        return pageService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PageDto create(@Valid @RequestBody CreatePageRequest request) {
        return pageService.create(request);
    }

    @PutMapping("/{code}")
    public PageDto update(@PathVariable String code,
                          @Valid @RequestBody UpdatePageRequest request) {
        return pageService.update(code, request);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        pageService.delete(code);
    }
}
