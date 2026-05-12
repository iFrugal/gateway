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
}
