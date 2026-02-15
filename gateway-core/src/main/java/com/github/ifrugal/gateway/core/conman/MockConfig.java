package com.github.ifrugal.gateway.core.conman;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.networknt.schema.JsonSchema;
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

        @JsonIgnore
        @Getter
        private JsonSchema bodySchemaInternal;

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
