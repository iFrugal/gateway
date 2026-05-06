package com.github.ifrugal.gateway.core.conman;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ConmanServlet")
class ConmanServletTest {

    private ConmanCache conmanCache;
    private ConmanServlet servlet;

    @BeforeEach
    void setUp() {
        conmanCache = mock(ConmanCache.class);
        servlet = new ConmanServlet(conmanCache);
    }

    @Test
    @DisplayName("should return 404 when no mock config matches")
    void serviceNotFound() {
        ServerRequest request = mockServerRequest(HttpMethod.GET, "/mock/unknown", null);
        when(conmanCache.getMockConfig(HttpMethod.GET, "/mock/unknown", null)).thenReturn(null);

        StepVerifier.create(servlet.service(request))
                .assertNext(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should return configured response when mock config matches")
    void serviceWithMatch() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/hello", 200,
                "{\"message\":\"hello\"}", null);

        ServerRequest request = mockServerRequest(HttpMethod.GET, "/mock/hello", null);
        when(conmanCache.getMockConfig(HttpMethod.GET, "/mock/hello", null)).thenReturn(mockConfig);

        StepVerifier.create(servlet.service(request))
                .assertNext(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should return configured response with custom status code")
    void serviceWithCustomStatusCode() {
        MockConfig mockConfig = createMockConfig(HttpMethod.POST, "/mock/create", 201,
                "{\"id\":1}", null);

        ServerRequest request = mockServerRequest(HttpMethod.POST, "/mock/create", null);
        when(conmanCache.getMockConfig(HttpMethod.POST, "/mock/create", null)).thenReturn(mockConfig);

        StepVerifier.create(servlet.service(request))
                .assertNext(response -> {
                    assertThat(response.statusCode().value()).isEqualTo(201);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should include custom response headers when configured")
    void serviceWithResponseHeaders() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/headers", 200,
                "{}", Map.of("X-Custom", "test-value"));

        ServerRequest request = mockServerRequest(HttpMethod.GET, "/mock/headers", null);
        when(conmanCache.getMockConfig(HttpMethod.GET, "/mock/headers", null)).thenReturn(mockConfig);

        StepVerifier.create(servlet.service(request))
                .assertNext(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.headers().getFirst("X-Custom")).isEqualTo("test-value");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should use tenant-id header for lookup")
    void serviceWithTenantId() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/tenant", 200,
                "{\"tenant\":\"found\"}", null);

        ServerRequest request = mockServerRequest(HttpMethod.GET, "/mock/tenant", "tenant-a");
        when(conmanCache.getMockConfig(HttpMethod.GET, "/mock/tenant", "tenant-a")).thenReturn(mockConfig);

        StepVerifier.create(servlet.service(request))
                .assertNext(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getRequestContext should extract request metadata")
    void getRequestContext() {
        ServerRequest request = mockServerRequest(HttpMethod.GET, "/mock/context", null);

        Map<String, Object> context = servlet.getRequestContext(request);

        assertThat(context).containsKey("requestUri");
        assertThat(context).containsKey("httpMethod");
        assertThat(context).containsKey("params");
        assertThat(context).containsKey("headers");
        assertThat(context.get("requestUri")).isEqualTo("/mock/context");
        assertThat(context.get("httpMethod")).isEqualTo("GET");
    }

    @Test
    @DisplayName("getRequestContext should include parsed body when present in exchange")
    void getRequestContextWithBody() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/mock/body").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);
        exchange.getAttributes().put("REQUEST_BODY", "{\"name\":\"test\"}");

        ServerRequest request = mock(ServerRequest.class);
        when(request.exchange()).thenReturn(exchange);
        when(request.uri()).thenReturn(URI.create("/mock/body"));
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.queryParams()).thenReturn(new org.springframework.util.LinkedMultiValueMap<>());
        when(request.headers()).thenReturn(mock(ServerRequest.Headers.class));
        when(request.headers().asHttpHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        Map<String, Object> context = servlet.getRequestContext(request);

        assertThat(context).containsKey("body");
    }

    @Test
    @DisplayName("serviceInternal should build response with body")
    void serviceInternalBuildsResponse() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/internal", 200,
                "{\"data\":\"test\"}", null);

        ServerRequest request = mockServerRequest(HttpMethod.GET, "/mock/internal", null);

        StepVerifier.create(servlet.serviceInternal(HttpMethod.GET, "/mock/internal", null, request, mockConfig))
                .assertNext(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
                })
                .verifyComplete();
    }

    // --- Helper methods ---

    private ServerRequest mockServerRequest(HttpMethod method, String path, String tenantId) {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.method(method, path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        ServerRequest request = mock(ServerRequest.class);
        when(request.exchange()).thenReturn(exchange);
        when(request.uri()).thenReturn(URI.create(path));
        when(request.method()).thenReturn(method);
        when(request.queryParams()).thenReturn(new org.springframework.util.LinkedMultiValueMap<>());

        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(headers.firstHeader("tenant-id")).thenReturn(tenantId);
        when(headers.asHttpHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(request.headers()).thenReturn(headers);

        return request;
    }

    private MockConfig createMockConfig(HttpMethod method, String uri, int statusCode,
                                         String body, Map<String, String> responseHeaders) {
        MockConfig config = new MockConfig();

        MockConfig.Request request = new MockConfig.Request();
        request.setHttpMethod(method);
        request.setUri(uri);
        config.setRequest(request);

        MockConfig.Response response = new MockConfig.Response();
        response.setBody(body);
        response.setStatusCode(statusCode);
        response.setContentType("application/json");
        if (responseHeaders != null) {
            response.setResponseHeaders(responseHeaders);
        }
        config.setResponse(response);

        return config;
    }
}
