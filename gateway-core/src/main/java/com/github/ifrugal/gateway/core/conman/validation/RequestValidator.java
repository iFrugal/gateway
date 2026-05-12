package com.github.ifrugal.gateway.core.conman.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersionDetector;
import com.github.ifrugal.gateway.core.conman.ConmanCache;
import com.github.ifrugal.gateway.core.conman.MockConfig;
import lazydevs.mapper.utils.SerDe;
import lazydevs.services.basic.validation.ParamValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reactive request validator for Conman mock configurations.
 * Validates request headers, query parameters, and body against configured schemas.
 */
public class RequestValidator {

    private static final Logger logger = LoggerFactory.getLogger(RequestValidator.class);
    private static final ParamValidator paramValidator = new ParamValidator();

    // Constants for exchange attributes
    private static final String REQUEST_BODY_ATTR = "REQUEST_BODY";
    private static final String REQUEST_HEADERS_ATTR = "REQUEST_HEADERS";
    private static final String REQUEST_PARAMS_ATTR = "REQUEST_PARAMS";

    /**
     * Validate a request for WebFlux reactive environment.
     *
     * @param exchange Server web exchange
     * @param mockConfig Mock configuration with validation rules
     * @param httpMethod HTTP method
     * @param uri Request URI
     * @param tenantId Optional tenant ID
     * @return Mono that completes on success or errors on validation failure
     */
    public static Mono<Void> validate(ServerWebExchange exchange, MockConfig mockConfig,
                                      HttpMethod httpMethod, String uri, String tenantId) {
        ServerHttpRequest request = exchange.getRequest();
        MockConfig.RequestValidation requestValidation = mockConfig.getRequest().getValidation();
        String key = ConmanCache.getKey(httpMethod, uri, tenantId);

        if (requestValidation == null) {
            return Mono.empty();
        }

        // Store headers and query params in exchange for later use
        storeHeadersAndParams(exchange, request);

        return validateHeadersAndParams(exchange, requestValidation, key)
                .then(validateBodyIfNeeded(exchange, requestValidation));
    }

    private static void storeHeadersAndParams(ServerWebExchange exchange, ServerHttpRequest request) {
        // Store headers as Map<String, Object>
        Map<String, Object> headers = request.getHeaders().toSingleValueMap()
                .entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(),
                        Map.Entry::getValue
                ));
        exchange.getAttributes().put(REQUEST_HEADERS_ATTR, headers);

        // Store query parameters as Map<String, Object>
        MultiValueMap<String, String> queryParams = request.getQueryParams();
        Map<String, Object> queryParamsMap = queryParams.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().isEmpty() ? "" : entry.getValue().get(0)
                ));
        exchange.getAttributes().put(REQUEST_PARAMS_ATTR, queryParamsMap);
    }

    private static Mono<Void> validateHeadersAndParams(ServerWebExchange exchange,
                                                       MockConfig.RequestValidation requestValidation,
                                                       String key) {
        return Mono.fromRunnable(() -> {
            // Validate headers
            if (requestValidation.getHeaders() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> headers = (Map<String, Object>) exchange.getAttributes().get(REQUEST_HEADERS_ATTR);
                paramValidator.validate(false, "Header", key, requestValidation.getHeaders(), headers);
            }

            // Validate query parameters
            if (requestValidation.getQueryParams() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> queryParams = (Map<String, Object>) exchange.getAttributes().get(REQUEST_PARAMS_ATTR);
                paramValidator.validate(false, "Query", key, requestValidation.getQueryParams(), queryParams);
            }
        });
    }

    private static Mono<Void> validateBodyIfNeeded(ServerWebExchange exchange,
                                                   MockConfig.RequestValidation requestValidation) {
        if (requestValidation.getBodySchema() == null) {
            logger.debug("No body schema configured, skipping body validation");
            return Mono.empty();
        }

        logger.debug("Body validation is required, body schema is present");

        // Prepare schema if not already done
        if (requestValidation.getBodySchemaInternal() == null) {
            logger.debug("Preparing JSON schema for validation");
            JsonNode schemaJsonNode = convert(requestValidation.getBodySchema());
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersionDetector.detect(schemaJsonNode))
                    .getSchema(schemaJsonNode);
            requestValidation.setBodySchemaInternal(schema);
        }

        // Check Content-Length
        ServerHttpRequest request = exchange.getRequest();
        long contentLength = request.getHeaders().getContentLength();
        logger.debug("Request Content-Length: {}", contentLength);

        if (contentLength == 0) {
            logger.warn("Content-Length is 0 but body validation is required");
            return Mono.error(new RuntimeException("Request body is required but Content-Length is 0"));
        }

        // Read and validate body reactively
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .timeout(java.time.Duration.ofSeconds(5))
                .doOnNext(dataBuffer -> {
                    try {
                        if (dataBuffer.readableByteCount() == 0) {
                            throw new RuntimeException("Request body is required but DataBuffer is empty");
                        }

                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        String bodyString = new String(bytes, StandardCharsets.UTF_8);

                        logger.debug("Body string length: {}", bodyString.length());

                        if (isBodyEmpty(bodyString)) {
                            throw new RuntimeException("Request body is required but was empty or null");
                        }

                        // Parse JSON
                        JsonNode jsonNode = SerDe.JSON.getOBJECT_MAPPER().readTree(bodyString);

                        if (jsonNode == null || jsonNode.isNull()) {
                            throw new RuntimeException("Request body is required but contains null JSON");
                        }

                        // Store body string in exchange attributes
                        exchange.getAttributes().put(REQUEST_BODY_ATTR, bodyString);

                        // Perform validation against schema
                        validateAgainstSchema(jsonNode, requestValidation.getBodySchemaInternal());

                        logger.debug("Body validation completed successfully");

                    } catch (Exception e) {
                        logger.error("Exception during body validation: {}", e.getMessage());
                        throw e instanceof RuntimeException ? (RuntimeException) e :
                                new RuntimeException("Failed to validate request body", e);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .switchIfEmpty(
                        Mono.fromRunnable(() -> logger.warn("No DataBuffer received but validation is required"))
                                .then(Mono.error(new RuntimeException("Request body is required but no body was provided")))
                )
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> new RuntimeException("Timeout reading request body - no body data received within 5 seconds"))
                .then();
    }

    private static boolean isBodyEmpty(String bodyString) {
        if (bodyString == null) return true;
        String trimmed = bodyString.trim();
        return trimmed.isEmpty() ||
                trimmed.equals("null") ||
                trimmed.equals("{}") ||
                trimmed.equals("[]");
    }

    private static JsonNode convert(Object obj) {
        try {
            if (obj == null) return null;
            if (obj instanceof String) {
                return SerDe.JSON.getOBJECT_MAPPER().readTree((String) obj);
            }
            if (obj instanceof JsonNode) {
                return (JsonNode) obj;
            }
            return SerDe.JSON.getOBJECT_MAPPER().valueToTree(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert object to JsonNode: " + obj, e);
        }
    }

    private static void validateAgainstSchema(JsonNode jsonNode, JsonSchema schema) {
        if (jsonNode == null || jsonNode.isNull()) {
            throw new RuntimeException("Cannot validate null JSON against schema");
        }

        var validationResult = schema.validate(jsonNode);
        if (!validationResult.isEmpty()) {
            StringBuilder errorMessages = new StringBuilder("JSON Schema validation failed:");
            validationResult.forEach(error ->
                    errorMessages.append("\n- Path: ").append(error.getInstanceLocation())
                            .append(", Error: ").append(error.getMessage())
            );
            throw new RuntimeException(errorMessages.toString());
        }
    }

    // Utility methods for accessing stored request data

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getRequestBody(ServerWebExchange exchange) {
        String body = (String) exchange.getAttributes().get(REQUEST_BODY_ATTR);
        if (body != null && !body.isEmpty()) {
            try {
                return SerDe.JSON.getOBJECT_MAPPER().readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                return Map.of("raw", body);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getRequestHeaders(ServerWebExchange exchange) {
        return (Map<String, Object>) exchange.getAttributes().get(REQUEST_HEADERS_ATTR);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getQueryParams(ServerWebExchange exchange) {
        return (Map<String, Object>) exchange.getAttributes().get(REQUEST_PARAMS_ATTR);
    }
}
