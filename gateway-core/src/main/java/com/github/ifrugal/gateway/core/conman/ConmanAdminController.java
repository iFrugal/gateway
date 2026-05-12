package com.github.ifrugal.gateway.core.conman;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

/**
 * Admin controller for managing Conman mock configurations at runtime.
 *
 * <p>All multipart handling uses the reactive {@link FilePart} type from
 * {@code org.springframework.http.codec.multipart}. {@code MultipartFile}
 * (the servlet API equivalent, used in {@code 1.0.x}) was removed in
 * {@code 1.1.0} so the upload streams through the WebFlux codec rather
 * than relying on Spring's servlet-shim compatibility layer.</p>
 */
@RestController
@RequestMapping("/conman/admin")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(ConmanCache.class)
public class ConmanAdminController {

    private final ConmanCache conmanCache;

    /**
     * Defence-in-depth cap on the upload's joined byte count (1 MB).
     *
     * <p>The framework-level caps in {@code application.yml}
     * ({@code spring.codec.max-in-memory-size},
     * {@code spring.webflux.multipart.max-in-memory-size},
     * {@code spring.webflux.multipart.max-disk-usage-per-part}) reject an
     * oversized part long before this check runs — they are the real
     * primary ceiling. This controller-level cap protects deployments
     * that consume {@code gateway-core} directly (without the bundled
     * {@code gateway-app/application.yml}) and forget to wire those
     * framework caps themselves.</p>
     */
    static final long MAX_UPLOAD_SIZE_BYTES = 1024 * 1024; // 1 MB

    /**
     * Register mock configurations from an uploaded YAML file.
     *
     * @param tenantId         optional tenant identifier for the registered mocks
     * @param registrationFile YAML file containing mock configurations (max 1 MB)
     * @return Mono completing with a success-status map; errors via
     *         {@link IllegalArgumentException} (empty/oversize) or
     *         {@link IOException} (parse / persistence failure)
     */
    @PostMapping(value = "/register", consumes = MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, String>> register(
            @RequestPart(required = false) String tenantId,
            @RequestPart("registrationFile") FilePart registrationFile) {

        final String filename = registrationFile.filename();

        // Join the multipart content into a single DataBuffer reactively.
        // The framework cap is the real primary defence; the in-controller
        // size check below is a fallback.
        return DataBufferUtils.join(registrationFile.content())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Upload file is empty")))
                .flatMap(dataBuffer -> {
                    try {
                        int size = dataBuffer.readableByteCount();
                        if (size == 0) {
                            return Mono.error(new IllegalArgumentException("Upload file is empty"));
                        }
                        if (size > MAX_UPLOAD_SIZE_BYTES) {
                            return Mono.error(new IllegalArgumentException(
                                    String.format("Upload file exceeds maximum size of %d bytes (got %d bytes)",
                                            MAX_UPLOAD_SIZE_BYTES, size)));
                        }

                        byte[] bytes = new byte[size];
                        dataBuffer.read(bytes);

                        log.info("Registering mock configurations from file: {} ({} bytes), tenantId: {}",
                                filename, size, tenantId);

                        // ConmanCache.register(String, InputStream) is declared without
                        // a checked-exception throws clause today. Catch RuntimeException
                        // so a YAML-parse or persistence failure surfaces as an error
                        // signal on the returned Mono rather than escaping to the global
                        // error handler unwrapped.
                        try {
                            conmanCache.register(tenantId, new ByteArrayInputStream(bytes));
                        } catch (RuntimeException e) {
                            return Mono.error(e);
                        }

                        return Mono.just(Map.of(
                                "status", "success",
                                "message", "Mock configurations registered successfully",
                                "file", filename
                        ));
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                });
    }

    /**
     * Get all registered mock configurations.
     */
    @GetMapping("/mocks")
    public Map<String, MockConfig> getAllMocks() {
        return conmanCache.getAllMockConfigs();
    }

    /**
     * Reload all mock configurations from configured files.
     */
    @PostMapping("/reload")
    public Map<String, String> reload() throws IOException {
        log.info("Reloading all mock configurations");
        conmanCache.reload();
        return Map.of(
                "status", "success",
                "message", "Mock configurations reloaded successfully"
        );
    }

    /**
     * Clear all mock configurations.
     */
    @DeleteMapping("/mocks")
    public Map<String, String> clearAll() {
        log.info("Clearing all mock configurations");
        conmanCache.clear();
        return Map.of(
                "status", "success",
                "message", "All mock configurations cleared"
        );
    }

    /**
     * Test a mock configuration without registering it.
     */
    @GetMapping("/test")
    public Map<String, Object> testMock(
            @RequestParam HttpMethod httpMethod,
            @RequestParam String uri,
            @RequestParam(required = false) String tenantId) {

        MockConfig config = conmanCache.getMockConfig(httpMethod, uri, tenantId);

        if (config == null) {
            return Map.of(
                    "status", "not_found",
                    "message", String.format("No mock found for %s %s (tenant: %s)", httpMethod, uri, tenantId)
            );
        }

        return Map.of(
                "status", "found",
                "mockConfig", config
        );
    }
}
