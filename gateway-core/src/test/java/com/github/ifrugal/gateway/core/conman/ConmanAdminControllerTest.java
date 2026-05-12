package com.github.ifrugal.gateway.core.conman;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ConmanAdminController")
class ConmanAdminControllerTest {

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

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
    @DisplayName("register should accept FilePart and register mocks")
    void register() throws IOException {
        byte[] yamlContent = """
                - request:
                    uri: /mock/test
                    httpMethod: GET
                  response:
                    body: '{"ok":true}'
                    statusCode: 200
                """.getBytes(StandardCharsets.UTF_8);

        FilePart filePart = mockFilePart("mocks.yml", yamlContent);

        StepVerifier.create(controller.register(null, filePart))
                .assertNext(result -> {
                    assertThat(result).containsEntry("status", "success");
                    assertThat(result).containsEntry("file", "mocks.yml");
                })
                .verifyComplete();

        verify(conmanCache).register(eq(null), any(java.io.InputStream.class));
    }

    @Test
    @DisplayName("register should pass tenant ID to cache")
    void registerWithTenantId() throws IOException {
        FilePart filePart = mockFilePart("tenant-mocks.yml", "[]".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(controller.register("tenant-x", filePart))
                .assertNext(result -> assertThat(result).containsEntry("status", "success"))
                .verifyComplete();

        verify(conmanCache).register(eq("tenant-x"), any(java.io.InputStream.class));
    }

    @Test
    @DisplayName("register should reject empty file")
    void registerRejectsEmptyFile() {
        FilePart emptyPart = mockFilePart("empty.yml", new byte[0]);

        StepVerifier.create(controller.register(null, emptyPart))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("empty");
                })
                .verify();
    }

    @Test
    @DisplayName("register should reject file exceeding size limit")
    void registerRejectsOversizedFile() {
        byte[] oversizedContent = new byte[(int) ConmanAdminController.MAX_UPLOAD_SIZE_BYTES + 1];
        FilePart largePart = mockFilePart("huge.yml", oversizedContent);

        StepVerifier.create(controller.register(null, largePart))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("exceeds maximum size");
                })
                .verify();
    }

    @Test
    @DisplayName("register should accept file at exactly the size limit")
    void registerAcceptsFileAtLimit() throws IOException {
        byte[] exactContent = new byte[(int) ConmanAdminController.MAX_UPLOAD_SIZE_BYTES];
        byte[] yamlPrefix = "[]".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(yamlPrefix, 0, exactContent, 0, yamlPrefix.length);

        FilePart filePart = mockFilePart("exact.yml", exactContent);

        StepVerifier.create(controller.register(null, filePart))
                .assertNext(result -> assertThat(result).containsEntry("status", "success"))
                .verifyComplete();
    }

    @Test
    @DisplayName("register should reject when content stream is empty (no DataBuffers emitted)")
    void registerRejectsEmptyContentFlux() {
        FilePart part = mock(FilePart.class);
        when(part.filename()).thenReturn("ghost.yml");
        // No DataBuffer ever arrives — the content publisher completes immediately
        when(part.content()).thenReturn(Flux.empty());

        StepVerifier.create(controller.register(null, part))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("empty");
                })
                .verify();
    }

    // --- helpers ---

    /** Build a FilePart that emits a single DataBuffer with the supplied bytes. */
    private FilePart mockFilePart(String filename, byte[] bytes) {
        FilePart part = mock(FilePart.class);
        when(part.filename()).thenReturn(filename);
        when(part.content()).thenAnswer(inv -> {
            if (bytes.length == 0) {
                return Flux.empty();
            }
            DataBuffer buffer = bufferFactory.wrap(bytes);
            return Flux.just(buffer);
        });
        return part;
    }
}
