package app.majung.server.pickup.common;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    public record ErrorResponse(String code, String message, String requestId) { }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new ErrorResponse(
                exception.code, exception.getMessage(), (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleInput(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST",
                "요청 형식과 입력 값을 확인해주세요.", (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleHttp(Exception exception, HttpServletRequest request) {
        org.springframework.web.ErrorResponse error = (org.springframework.web.ErrorResponse) exception;
        return ResponseEntity.status(error.getStatusCode()).body(new ErrorResponse("INVALID_REQUEST",
                "요청 경로·메서드·Content-Type을 확인해주세요.", (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleConcurrentRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse("RETRY_REQUEST",
                "다른 요청을 처리 중입니다. 상태를 조회한 뒤 다시 시도해주세요.", (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        // Do not echo exception messages, request bodies, credentials or coordinates.
        log.error("Request failed: requestId={}, exceptionType={}", request.getAttribute("requestId"),
                exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ErrorResponse("INTERNAL_SERVER_ERROR",
                "요청을 처리하지 못했습니다.", (String) request.getAttribute("requestId")));
    }
}
