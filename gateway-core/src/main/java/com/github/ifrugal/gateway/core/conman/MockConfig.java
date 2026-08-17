package com.github.ifrugal.gateway.core.conman;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import lazydevs.mapper.utils.SerDe;
import lazydevs.mapper.utils.engine.TemplateEngine;
import lazydevs.mapper.utils.file.FileUtils;
import lazydevs.services.basic.validation.Param;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Configuration for a mock API endpoint.
 * Defines the request matching criteria and the response to return.
 */
@Getter
@Setter
@ToString
@Slf4j
public class MockConfig {

    /**
     * Tenant ID for multi-tenant mock configurations.
     */
    private String tenantId;

    /**
     * Set of tenant IDs this mock applies to.
     */
    private Set<String> tenantIds;

    /**
     * Request matching configuration.
     */
    private Request request;

    /**
     * Response configuration.
     */
    private Response response;

    @Getter
    @Setter
    @ToString
    public static class Request {
        /**
         * URI pattern to match.
         */
        private String uri;

        /**
         * HTTP method to match.
         */
        private HttpMethod httpMethod;

        /**
         * Request validation configuration.
         */
        private RequestValidation validation;
    }

    @Setter
    @ToString
    public static class RequestValidation {
        private String bodySchema;
        private String bodySchemaFile;

        /**
         * Shared schema compiler. A schema's {@code $schema} declaration is
         * honoured when present; 2020-12 is assumed when absent (the 1.x
         * SpecVersionDetector used to fail loud instead — mock authors should
         * still declare {@code $schema}).
         */
        private static final SchemaRegistry SCHEMA_REGISTRY =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

        @JsonIgnore
        @Getter
        private Schema bodySchemaInternal;

        @Getter
        private Map<String, Param> headers;

        @Getter
        private Map<String, Param> queryParams;

        public String getBodySchema() {
            if (null == this.bodySchema || this.bodySchema.isEmpty()) {
                if (null != this.bodySchemaFile && !this.bodySchemaFile.isEmpty()) {
                    try {
                        this.bodySchema = FileUtils.readFileAsString(this.bodySchemaFile);
                    } catch (Exception e) {
                        log.error("PWD = {}", new File(".").getAbsolutePath(), e);
                        throw e;
                    }
                }
            }
            return this.bodySchema;
        }

        /**
         * Eagerly compile the JSON schema (if any) and stash the result in
         * {@code bodySchemaInternal}. Idempotent: returns immediately if the
         * schema has already been compiled, so callers can invoke this on
         * every {@code register()} call without paying twice.
         *
         * <p>Called by {@link ConmanCache#register} after each mock is loaded
         * so the validator hot path doesn't have to compile under concurrent
         * load. Prior to this, the first two concurrent requests for the
         * same mock could each compile the same schema and race on the
         * {@code setBodySchemaInternal} assignment — harmless (last-write-
         * wins, both writes produce equivalent {@link Schema} instances)
         * but wasteful and not provably correct under all schedulings.
         *
         * <p>Compilation failures (malformed schema JSON, unrecognised
         * spec version) propagate as {@link RuntimeException} so that
         * misconfigured mocks fail loud at load time, not on the first
         * request that hits them.
         */
        public void precompileBodySchemaIfPresent() {
            if (this.bodySchemaInternal != null) {
                return;
            }
            String schemaText = getBodySchema();
            if (schemaText == null || schemaText.isEmpty()) {
                return;
            }
            try {
                // The registry parses the schema text itself (Jackson 3
                // internally); no Jackson 2 tree crosses the boundary.
                this.bodySchemaInternal = SCHEMA_REGISTRY.getSchema(schemaText);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to compile JSON schema for mock validation: " + e.getMessage(), e);
            }
        }
    }

    @Getter
    @Setter
    @ToString
    public static class Response {
        /**
         * Response body as an object (will be serialized to JSON).
         */
        private Map<String, Object> bodyObj;

        /**
         * Response body as a string.
         */
        private String body;

        /**
         * Content type of the response.
         */
        private String contentType;

        /**
         * HTTP status code.
         */
        private int statusCode;

        /**
         * Response headers.
         */
        private Map<String, String> responseHeaders;

        /**
         * Whether the body is a template that should be processed.
         */
        private boolean bodyTemplate;
    }

    /**
     * Resolve the response body, applying template processing if configured.
     *
     * @param params Parameters for template processing
     * @return Resolved body as bytes
     */
    public byte[] resolveBodyBytes(Map<String, Object> params) {
        String bodyLocal = this.response.body;
        if (this.response.bodyObj != null) {
            bodyLocal = SerDe.JSON.serialize(this.response.bodyObj, true);
        }
        if (this.response.bodyTemplate) {
            params.put("uuid1", UUID.randomUUID().toString());
            bodyLocal = TemplateEngine.getInstance().generate(bodyLocal, params);
        }
        return (bodyLocal == null || bodyLocal.isEmpty()) ? new byte[0] : bodyLocal.getBytes();
    }
}
