package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.annotation.Internal;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Response decorator that captures the outgoing response body for logging and
 * caching while still streaming it to the client unchanged.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Captured bytes accumulate into a {@link ByteArrayOutputStream} protected
 *       by a {@code synchronized} block, replacing the previous unbounded and
 *       unsynchronised {@link StringBuilder}. Reactor typically emits
 *       {@code writeWith}'s buffers serially, but the contract does not formally
 *       guarantee single-threaded emission, so the lock is the conservative
 *       choice and the contention is negligible on the single response path.</li>
 *   <li>Capture stops accepting bytes once {@code maxCaptureBytes} is reached.
 *       The truncation flag flips once and subsequent bytes are no longer copied
 *       out of the {@link DataBuffer}. Bytes still flow to the client unchanged;
 *       only the captured copy is bounded.</li>
 * </ul>
 */
@Internal
public class BodyCaptureResponse extends ServerHttpResponseDecorator {

    /** Marker appended to a captured body when truncation occurs. */
    static final String TRUNCATED_MARKER = "...[truncated]";

    private final Object lock = new Object();
    private final ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
    private final AtomicInteger capturedSize = new AtomicInteger(0);
    private volatile boolean truncated = false;
    private final int maxCaptureBytes;

    /**
     * Backwards-compatible constructor using
     * {@link LoggingProperties#DEFAULT_MAX_BODY_BYTES}.
     */
    public BodyCaptureResponse(ServerHttpResponse delegate) {
        this(delegate, LoggingProperties.DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * @param delegate        the response being decorated
     * @param maxCaptureBytes byte cap on the captured copy; {@code <= 0}
     *                        disables truncation
     */
    public BodyCaptureResponse(ServerHttpResponse delegate, int maxCaptureBytes) {
        super(delegate);
        this.maxCaptureBytes = maxCaptureBytes;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        Flux<DataBuffer> buffer = Flux.from(body);
        return super.writeWith(buffer.doOnNext(this::capture));
    }

    private void capture(DataBuffer buffer) {
        if (truncated) {
            return;
        }
        int readable = buffer.readableByteCount();
        if (readable == 0) {
            return;
        }
        synchronized (lock) {
            if (truncated) {
                return;
            }
            int remaining = (maxCaptureBytes <= 0) ? Integer.MAX_VALUE : maxCaptureBytes - capturedSize.get();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int toCopy = Math.min(readable, remaining);
            byte[] copy = new byte[toCopy];
            // Read from a non-consuming view of the buffer so downstream still
            // sees the full payload; toByteBuffer() returns a duplicate.
            buffer.toByteBuffer().get(copy, 0, toCopy);
            capturedBytes.write(copy, 0, toCopy);
            capturedSize.addAndGet(toCopy);
            if (toCopy < readable) {
                truncated = true;
            }
        }
    }

    /**
     * Get the captured response body, possibly truncated to
     * {@code maxCaptureBytes}.
     */
    public String getFullBody() {
        synchronized (lock) {
            String captured = capturedBytes.toString(StandardCharsets.UTF_8);
            return truncated ? captured + TRUNCATED_MARKER : captured;
        }
    }

    /** Whether the captured body was truncated at the byte cap. */
    public boolean isTruncated() {
        return truncated;
    }
}
