package com.github.ifrugal.gateway.core.conman;

import com.github.ifrugal.gateway.core.conman.validation.RequestValidator;
import lazydevs.mapper.utils.SerDe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reactive servlet for handling mock API requests.
 * Matches incoming requests against configured mocks and returns appropriate responses.
 */
@Slf4j
public class ConmanServlet {

    private static ConmanServlet INSTANCE;
    private final ConmanCache conmanCache;

    public ConmanServlet(ConmanCache conmanCache) {
        this.conmanCache = conmanCache;
        INSTANCE = this;
    }

    public static ConmanServlet getInstance() {
        return INSTANCE;
    }

    /**
     * Handle an incoming request and return a mock response.
     *
     * @param req Server request
     * @return Mono with server response
     */
    public Mono<ServerResponse> service(ServerRequest req) {
        HttpMethod httpMethod = HttpMethod.valueOf(getMethodName(req));
        String uri = req.uri().getPath();
        String tenantId = req.headers().firstHeader("tenant-id");

        MockConfig data = conmanCache.getMockConfig(httpMethod, uri, tenantId);

        if (data == null) {
            return notFound(httpMethod, uri, tenantId);
        }

        // Use reactive chain for validation
        return RequestValidator.validate(req.exchange(), data, httpMethod, uri, tenantId)
                .then(serviceInternal(httpMethod, uri, tenantId, req, data))
                .onErrorResume(throwable -> {
                    log.error("Validation failed for request: {} {}", httpMethod, uri, throwable);
                    return validationException(throwable);
                });
    }

    Mono<ServerResponse> serviceInternal(HttpMethod httpMethod, String uri, String tenantId,
                                         ServerRequest req, MockConfig data) {
        Map<String, Object> map = new HashMap<>();
        map.put("request", getRequestContext(req));

        ServerResponse.BodyBuilder responseBuilder = ServerResponse
                .status(data.getResponse().getStatusCode());
        responseBuilder.contentType(MediaType.APPLICATION_JSON);

        if (data.getResponse().getResponseHeaders() != null) {
            for (Map.Entry<String, String> header : data.getResponse().getResponseHeaders().entrySet()) {
                responseBuilder.header(header.getKey(), header.getValue());
            }
        }

        try {
            responseBuilder.header("host", InetAddress.getLocalHost().getHostAddress());
        } catch (Exception e) {
            log.debug("Could not add host header: {}", e.getMessage());
        }

        return responseBuilder.bodyValue(data.resolveBodyBytes(map));
    }

    private String getMethodName(ServerRequest req) {
        var method = req.method();
        return method != null ? method.name() : "UNKNOWN";
    }

    private Mono<ServerResponse> notFound(HttpMethod httpMethod, String uri, String tenantId) {
        ServerResponse.BodyBuilder responseBuilder = ServerResponse.status(HttpStatus.NOT_FOUND.value());
        responseBuilder.contentType(MediaType.APPLICATION_JSON);
        byte[] body = String.format("""
                {
                    "status": "Conman mapping error",
                    "message": "Mapping not found for method=%s, URI=%s, tenant-id=%s"
                }
                """, httpMethod, uri, tenantId).getBytes();
        return responseBuilder.bodyValue(body);
    }

    private Mono<ServerResponse> validationException(Throwable e) {
        ServerResponse.BodyBuilder responseBuilder = ServerResponse.status(HttpStatus.BAD_REQUEST.value());
        responseBuilder.contentType(MediaType.APPLICATION_JSON);
        byte[] body = String.format("""
                {
                    "status": "Request Validation Failed",
                    "message": "%s"
                }
                """, e.getMessage().replace("\"", "\\\"")).getBytes();
        return responseBuilder.bodyValue(body);
    }

    /**
     * Extract request context for use in template processing.
     *
     * @param serverRequest Server request
     * @return Map of request context values
     */
    public Map<String, Object> getRequestContext(ServerRequest serverRequest) {
        Map<String, Object> map = new HashMap<>();
        String requestBody = (String) serverRequest.exchange().getAttributes().get("REQUEST_BODY");
        if (requestBody != null && !requestBody.isEmpty()) {
            try {
                map.put("body", SerDe.JSON.deserializeToMap(requestBody));
            } catch (Exception e) {
                log.debug("Could not parse request body as JSON: {}", e.getMessage());
                map.put("body", requestBody);
            }
        }
        map.put("requestUri", serverRequest.uri().getPath());
        map.put("httpMethod", getMethodName(serverRequest));
        map.put("params", new LinkedHashMap<>(serverRequest.queryParams()));
        map.put("headers", serverRequest.headers().asHttpHeaders().toSingleValueMap());
        return map;
    }
}
