package ru.seoniks.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.Page;
import ru.seoniks.auth.dto.CreatePageRequest;
import ru.seoniks.auth.dto.PageDto;
import ru.seoniks.auth.dto.UpdatePageRequest;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.PageRepository;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageServiceTest {

    @Mock
    PageRepository pages;

    @Mock
    AuditService audit;

    @InjectMocks
    PageService service;

    @Test
    void codePatternAcceptsOnlyUpperLettersDigitsAndUnderscore() {
        assertThat(PageService.CODE.matcher("TEST_PAGE_1").matches()).isTrue();
        assertThat(PageService.CODE.matcher("REPORT_2026_Q3").matches()).isTrue();
        assertThat(PageService.CODE.matcher("ABC").matches()).isTrue();
        assertThat(PageService.CODE.matcher("A1_".repeat(21) + "A").matches()).isTrue();

        assertThat(PageService.CODE.matcher("test_page").matches()).isFalse();
        assertThat(PageService.CODE.matcher("Test_Page").matches()).isFalse();
        assertThat(PageService.CODE.matcher("AB").matches()).isFalse();
        assertThat(PageService.CODE.matcher("A".repeat(65)).matches()).isFalse();
        assertThat(PageService.CODE.matcher("PAGE-1").matches()).isFalse();
        assertThat(PageService.CODE.matcher("PAGE 1").matches()).isFalse();
        assertThat(PageService.CODE.matcher("СТРАНИЦА").matches()).isFalse();
    }

    @Test
    void lowercaseCodeIsRejectedBeforeTouchingDatabase() {
        ApiException e = assertThrows(ApiException.class, () -> service.create(
                new CreatePageRequest("dynamic_page", "Заголовок", "текст")));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(pages);
    }

    @Test
    void shortAndInvalidCodesAreRejected() {
        for (String code : List.of("AB", "PAGE-1", "PAGE 1", "A".repeat(65))) {
            ApiException e = assertThrows(ApiException.class, () -> service.create(
                    new CreatePageRequest(code, "Заголовок", "текст")),
                    "код " + code + " должен быть отклонён");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void duplicateCodeGives409() {
        when(pages.existsByCode("NEW_PAGE")).thenReturn(true);

        ApiException e = assertThrows(ApiException.class, () -> service.create(
                new CreatePageRequest("NEW_PAGE", "Заголовок", "текст")));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(pages).existsByCode("NEW_PAGE");
    }

    @Test
    void createStoresPageAndWritesAudit() {
        when(pages.existsByCode("NEW_PAGE")).thenReturn(false);
        when(pages.save(any(Page.class))).thenAnswer(inv -> {
            Page p = inv.getArgument(0);
            if (p.getCreatedAt() == null) {
                p.setCreatedAt(Instant.now());
            }
            return p;
        });

        PageDto created = service.create(new CreatePageRequest("NEW_PAGE", "  Заголовок  ", "текст"));

        assertThat(created.code()).isEqualTo("NEW_PAGE");
        assertThat(created.title()).isEqualTo("Заголовок");
        assertThat(created.content()).isEqualTo("текст");
        assertThat(created.createdAt()).isNotNull();
        verify(audit).record(eq(AuditEvent.ADMIN_PAGE_CREATE), eq(true), eq("NEW_PAGE"), anyMap());
    }

    @Test
    void updateChangesTitleAndContentButKeepsCode() {
        Page existing = page("NEW_PAGE", "Старый заголовок", "старый текст");
        when(pages.findById("NEW_PAGE")).thenReturn(Optional.of(existing));

        PageDto updated = service.update("NEW_PAGE", new UpdatePageRequest("Новый заголовок", "новый текст"));

        assertThat(updated.code()).isEqualTo("NEW_PAGE");
        assertThat(updated.title()).isEqualTo("Новый заголовок");
        assertThat(updated.content()).isEqualTo("новый текст");
        verify(audit).record(eq(AuditEvent.ADMIN_PAGE_UPDATE), eq(true), eq("NEW_PAGE"), anyMap());
    }

    @Test
    void updateRequestStructurallyCannotCarryCode() {
        // неизменяемость кода обеспечена не проверкой в рантайме, а отсутствием поля
        assertThat(Arrays.stream(UpdatePageRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("title", "content");
    }

    @Test
    void updateUnknownPageGives404() {
        when(pages.findById("NOPE")).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class,
                () -> service.update("NOPE", new UpdatePageRequest("Заголовок", null)));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRemovesPageAndWritesAudit() {
        Page existing = page("NEW_PAGE", "Заголовок", "текст");
        when(pages.findById("NEW_PAGE")).thenReturn(Optional.of(existing));

        service.delete("NEW_PAGE");

        verify(pages).delete(existing);
        verify(audit).record(eq(AuditEvent.ADMIN_PAGE_DELETE), eq(true), eq("NEW_PAGE"), anyMap());
    }

    @Test
    void deleteUnknownPageGives404() {
        when(pages.findById("NOPE")).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.delete("NOPE"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsPagesInCodeOrder() {
        when(pages.findAllByOrderByCodeAsc())
                .thenReturn(List.of(page("AAA", "а", null), page("BBB", "б", null)));

        assertThat(service.list()).extracting(PageDto::code).containsExactly("AAA", "BBB");
    }

    private static Page page(String code, String title, String content) {
        Page p = new Page();
        p.setCode(code);
        p.setTitle(title);
        p.setContent(content);
        p.setCreatedAt(Instant.now());
        return p;
    }
}
