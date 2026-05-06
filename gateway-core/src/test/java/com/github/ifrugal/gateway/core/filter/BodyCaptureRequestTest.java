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
}
