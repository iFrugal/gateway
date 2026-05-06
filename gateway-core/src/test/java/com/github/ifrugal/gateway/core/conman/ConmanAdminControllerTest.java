package com.github.ifrugal.gateway.core.conman;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("ConmanAdminController")
class ConmanAdminControllerTest {

    private ConmanCache conmanCache;
    private ConmanAdminController controller;

    @BeforeEach
    void setUp() {
        conmanCache = mock(ConmanCache.class);
        controller = new ConmanAdminController(conmanCache);
    }

    @Test
    @DisplayName("getAllMocks should delegate to conmanCache")
    void getAllMocks() {
        MockConfig config = new MockConfig();
        when(conmanCache.getAllMockConfigs()).thenReturn(Map.of("key1", config));

        Map<String, MockConfig> result = controller.getAllMocks();

        assertThat(result).hasSize(1);
        assertThat(result).containsKey("key1");
        verify(conmanCache).getAllMockConfigs();
    }

    @Test
    @DisplayName("clearAll should clear cache and return success")
    void clearAll() {
        Map<String, String> result = controller.clearAll();

        assertThat(result).containsEntry("status", "success");
        verify(conmanCache).clear();
    }

    @Test
    @DisplayName("reload should reinitialize cache and return success")
    void reload() throws IOException {
        Map<String, String> result = controller.reload();

        assertThat(result).containsEntry("status", "success");
        assertThat(result).containsEntry("message", "Mock configurations reloaded successfully");
        verify(conmanCache).reload();
    }

    @Test
    @DisplayName("testMock should return found status when mock exists")
    void testMockFound() {
        MockConfig config = new MockConfig();
        when(conmanCache.getMockConfig(HttpMethod.GET, "/api/test", null)).thenReturn(config);

        Map<String, Object> result = controller.testMock(HttpMethod.GET, "/api/test", null);

        assertThat(result).containsEntry("status", "found");
        assertThat(result).containsEntry("mockConfig", config);
    }

    @Test
    @DisplayName("testMock should return not_found status when mock doesn't exist")
    void testMockNotFound() {
        when(conmanCache.getMockConfig(HttpMethod.GET, "/api/missing", null)).thenReturn(null);

        Map<String, Object> result = controller.testMock(HttpMethod.GET, "/api/missing", null);

        assertThat(result).containsEntry("status", "not_found");
        assertThat(result.get("message").toString()).contains("/api/missing");
    }

    @Test
    @DisplayName("testMock should include tenant in lookup")
    void testMockWithTenant() {
        when(conmanCache.getMockConfig(HttpMethod.POST, "/api/data", "t1")).thenReturn(null);

        Map<String, Object> result = controller.testMock(HttpMethod.POST, "/api/data", "t1");

        assertThat(result).containsEntry("status", "not_found");
        verify(conmanCache).getMockConfig(HttpMethod.POST, "/api/data", "t1");
    }

    @Test
    @DisplayName("register should accept multipart file and register mocks")
    void register() throws IOException {
        byte[] yamlContent = """
                - request:
                    uri: /mock/test
                    httpMethod: GET
                  response:
                    body: '{"ok":true}'
                    statusCode: 200
                """.getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "registrationFile", "mocks.yml", "application/x-yaml", yamlContent);

        Map<String, String> result = controller.register(null, file);

        assertThat(result).containsEntry("status", "success");
        assertThat(result).containsEntry("file", "mocks.yml");
        verify(conmanCache).register(eq(null), any(java.io.InputStream.class));
    }

    @Test
    @DisplayName("register should pass tenant ID to cache")
    void registerWithTenantId() throws IOException {
        byte[] yamlContent = "[]".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "registrationFile", "tenant-mocks.yml", "application/x-yaml", yamlContent);

        Map<String, String> result = controller.register("tenant-x", file);

        assertThat(result).containsEntry("status", "success");
        verify(conmanCache).register(eq("tenant-x"), any(java.io.InputStream.class));
    }

    @Test
    @DisplayName("register should reject empty file")
    void registerRejectsEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "registrationFile", "empty.yml", "application/x-yaml", new byte[0]);

        assertThatThrownBy(() -> controller.register(null, emptyFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("register should reject file exceeding size limit")
    void registerRejectsOversizedFile() {
        // Create a file larger than MAX_UPLOAD_SIZE_BYTES (1 MB)
        byte[] oversizedContent = new byte[(int) ConmanAdminController.MAX_UPLOAD_SIZE_BYTES + 1];
        MockMultipartFile largeFile = new MockMultipartFile(
                "registrationFile", "huge.yml", "application/x-yaml", oversizedContent);

        assertThatThrownBy(() -> controller.register(null, largeFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum size");
    }

    @Test
    @DisplayName("register should accept file at exactly the size limit")
    void registerAcceptsFileAtLimit() throws IOException {
        byte[] exactContent = new byte[(int) ConmanAdminController.MAX_UPLOAD_SIZE_BYTES];
        // Fill with valid YAML
        byte[] yamlPrefix = "[]".getBytes();
        System.arraycopy(yamlPrefix, 0, exactContent, 0, yamlPrefix.length);

        MockMultipartFile file = new MockMultipartFile(
                "registrationFile", "exact.yml", "application/x-yaml", exactContent);

        Map<String, String> result = controller.register(null, file);

        assertThat(result).containsEntry("status", "success");
    }
}
