package com.github.ifrugal.gateway.core.conman;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MockConfig")
class MockConfigTest {

    @Test
    @DisplayName("resolveBodyBytes should return body string as bytes")
    void resolveBodyBytesWithBodyString() {
        MockConfig config = new MockConfig();
        MockConfig.Response response = new MockConfig.Response();
        response.setBody("{\"hello\":\"world\"}");
        response.setStatusCode(200);
        config.setResponse(response);

        byte[] result = config.resolveBodyBytes(Map.of());
        assertThat(new String(result)).isEqualTo("{\"hello\":\"world\"}");
    }

    @Test
    @DisplayName("resolveBodyBytes should serialize bodyObj to JSON when present")
    void resolveBodyBytesWithBodyObj() {
        MockConfig config = new MockConfig();
        MockConfig.Response response = new MockConfig.Response();
        response.setBodyObj(Map.of("key", "value"));
        response.setStatusCode(200);
        config.setResponse(response);

        byte[] result = config.resolveBodyBytes(Map.of());
        String json = new String(result);
        assertThat(json).contains("\"key\"");
        assertThat(json).contains("\"value\"");
    }

    @Test
    @DisplayName("resolveBodyBytes should return empty array for null body")
    void resolveBodyBytesWithNullBody() {
        MockConfig config = new MockConfig();
        MockConfig.Response response = new MockConfig.Response();
        response.setBody(null);
        response.setStatusCode(200);
        config.setResponse(response);

        byte[] result = config.resolveBodyBytes(Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveBodyBytes should return empty array for empty body")
    void resolveBodyBytesWithEmptyBody() {
        MockConfig config = new MockConfig();
        MockConfig.Response response = new MockConfig.Response();
        response.setBody("");
        response.setStatusCode(200);
        config.setResponse(response);

        byte[] result = config.resolveBodyBytes(Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveBodyBytes should process template when bodyTemplate is true")
    void resolveBodyBytesWithTemplate() {
        MockConfig config = new MockConfig();
        MockConfig.Response response = new MockConfig.Response();
        response.setBody("{\"id\":\"${uuid1}\"}");
        response.setBodyTemplate(true);
        response.setStatusCode(200);
        config.setResponse(response);

        byte[] result = config.resolveBodyBytes(new java.util.HashMap<>());
        String body = new String(result);
        // Template should have replaced ${uuid1} with a UUID
        assertThat(body).doesNotContain("${uuid1}");
        assertThat(body).contains("\"id\":\"");
    }

    @Test
    @DisplayName("bodyObj should take precedence over body string")
    void bodyObjTakesPrecedenceOverBodyString() {
        MockConfig config = new MockConfig();
        MockConfig.Response response = new MockConfig.Response();
        response.setBody("{\"from\":\"string\"}");
        response.setBodyObj(Map.of("from", "object"));
        response.setStatusCode(200);
        config.setResponse(response);

        byte[] result = config.resolveBodyBytes(Map.of());
        String json = new String(result);
        assertThat(json).contains("\"object\"");
        assertThat(json).doesNotContain("\"string\"");
    }

    @Test
    @DisplayName("Request should hold URI and HttpMethod")
    void requestProperties() {
        MockConfig.Request request = new MockConfig.Request();
        request.setUri("/api/test");
        request.setHttpMethod(org.springframework.http.HttpMethod.POST);

        assertThat(request.getUri()).isEqualTo("/api/test");
        assertThat(request.getHttpMethod()).isEqualTo(org.springframework.http.HttpMethod.POST);
    }

    @Test
    @DisplayName("Response should hold statusCode and responseHeaders")
    void responseProperties() {
        MockConfig.Response response = new MockConfig.Response();
        response.setStatusCode(201);
        response.setContentType("application/xml");
        response.setResponseHeaders(Map.of("X-Custom", "value"));

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getContentType()).isEqualTo("application/xml");
        assertThat(response.getResponseHeaders()).containsEntry("X-Custom", "value");
    }

    @Test
    @DisplayName("RequestValidation should return null bodySchema when not set")
    void requestValidationNullSchema() {
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        assertThat(validation.getBodySchema()).isNull();
        assertThat(validation.getBodySchemaInternal()).isNull();
    }

    @Test
    @DisplayName("RequestValidation should return bodySchema when set directly")
    void requestValidationDirectSchema() {
        MockConfig.RequestValidation validation = new MockConfig.RequestValidation();
        validation.setBodySchema("{\"type\":\"object\"}");

        assertThat(validation.getBodySchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    @DisplayName("MockConfig should hold tenantId and tenantIds")
    void tenantProperties() {
        MockConfig config = new MockConfig();
        config.setTenantId("t1");
        config.setTenantIds(java.util.Set.of("t1", "t2"));

        assertThat(config.getTenantId()).isEqualTo("t1");
        assertThat(config.getTenantIds()).containsExactlyInAnyOrder("t1", "t2");
    }

    // ---- precompileBodySchemaIfPresent --------------------------------------

    @Test
    @DisplayName("precompileBodySchemaIfPresent should compile inline schema and set bodySchemaInternal")
    void precompileCompilesInlineSchema() {
        MockConfig.RequestValidation v = new MockConfig.RequestValidation();
        // networknt 3.x reads the $schema declaration to pick the dialect and
        // falls back to 2020-12 when absent (the 1.x SpecVersionDetector used
        // to throw instead). Mock authors are still expected to declare
        // $schema; this test reflects that real-world contract.
        v.setBodySchema(
                "{" +
                "  \"$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
                "  \"type\":\"object\"," +
                "  \"properties\":{\"id\":{\"type\":\"integer\"}}," +
                "  \"required\":[\"id\"]" +
                "}");

        // Pre-condition: not yet compiled
        assertThat(v.getBodySchemaInternal()).isNull();

        v.precompileBodySchemaIfPresent();

        // Post-condition: schema is compiled, no exception thrown
        assertThat(v.getBodySchemaInternal()).isNotNull();
    }

    @Test
    @DisplayName("precompileBodySchemaIfPresent should be idempotent (second call is a no-op)")
    void precompileIdempotent() {
        MockConfig.RequestValidation v = new MockConfig.RequestValidation();
        v.setBodySchema(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}");

        v.precompileBodySchemaIfPresent();
        com.networknt.schema.Schema firstCompiled = v.getBodySchemaInternal();
        assertThat(firstCompiled).isNotNull();

        // Calling again must NOT replace the cached instance.
        v.precompileBodySchemaIfPresent();
        assertThat(v.getBodySchemaInternal()).isSameAs(firstCompiled);
    }

    @Test
    @DisplayName("precompileBodySchemaIfPresent should be a no-op when no schema is set")
    void precompileNoSchemaNoOp() {
        MockConfig.RequestValidation v = new MockConfig.RequestValidation();

        v.precompileBodySchemaIfPresent();

        assertThat(v.getBodySchemaInternal()).isNull();
    }

    @Test
    @DisplayName("precompileBodySchemaIfPresent should throw at load time for malformed schema")
    void precompileFailsLoudOnMalformedSchema() {
        MockConfig.RequestValidation v = new MockConfig.RequestValidation();
        v.setBodySchema("{ this is not valid JSON");

        // The eager-compile contract: misconfigured mocks fail at load time,
        // not on the first request that hits them.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(v::precompileBodySchemaIfPresent)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to compile JSON schema");
    }
}
