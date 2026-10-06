package ru.seoniks.auth.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.seoniks.auth.error.ApiException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new HashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(err -> fields.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Ошибка валидации входных данных",
                "fields", fields));
    }

    /**
     * ОТКАЗ В ДОСТУПЕ -> 403.
     *
     * Method security (@PreAuthorize("@access.hasPage(#code)")) бросает
     * AuthorizationDeniedException (наследник AccessDeniedException) ВНУТРИ
     * DispatcherServlet. До ExceptionTranslationFilter и его accessDeniedHandler
     * такое исключение не доходит: его перехватывает @RestControllerAdvice.
     * Без этого метода catch-all ниже превращал отказ в доступе в 500.
     *
     * Симптом до правки:
     *   GET /api/pages/TEST_PAGE_2 под ролью USER -> 500 {"error":"Внутренняя ошибка сервера"}
     * После правки:
     *   GET /api/pages/TEST_PAGE_2 под ролью USER -> 403 {"error":"Forbidden"}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Forbidden"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Необработанное исключение", e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Внутренняя ошибка сервера"));
    }
}