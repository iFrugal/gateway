package io.github.springgateway.core.conman;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

/**
 * Admin controller for managing Conman mock configurations at runtime.
 */
@RestController
@RequestMapping("/conman/admin")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(ConmanCache.class)
public class ConmanAdminController {

    private final ConmanCache conmanCache;

    /**
     * Register mock configurations from an uploaded file.
     *
     * @param tenantId Optional tenant ID
     * @param registrationFile YAML file containing mock configurations
     */
    @PostMapping(value = "/register", consumes = MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> register(
            @RequestPart(required = false) String tenantId,
            @RequestPart MultipartFile registrationFile) throws IOException {

        log.info("Registering mock configurations from file: {}, tenantId: {}",
                registrationFile.getOriginalFilename(), tenantId);

        conmanCache.register(tenantId, registrationFile.getInputStream());

        return Map.of(
                "status", "success",
                "message", "Mock configurations registered successfully",
                "file", registrationFile.getOriginalFilename()
        );
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
