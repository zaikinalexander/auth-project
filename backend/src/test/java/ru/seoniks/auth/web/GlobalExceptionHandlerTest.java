package ru.seoniks.auth.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.seoniks.auth.error.ApiException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionKeepsItsOwnStatus() {
        assertThat(statusOf(handler.handleApi(ApiException.conflict("занято"))))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(handler.handleApi(ApiException.notFound("нет"))))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(handler.handleApi(ApiException.tooManyRequests("стоп"))))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void apiExceptionMessageGoesToBody() {
        ResponseEntity<Map<String, String>> r = handler.handleApi(ApiException.badRequest("нельзя"));

        assertThat(r.getBody()).containsEntry("error", "нельзя");
    }

    @Test
    void accessDeniedGives403WithForbiddenBody() {
        ResponseEntity<Map<String, String>> r = handler.handleAccessDenied(new AccessDeniedException("no"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).containsEntry("error", "Forbidden");
    }

    @Test
    void preAuthorizeDenialIsCoveredByAccessDeniedHandler() {
        // именно этот наследник прилетает от @PreAuthorize и раньше уходил в catch-all как 500
        assertThat(AccessDeniedException.class).isAssignableFrom(AuthorizationDeniedException.class);
    }

    @Test
    void unreadableBodyGives400() {
        HttpMessageNotReadableException e = mock(HttpMessageNotReadableException.class);

        ResponseEntity<Map<String, String>> r = handler.handleUnreadableBody(e);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("error", "Некорректное тело запроса");
    }

    @Test
    void typeMismatchGives400AndNamesTheParameter() {
        MethodArgumentTypeMismatchException e = mock(MethodArgumentTypeMismatchException.class);
        when(e.getName()).thenReturn("id");

        ResponseEntity<Map<String, String>> r = handler.handleTypeMismatch(e);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("error", "Некорректный параметр: id");
    }

    @Test
    void missingParameterGives400() {
        ResponseEntity<Map<String, String>> r = handler.handleMissingParameter(
                new MissingServletRequestParameterException("page", "String"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("error", "Не передан параметр: page");
    }

    @Test
    void unsupportedMethodGives405() {
        ResponseEntity<Map<String, String>> r = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void unsupportedMediaTypeGives415() {
        HttpMediaTypeNotSupportedException e = mock(HttpMediaTypeNotSupportedException.class);

        ResponseEntity<Map<String, String>> r = handler.handleMediaTypeNotSupported(e);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void unknownFailureStillGives500() {
        ResponseEntity<Map<String, String>> r = handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).containsEntry("error", "Внутренняя ошибка сервера");
    }

    private static HttpStatus statusOf(ResponseEntity<?> r) {
        return HttpStatus.valueOf(r.getStatusCode().value());
    }
}
