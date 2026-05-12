package com.github.ifrugal.gateway.core.conman;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConmanCache")
class ConmanCacheTest {

    private ConmanCache cache;

    @BeforeEach
    void setUp() {
        ConmanProperties properties = new ConmanProperties();
        properties.setMappingFiles(java.util.Collections.emptyList());
        // We don't call init() here as it requires ApplicationContext; we test setMockConfig/getMockConfig directly
        cache = new ConmanCache(properties, null);
    }

    @Test
    @DisplayName("should generate correct cache key")
    void getKey() {
        String key = ConmanCache.getKey(HttpMethod.GET, "/mock/hello", null);
        assertThat(key).isEqualTo("GET_/mock/hello_null");
    }

    @Test
    @DisplayName("should generate cache key with tenant ID")
    void getKeyWithTenant() {
        String key = ConmanCache.getKey(HttpMethod.POST, "/mock/data", "tenant1");
        assertThat(key).isEqualTo("POST_/mock/data_tenant1");
    }

    @Test
    @DisplayName("should store and retrieve a mock config")
    void setAndGetMockConfig() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/test", null);
        cache.setMockConfig(mockConfig);

        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/mock/test", null);
        assertThat(result).isNotNull();
        assertThat(result.getRequest().getUri()).isEqualTo("/mock/test");
    }

    @Test
    @DisplayName("should return null for non-existent mock config")
    void getMockConfigNotFound() {
        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/nonexistent", null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should fall back to null tenant when tenant-specific config not found")
    void fallbackToNullTenant() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/test", null);
        cache.setMockConfig(mockConfig);

        // Looking up with a tenant should fall back to null tenant
        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/mock/test", "unknown-tenant");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should store with tenant ID and retrieve with same tenant")
    void tenantSpecificConfig() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/test", "tenant1");
        cache.setMockConfig(mockConfig);

        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/mock/test", "tenant1");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should remove a mock config")
    void unsetMockConfig() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/test", null);
        cache.setMockConfig(mockConfig);

        MockConfig removed = cache.unsetMockConfig(mockConfig);
        assertThat(removed).isNotNull();

        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/mock/test", null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should clear all mock configs")
    void clearAll() {
        cache.setMockConfig(createMockConfig(HttpMethod.GET, "/mock/a", null));
        cache.setMockConfig(createMockConfig(HttpMethod.POST, "/mock/b", null));

        cache.clear();

        assertThat(cache.getAllMockConfigs()).isEmpty();
    }

    @Test
    @DisplayName("should return all mock configs")
    void getAllMockConfigs() {
        cache.setMockConfig(createMockConfig(HttpMethod.GET, "/mock/a", null));
        cache.setMockConfig(createMockConfig(HttpMethod.POST, "/mock/b", null));

        assertThat(cache.getAllMockConfigs()).hasSize(2);
    }

    @Test
    @DisplayName("should store mock config with multiple tenant IDs")
    void multiTenantConfig() {
        MockConfig mockConfig = createMockConfig(HttpMethod.GET, "/mock/multi", null);
        mockConfig.setTenantIds(java.util.Set.of("t1", "t2", "t3"));
        cache.setMockConfig(mockConfig);

        assertThat(cache.getMockConfig(HttpMethod.GET, "/mock/multi", "t1")).isNotNull();
        assertThat(cache.getMockConfig(HttpMethod.GET, "/mock/multi", "t2")).isNotNull();
        assertThat(cache.getMockConfig(HttpMethod.GET, "/mock/multi", "t3")).isNotNull();
    }

    @Test
    @DisplayName("should register mocks from InputStream (YAML)")
    void registerFromInputStream() {
        String yaml = """
                - request:
                    uri: /mock/from-stream
                    httpMethod: GET
                  response:
                    body: '{"source":"stream"}'
                    statusCode: 200
                """;
        ByteArrayInputStream stream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));

        cache.register(null, stream);

        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/mock/from-stream", null);
        assertThat(result).isNotNull();
        assertThat(result.getResponse().getBody()).contains("stream");
    }

    @Test
    @DisplayName("should register mocks from InputStream with tenant ID")
    void registerFromInputStreamWithTenant() {
        String yaml = """
                - request:
                    uri: /mock/tenant-stream
                    httpMethod: POST
                  response:
                    body: '{"tenant":"yes"}'
                    statusCode: 201
                """;
        ByteArrayInputStream stream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));

        cache.register("tenant-x", stream);

        MockConfig result = cache.getMockConfig(HttpMethod.POST, "/mock/tenant-stream", "tenant-x");
        assertThat(result).isNotNull();
        assertThat(result.getTenantId()).isEqualTo("tenant-x");
    }

    @Test
    @DisplayName("should throw on invalid YAML input")
    void registerFromInvalidInputStream() {
        ByteArrayInputStream stream = new ByteArrayInputStream("not: valid: yaml: {{".getBytes());

        assertThatThrownBy(() -> cache.register(null, stream))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("should register mocks from Resource array")
    void registerFromResources() throws IOException {
        String yaml = """
                - request:
                    uri: /mock/resource
                    httpMethod: GET
                  response:
                    body: '{"from":"resource"}'
                    statusCode: 200
                """;
        Resource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public java.net.URL getURL() throws IOException {
                return new java.net.URL("file:///test/mock.yml");
            }
        };

        cache.register(null, resource);

        MockConfig result = cache.getMockConfig(HttpMethod.GET, "/mock/resource", null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should handle null Resources array gracefully")
    void registerFromNullResources() {
        cache.register(null, (org.springframework.core.io.Resource[]) null);
        // No exception should be thrown
        assertThat(cache.getAllMockConfigs()).isEmpty();
    }

    @Test
    @DisplayName("init should load mocks from configured mapping files")
    void initLoadsMappingFiles() throws IOException {
        String yaml = """
                - request:
                    uri: /mock/init
                    httpMethod: GET
                  response:
                    body: '{"init":"loaded"}'
                    statusCode: 200
                """;

        ApplicationContext appContext = mock(ApplicationContext.class);
        Resource bannerResource = mock(Resource.class);
        when(bannerResource.exists()).thenReturn(false);
        when(appContext.getResource(anyString())).thenReturn(bannerResource);

        Resource mockResource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public java.net.URL getURL() throws IOException {
                return new java.net.URL("file:///test/conman.yml");
            }
        };
        when(appContext.getResources("classpath:conman.yml")).thenReturn(new Resource[]{mockResource});

        ConmanProperties props = new ConmanProperties();
        // Default mapping files includes "classpath:conman.yml"
        ConmanCache initCache = new ConmanCache(props, appContext);
        initCache.init();

        MockConfig result = initCache.getMockConfig(HttpMethod.GET, "/mock/init", null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("reload should re-initialize from configured files")
    void reloadReInitializes() throws IOException {
        ApplicationContext appContext = mock(ApplicationContext.class);
        Resource bannerResource = mock(Resource.class);
        when(bannerResource.exists()).thenReturn(false);
        when(appContext.getResource(anyString())).thenReturn(bannerResource);
        when(appContext.getResources("classpath:conman.yml")).thenReturn(new Resource[]{});

        ConmanProperties props = new ConmanProperties();
        ConmanCache reloadCache = new ConmanCache(props, appContext);

        // Manually add a config
        reloadCache.setMockConfig(createMockConfig(HttpMethod.GET, "/mock/old", null));
        assertThat(reloadCache.getAllMockConfigs()).hasSize(1);

        // After reload (with no files), should be empty
        reloadCache.reload();
        assertThat(reloadCache.getAllMockConfigs()).isEmpty();
    }

    @Test
    @DisplayName("unsetMockConfig should return null when config doesn't exist")
    void unsetNonExistent() {
        MockConfig mockConfig = createMockConfig(HttpMethod.DELETE, "/mock/nonexistent", null);
        MockConfig removed = cache.unsetMockConfig(mockConfig);
        assertThat(removed).isNull();
    }

    private MockConfig createMockConfig(HttpMethod method, String uri, String tenantId) {
        MockConfig config = new MockConfig();
        config.setTenantId(tenantId);

        MockConfig.Request request = new MockConfig.Request();
        request.setHttpMethod(method);
        request.setUri(uri);
        config.setRequest(request);

        MockConfig.Response response = new MockConfig.Response();
        response.setBody("{\"status\":\"ok\"}");
        response.setStatusCode(200);
        response.setContentType("application/json");
        config.setResponse(response);

        return config;
    }
}
