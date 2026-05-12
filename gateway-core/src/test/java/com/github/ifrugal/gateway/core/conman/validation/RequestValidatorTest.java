package com.github.ifrugal.gateway.core.conman.validation;

import com.github.ifrugal.gateway.core.conman.MockConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestValidator")
class RequestValidatorTest {

    @Test
    @DisplayName("validate should complete immediately when requestValidation is null")
    void validateWithNullValidation() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        request.setValidation(null);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.GET, "/test", null))
                .verifyComplete();
    }

    @Test
    @DisplayName("validate should store headers and params in exchange attributes")
    void validateStoresHeadersAndParams() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/test?foo=bar")
                .header("X-Custom", "value")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        // Empty validation — no headers/params/body rules to enforce, but storeHeadersAndParams runs
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.GET, "/test", null))
                .verifyComplete();

        // Verify headers were stored
        Map<String, Object> storedHeaders = RequestValidator.getRequestHeaders(exchange);
        assertThat(storedHeaders).isNotNull();
        assertThat(storedHeaders).containsEntry("x-custom", "value");

        // Verify query params were stored
        Map<String, Object> storedParams = RequestValidator.getQueryParams(exchange);
        assertThat(storedParams).isNotNull();
        assertThat(storedParams).containsEntry("foo", "bar");
    }

    @Test
    @DisplayName("validate should skip body validation when bodySchema is null")
    void validateSkipsBodyWhenNoSchema() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/test")
                .body(reactor.core.publisher.Flux.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        // No bodySchema set → body validation is skipped
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.POST, "/test", null))
                .verifyComplete();
    }

    @Test
    @DisplayName("getRequestBody should return null when no body stored")
    void getRequestBodyReturnsNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        Map<String, Object> body = RequestValidator.getRequestBody(exchange);
        assertThat(body).isNull();
    }

    @Test
    @DisplayName("getRequestBody should parse stored JSON body")
    void getRequestBodyParsesJson() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        exchange.getAttributes().put("REQUEST_BODY", "{\"name\":\"test\",\"value\":42}");

        Map<String, Object> body = RequestValidator.getRequestBody(exchange);
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("name", "test");
    }

    @Test
    @DisplayName("getRequestBody should return raw body map when JSON parsing fails")
    void getRequestBodyFallsBackToRaw() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        exchange.getAttributes().put("REQUEST_BODY", "not-valid-json");

        Map<String, Object> body = RequestValidator.getRequestBody(exchange);
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("raw", "not-valid-json");
    }

    @Test
    @DisplayName("getRequestBody should return null for empty body string")
    void getRequestBodyReturnsNullForEmptyString() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        exchange.getAttributes().put("REQUEST_BODY", "");

        Map<String, Object> body = RequestValidator.getRequestBody(exchange);
        assertThat(body).isNull();
    }

    @Test
    @DisplayName("getRequestHeaders should return stored headers")
    void getRequestHeadersReturnsStored() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        exchange.getAttributes().put("REQUEST_HEADERS", Map.of("content-type", "application/json"));

        Map<String, Object> headers = RequestValidator.getRequestHeaders(exchange);
        assertThat(headers).containsEntry("content-type", "application/json");
    }

    @Test
    @DisplayName("getRequestHeaders should return null when not stored")
    void getRequestHeadersReturnsNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        Map<String, Object> headers = RequestValidator.getRequestHeaders(exchange);
        assertThat(headers).isNull();
    }

    @Test
    @DisplayName("getQueryParams should return stored params")
    void getQueryParamsReturnsStored() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        exchange.getAttributes().put("REQUEST_PARAMS", Map.of("page", "1"));

        Map<String, Object> params = RequestValidator.getQueryParams(exchange);
        assertThat(params).containsEntry("page", "1");
    }

    @Test
    @DisplayName("getQueryParams should return null when not stored")
    void getQueryParamsReturnsNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        Map<String, Object> params = RequestValidator.getQueryParams(exchange);
        assertThat(params).isNull();
    }

    @Test
    @DisplayName("validate should store multiple query params correctly")
    void validateStoresMultipleQueryParams() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest
                .get("/test?page=1&size=10&sort=name")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.GET, "/test", null))
                .verifyComplete();

        Map<String, Object> storedParams = RequestValidator.getQueryParams(exchange);
        assertThat(storedParams).hasSize(3);
        assertThat(storedParams).containsEntry("page", "1");
        assertThat(storedParams).containsEntry("size", "10");
        assertThat(storedParams).containsEntry("sort", "name");
    }

    @Test
    @DisplayName("validate should lowercase all header keys")
    void validateLowercasesHeaderKeys() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/test")
                .header("Authorization", "Bearer token")
                .header("Content-Type", "application/json")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.GET, "/test", null))
                .verifyComplete();

        Map<String, Object> storedHeaders = RequestValidator.getRequestHeaders(exchange);
        // All keys should be lowercase
        for (String key : storedHeaders.keySet()) {
            assertThat(key).isEqualTo(key.toLowerCase());
        }
    }

    @Test
    @DisplayName("validate should error when body is required but Content-Length is 0")
    void validateBodyRequiredButContentLengthZero() {
        String schema = """
                {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "properties": {
                        "name": { "type": "string" }
                    },
                    "required": ["name"]
                }
                """;

        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/test")
                .header("Content-Length", "0")
                .body(Flux.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        validation.setBodySchema(schema);
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.POST, "/test", null))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("validate should validate body against JSON schema successfully")
    void validateBodyAgainstSchemaSuccess() {
        String schema = """
                {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "properties": {
                        "name": { "type": "string" },
                        "age": { "type": "integer" }
                    },
                    "required": ["name"]
                }
                """;

        String body = "{\"name\":\"John\",\"age\":30}";
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        DataBuffer buffer = factory.wrap(body.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/test")
                .header("Content-Length", String.valueOf(body.length()))
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        validation.setBodySchema(schema);
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.POST, "/test", null))
                .verifyComplete();

        // Body should be stored in exchange attributes after successful validation
        Map<String, Object> storedBody = RequestValidator.getRequestBody(exchange);
        assertThat(storedBody).isNotNull();
        assertThat(storedBody).containsEntry("name", "John");
    }

    @Test
    @DisplayName("validate should error when body fails JSON schema validation")
    void validateBodyAgainstSchemaFailure() {
        String schema = """
                {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "properties": {
                        "name": { "type": "string" },
                        "age": { "type": "integer" }
                    },
                    "required": ["name", "age"]
                }
                """;

        // Missing required "age" field
        String body = "{\"name\":\"John\"}";
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        DataBuffer buffer = factory.wrap(body.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/test")
                .header("Content-Length", String.valueOf(body.length()))
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        validation.setBodySchema(schema);
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.POST, "/test", null))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("validate should error when body is empty JSON object with schema requiring fields")
    void validateBodyEmptyJsonObject() {
        String schema = """
                {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "properties": {
                        "name": { "type": "string" }
                    },
                    "required": ["name"]
                }
                """;

        // Empty object {} is treated as "empty" by isBodyEmpty
        String body = "{}";
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        DataBuffer buffer = factory.wrap(body.getBytes(StandardCharsets.UTF_8));

        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/test")
                .header("Content-Length", String.valueOf(body.length()))
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        MockConfig mockConfig = new MockConfig();
        MockConfig.Request request = new MockConfig.Request();
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        validation.setBodySchema(schema);
        request.setValidation(validation);
        mockConfig.setRequest(request);

        StepVerifier.create(
                RequestValidator.validate(exchange, mockConfig, HttpMethod.POST, "/test", null))
                .expectError(RuntimeException.class)
                .verify();
    }
}
