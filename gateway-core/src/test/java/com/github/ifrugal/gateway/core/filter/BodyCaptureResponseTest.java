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
        assertThat(captureResponse.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("should truncate captured copy when body exceeds maxCaptureBytes")
    void truncatesAtCap() {
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        // Cap at 50 bytes; emit a single 200-byte chunk
        BodyCaptureResponse captureResponse = new BodyCaptureResponse(delegate, 50);

        String bodyContent = "z".repeat(200);
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(captureResponse.writeWith(Flux.just(buffer)))
                .verifyComplete();

        assertThat(captureResponse.isTruncated()).isTrue();
        String captured = captureResponse.getFullBody();
        assertThat(captured).startsWith("z".repeat(50));
        assertThat(captured).endsWith(BodyCaptureResponse.TRUNCATED_MARKER);
        assertThat(captured).hasSize(50 + BodyCaptureResponse.TRUNCATED_MARKER.length());
    }

    @Test
    @DisplayName("should stop accumulating after cap is reached across multiple chunks")
    void truncatesAcrossChunks() {
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        BodyCaptureResponse captureResponse = new BodyCaptureResponse(delegate, 8);

        DataBuffer c1 = bufferFactory.wrap("1234".getBytes(StandardCharsets.UTF_8));
        DataBuffer c2 = bufferFactory.wrap("5678".getBytes(StandardCharsets.UTF_8));
        DataBuffer c3 = bufferFactory.wrap("9999".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(captureResponse.writeWith(Flux.just(c1, c2, c3)))
                .verifyComplete();

        // Cap was 8; the third chunk should be entirely discarded
        assertThat(captureResponse.isTruncated()).isTrue();
        assertThat(captureResponse.getFullBody())
                .isEqualTo("12345678" + BodyCaptureResponse.TRUNCATED_MARKER);
    }

    @Test
    @DisplayName("should not truncate when maxCaptureBytes is 0")
    void disableTruncation() {
        MockServerHttpResponse delegate = new MockServerHttpResponse();
        BodyCaptureResponse captureResponse = new BodyCaptureResponse(delegate, 0);

        String bodyContent = "k".repeat(5000);
        DataBuffer buffer = bufferFactory.wrap(bodyContent.getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(captureResponse.writeWith(Flux.just(buffer)))
                .verifyComplete();

        assertThat(captureResponse.isTruncated()).isFalse();
        assertThat(captureResponse.getFullBody()).isEqualTo(bodyContent);
    }
}
