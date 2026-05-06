package com.github.ifrugal.gateway.core.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BodyCaptureResponse")
class BodyCaptureResponseTest {

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @Test
    @DisplayName("should capture response body written via writeWith")
    void capturesResponseBody() {
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        BodyCaptureResponse captureResponse = new BodyCaptureResponse(delegate);

        String bodyContent = "{\"status\":\"ok\"}";
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(captureResponse.writeWith(Flux.just(buffer)))
                .verifyComplete();

        assertThat(captureResponse.getFullBody()).isEqualTo(bodyContent);
    }

    @Test
    @DisplayName("should capture multi-chunk response body")
    void capturesMultiChunkBody() {
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        BodyCaptureResponse captureResponse = new BodyCaptureResponse(delegate);

        DataBuffer chunk1 = bufferFactory.wrap("{\"part\":".getBytes(StandardCharsets.UTF_8));
        DataBuffer chunk2 = bufferFactory.wrap("\"two\"}".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(captureResponse.writeWith(Flux.just(chunk1, chunk2)))
                .verifyComplete();

        assertThat(captureResponse.getFullBody()).isEqualTo("{\"part\":\"two\"}");
    }

    @Test
    @DisplayName("should return empty string when no body is written")
    void emptyBodyWhenNothingWritten() {
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        BodyCaptureResponse captureResponse = new BodyCaptureResponse(delegate);

        assertThat(captureResponse.getFullBody()).isEmpty();
    }
}
