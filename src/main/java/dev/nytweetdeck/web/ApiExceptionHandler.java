package dev.nytweetdeck.web;

import dev.nytweetdeck.account.vault.VaultException;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VaultException.class)
    ProblemDetail handleVault(VaultException exception) {
        var status = exception.getMessage().contains("ロック")
                ? HttpStatus.LOCKED
                : HttpStatus.CONFLICT;
        return problem(status, "アカウントVaultエラー", exception.getMessage());
    }

    @ExceptionHandler(XApiHttpException.class)
    ProblemDetail handleXApi(XApiHttpException exception) {
        var status = exception.statusCode() >= 400 && exception.statusCode() < 600
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.SERVICE_UNAVAILABLE;
        return problem(status, "X API通信エラー", exception.getMessage());
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
