package io.github.hhhjbot.wcs.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 도메인이 던진 예외를 HTTP 응답으로 옮긴다.
 *
 * <p>도메인은 HTTP를 모른다. 위치 코드 형식이 틀리면 그냥
 * {@code IllegalArgumentException}을 던지고, 그것을 400으로 바꾸는 일은 여기서 한다.
 * 이 변환을 컨트롤러마다 적으면 같은 코드가 흩어진다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 형식 오류 · 등록되지 않은 설비 등. 요청이 잘못된 경우다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** 허용되지 않은 상태 전이 등. 지금 상태에서는 할 수 없는 요청이다. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** 없는 작업 번호. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
