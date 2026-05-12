package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.config.LoggingProperties;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

/**
 * Exchange decorator that wraps both request and response with body capture
 * capabilities. Both wrappers honour a single {@code maxCaptureBytes} cap so
 * captured copies cannot grow unbounded on the heap.
 */
public class BodyCaptureExchange extends ServerWebExchangeDecorator {

    private final BodyCaptureRequest bodyCaptureRequest;
    private final BodyCaptureResponse bodyCaptureResponse;

    /**
     * Backwards-compatible constructor using
     * {@link LoggingProperties#DEFAULT_MAX_BODY_BYTES}.
     */
    public BodyCaptureExchange(ServerWebExchange exchange) {
        this(exchange, LoggingProperties.DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * @param exchange         the original exchange
     * @param maxCaptureBytes  byte cap shared by the request and response
     *                         wrappers; {@code <= 0} disables truncation
     */
    public BodyCaptureExchange(ServerWebExchange exchange, int maxCaptureBytes) {
        super(exchange);
        this.bodyCaptureRequest = new BodyCaptureRequest(exchange.getRequest(), maxCaptureBytes);
        this.bodyCaptureResponse = new BodyCaptureResponse(exchange.getResponse(), maxCaptureBytes);
    }

    @Override
    public BodyCaptureRequest getRequest() {
        return bodyCaptureRequest;
    }

    @Override
    public BodyCaptureResponse getResponse() {
        return bodyCaptureResponse;
    }
}
