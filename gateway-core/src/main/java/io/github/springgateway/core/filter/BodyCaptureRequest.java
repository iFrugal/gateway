package io.github.springgateway.core.filter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Request decorator that captures and caches the request body.
 * Allows the body to be read multiple times in a reactive pipeline.
 */
public class BodyCaptureRequest extends ServerHttpRequestDecorator {

    private final Mono<String> cachedBody;
    private final DataBufferFactory bufferFactory;

    public BodyCaptureRequest(ServerHttpRequest delegate) {
        super(delegate);
        this.bufferFactory = new DefaultDataBufferFactory();

        // Eagerly read and cache the body when this object is created
        this.cachedBody = DataBufferUtils.join(super.getBody())
                .map(dataBuffer -> {
                    try {
                        String body = StandardCharsets.UTF_8.decode(dataBuffer.asByteBuffer()).toString();
                        return body;
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .cache()
                .onErrorReturn("");
    }

    @Override
    public Flux<DataBuffer> getBody() {
        // Return the cached body as a new DataBuffer for downstream consumption
        return cachedBody
                .map(bodyString -> bufferFactory.wrap(bodyString.getBytes(StandardCharsets.UTF_8)))
                .flux();
    }

    /**
     * Get the full body synchronously.
     * Should only be called when the body is already resolved.
     *
     * @return The request body as a string
     */
    public String getFullBody() {
        String result = cachedBody.toFuture().getNow("");
        return result != null ? result : "";
    }

    /**
     * Get the full body asynchronously.
     *
     * @return Mono containing the request body
     */
    public Mono<String> getFullBodyAsync() {
        return cachedBody;
    }
}
