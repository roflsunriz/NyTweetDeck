package dev.nytweetdeck.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTest {

    @Test
    void exposesRateLimitRetryTimingWithoutUpstreamResponseContent() {
        var response = new ApiExceptionHandler()
                .handleXApi(new XApiHttpException("X翻訳の利用枠リセットを待っています。", 429, 37L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("37");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("利用枠リセット");
    }

    @Test
    void reportsAnOversizedAttachmentWithoutExposingItsBytes() {
        var problem = new ApiExceptionHandler()
                .handleOversizedUpload(new MaxUploadSizeExceededException(15 * 1024 * 1024));

        assertThat(problem.getStatus()).isEqualTo(413);
        assertThat(problem.getDetail()).contains("サイズが上限");
    }
}
