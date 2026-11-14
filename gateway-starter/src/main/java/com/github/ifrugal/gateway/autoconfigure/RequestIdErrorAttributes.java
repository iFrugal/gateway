package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Map;

/**
 * {@link DefaultErrorAttributes} that reports the gateway request id instead of the
 * internal {@code ServerHttpRequest} id in WebFlux error bodies.
 *
 * <p>Spring Boot fills the {@code requestId} error attribute with the reactive request's
 * connection-scoped log id. This subclass replaces it with the id stored by
 * {@link LoggingAndCachingWebFilter} under {@link LoggingAndCachingWebFilter#REQUEST_ID_ATTRIBUTE},
 * so the error body matches the echoed request-id header and the {@code requestId} MDC entry.
 * When the attribute is absent (the filter did not run) Boot's value is kept.</p>
 */
public class RequestIdErrorAttributes extends DefaultErrorAttributes {

    /**
     * Error attribute name used by {@link DefaultErrorAttributes} for the request id.
     */
    static final String REQUEST_ID_KEY = "requestId";

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        Map<String, Object> errorAttributes = super.getErrorAttributes(request, options);
        request.attribute(LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE)
                .ifPresent(requestId -> errorAttributes.computeIfPresent(REQUEST_ID_KEY, (key, value) -> requestId));
        return errorAttributes;
    }
}
