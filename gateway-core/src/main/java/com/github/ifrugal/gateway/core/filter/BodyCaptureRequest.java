package com.github.ifrugal.gateway.core.filter;

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
 * Request decorator that captures and caches the request body so it can be
 * read multiple times within a single request lifecycle (once by the logging
 * pipeline, once by the cache lookup, and finally replayed to downstream
 * filters).
 *
 * <p>The captured copy is bounded by {@code maxCaptureBytes}: bodies larger
 * than the cap are still forwarded to upstream <em>in full</em>, but the
 * cached string used for logging / caching is truncated and tagged with a
 * trailing marker. This prevents an unbounded multipart upload from holding
 * its entire payload as a Java {@code String} on the heap for the lifetime
 * of the request.
 *
 * <p>A value of {@code maxCaptureBytes <= 0} disables truncation entirely
 * (legacy behaviour).
 */
public class BodyCaptureRequest extends ServerHttpRequestDecorator {

    /** Marker appended to a captured body when truncation occurs. */
    static final String TRUNCATED_MARKER = "...[truncated]";

    private final Mono<String> cachedBody;
    private final DataBufferFactory bufferFactory;
    private final int maxCaptureBytes;

    /**
     * Backwards-compatible constructor; uses {@link com.github.ifrugal.gateway.core.config.LoggingProperties#DEFAULT_MAX_BODY_BYTES}.
     */
    public BodyCaptureRequest(ServerHttpRequest delegate) {
        this(delegate, com.github.ifrugal.gateway.core.config.LoggingProperties.DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * @param delegate        the original request
     * @param maxCaptureBytes maximum number of bytes captured for logging/caching;
     *                        {@code <= 0} means "no cap"
     */
    public BodyCaptureRequest(ServerHttpRequest delegate, int maxCaptureBytes) {
        super(delegate);
        this.bufferFactory = new DefaultDataBufferFactory();
        this.maxCaptureBytes = maxCaptureBytes;

        // Eagerly read and cache the body when this object is created so it
        // survives the original Flux being consumed by downstream filters.
        this.cachedBody = DataBufferUtils.join(super.getBody())
                .map(dataBuffer -> {
                    try {
                        return decodeCapped(dataBuffer, maxCaptureBytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .defaultIfEmpty("")
                .cache()
                .onErrorReturn("");
    }

    @Override
    public Flux<DataBuffer> getBody() {
        // Return the cached body as a new DataBuffer for downstream consumption.
        // Note: when the body was truncated, downstream gets the truncated copy.
        // The cap is therefore best-suited to logging/caching scenarios; if you
        // need pass-through-only semantics for huge bodies, do not wrap the
        // request in BodyCaptureRequest at all.
        return cachedBody
                .map(bodyString -> bufferFactory.wrap(bodyString.getBytes(StandardCharsets.UTF_8)))
                .flux();
    }

    /**
     * Get the full body synchronously.
     *
     * @deprecated Synchronous body access from a reactive context is fragile —
     *             if the body has not finished arriving when this is called,
     *             you silently get the default ({@code ""}). Use
     *             {@link #getFullBodyAsync()} and compose with {@code Mono}.
     * @return the captured body string, possibly truncated, possibly empty
     */
    @Deprecated(forRemoval = true, since = "1.1.0")
    public String getFullBody() {
        String result = cachedBody.toFuture().getNow("");
        return result != null ? result : "";
    }

    /**
     * Get the full body asynchronously.
     *
     * @return Mono containing the request body (possibly truncated to
     *         {@code maxCaptureBytes})
     */
    public Mono<String> getFullBodyAsync() {
        return cachedBody;
    }

    /**
     * Decode at most {@code cap} bytes from {@code buffer}, appending
     * {@link #TRUNCATED_MARKER} when truncation occurs. A {@code cap <= 0}
     * disables truncation.
     */
    private static String decodeCapped(DataBuffer buffer, int cap) {
        int available = buffer.readableByteCount();
        if (cap <= 0 || available <= cap) {
            return StandardCharsets.UTF_8.decode(buffer.asByteBuffer()).toString();
        }
        byte[] bytes = new byte[cap];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8) + TRUNCATED_MARKER;
    }
}
