package com.github.ifrugal.gateway.core.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BodyCaptureRequest")
class BodyCaptureRequestTest {

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @Test
    @DisplayName("should capture and cache the request body")
    void capturesRequestBody() {
        String bodyContent = "{\"name\":\"test\"}";
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest delegate = MockServerHttpRequest.post("/test")
                .body(reactor.core.publisher.Flux.just(buffer));

        BodyCaptureRequest captureRequest = new BodyCaptureRequest(delegate);

        // Async read
        StepVerifier.create(captureRequest.getFullBodyAsync())
                .assertNext(body -> assertThat(body).isEqualTo(bodyContent))
                .verifyComplete();
    }

    @Test
    @DisplayName("should return body via getBody() as Flux<DataBuffer>")
    void getBodyReturnsFlux() {
        String bodyContent = "hello";
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest delegate = MockServerHttpRequest.post("/test")
                .body(reactor.core.publisher.Flux.just(buffer));

        BodyCaptureRequest captureRequest = new BodyCaptureRequest(delegate);

        // First trigger the cache
        captureRequest.getFullBodyAsync().block();

        // Then read body via normal getBody()
        StepVerifier.create(captureRequest.getBody()
                        .map(db -> {
                            byte[] bytes = new byte[db.readableByteCount()];
                            db.read(bytes);
                            return new String(bytes, StandardCharsets.UTF_8);
                        }))
                .assertNext(body -> assertThat(body).isEqualTo(bodyContent))
                .verifyComplete();
    }

    @Test
    @DisplayName("should handle empty body gracefully")
    void emptyBody() {
        MockServerHttpRequest delegate = MockServerHttpRequest.get("/test").build();

        BodyCaptureRequest captureRequest = new BodyCaptureRequest(delegate);

        StepVerifier.create(captureRequest.getFullBodyAsync())
                .assertNext(body -> assertThat(body).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("should truncate bodies that exceed maxCaptureBytes and append a marker")
    void truncatesOversizedBodies() {
        // 1 KiB body, cap at 100 bytes -> first 100 bytes + truncation marker
        String bodyContent = "a".repeat(1024);
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest delegate = MockServerHttpRequest.post("/test")
                .body(reactor.core.publisher.Flux.just(buffer));

        BodyCaptureRequest captureRequest = new BodyCaptureRequest(delegate, 100);

        StepVerifier.create(captureRequest.getFullBodyAsync())
                .assertNext(body -> {
                    assertThat(body).startsWith("a".repeat(100));
                    assertThat(body).endsWith(BodyCaptureRequest.TRUNCATED_MARKER);
                    // truncated string = 100 chars + marker, NOT the full 1024
                    assertThat(body).hasSize(100 + BodyCaptureRequest.TRUNCATED_MARKER.length());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should not truncate when maxCaptureBytes is 0 (unlimited)")
    void disableTruncationWithZeroCap() {
        String bodyContent = "x".repeat(2048);
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest delegate = MockServerHttpRequest.post("/test")
                .body(reactor.core.publisher.Flux.just(buffer));

        BodyCaptureRequest captureRequest = new BodyCaptureRequest(delegate, 0);

        StepVerifier.create(captureRequest.getFullBodyAsync())
                .assertNext(body -> assertThat(body).isEqualTo(bodyContent))
                .verifyComplete();
    }

    @Test
    @DisplayName("should not truncate when body is exactly at the cap")
    void bodyAtCapIsNotTruncated() {
        String bodyContent = "y".repeat(64);
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest delegate = MockServerHttpRequest.post("/test")
                .body(reactor.core.publisher.Flux.just(buffer));

        BodyCaptureRequest captureRequest = new BodyCaptureRequest(delegate, 64);

        StepVerifier.create(captureRequest.getFullBodyAsync())
                .assertNext(body -> {
                    assertThat(body).isEqualTo(bodyContent);
                    assertThat(body).doesNotContain(BodyCaptureRequest.TRUNCATED_MARKER);
                })
                .verifyComplete();
    }
}
