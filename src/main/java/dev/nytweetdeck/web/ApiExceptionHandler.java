package dev.nytweetdeck.web;

import dev.nytweetdeck.account.AccountStoreException;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountStoreException.class)
    ProblemDetail handleAccountStore(AccountStoreException exception) {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "アカウント保存エラー",
                exception.getMessage());
    }

    @ExceptionHandler(XApiHttpException.class)
    ResponseEntity<ProblemDetail> handleXApi(XApiHttpException exception) {
        var status = exception.statusCode() == 429
                ? HttpStatus.TOO_MANY_REQUESTS
                : exception.statusCode() >= 400 && exception.statusCode() < 600
                        ? HttpStatus.BAD_GATEWAY
                        : HttpStatus.SERVICE_UNAVAILABLE;
        var response = ResponseEntity.status(status);
        if (exception.retryAfterSeconds() != null) {
            response.header("Retry-After", Long.toString(exception.retryAfterSeconds()));
        }
        return response.body(problem(status, "X API通信エラー", exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ProblemDetail handleValidation(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "入力エラー", exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:nytweetdeck:error:" + status.value()));
        return problem;
    }
}
