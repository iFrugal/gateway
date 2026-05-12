package com.github.ifrugal.gateway.core.conman;

import jakarta.annotation.PostConstruct;
import lazydevs.mapper.utils.SerDe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static lazydevs.mapper.utils.file.FileUtils.readInputStreamAsString;

/**
 * Cache for storing and retrieving mock configurations.
 * Thread-safe implementation using ConcurrentHashMap.
 */
@Slf4j
public class ConmanCache {

    private final Map<String, MockConfig> mockConfigMap = new ConcurrentHashMap<>();
    private final ConmanProperties conmanProperties;
    private final ApplicationContext applicationContext;

    public ConmanCache(ConmanProperties conmanProperties, ApplicationContext applicationContext) {
        this.conmanProperties = conmanProperties;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() throws IOException {
        // Try to load and display banner
        try {
            Resource bannerResource = applicationContext.getResource(conmanProperties.getBannerPath());
            if (bannerResource.exists()) {
                log.info(readInputStreamAsString(bannerResource.getInputStream()));
            }
        } catch (Exception e) {
            log.debug("Could not load Conman banner: {}", e.getMessage());
        }

        mockConfigMap.clear();

        conmanProperties.getMappingFiles().forEach(filePath -> {
            try {
                register(null, applicationContext.getResources(filePath));
            } catch (Exception e) {
                if (e instanceof FileNotFoundException && filePath.equals("classpath:conman.yml")) {
                    log.warn("Ignoring default conman.yml - file not present: {}", filePath);
                } else {
                    throw new IllegalArgumentException("Failed to load mock config from: " + filePath, e);
                }
            }
        });

        log.info("Loaded {} mock configurations", mockConfigMap.size());
        mockConfigMap.forEach((key, value) -> log.debug("Mock config: {}", key));
    }

    /**
     * Register mock configurations from resources.
     *
     * @param tenantId Optional tenant ID
     * @param resources Resources containing mock configurations
     */
    public void register(String tenantId, Resource... resources) {
        if (resources != null) {
            Arrays.stream(resources).forEach(resource -> {
                try {
                    log.info("Loading mock config from: {}", resource.getURL());
                    register(tenantId, resource.getInputStream());
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read resource: " + resource, e);
                }
            });
        }
    }

    /**
     * Register mock configurations from an input stream.
     *
     * @param tenantId Optional tenant ID
     * @param inputStream Input stream containing YAML mock configurations
     */
    public void register(String tenantId, InputStream inputStream) {
        try {
            List<MockConfig> mockConfigs = SerDe.YAML.deserializeToList(inputStream, MockConfig.class);
            mockConfigs.forEach(mockConfig -> {
                if (null != tenantId) {
                    mockConfig.setTenantId(tenantId);
                }
                setMockConfig(mockConfig);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse mock configuration", e);
        }
    }

    /**
     * Generate a cache key for a mock configuration.
     *
     * @param httpMethod HTTP method
     * @param uri URI pattern
     * @param tenantId Optional tenant ID
     * @return Cache key
     */
    public static String getKey(HttpMethod httpMethod, String uri, String tenantId) {
        return httpMethod.name() + "_" + uri + "_" + (null == tenantId ? "null" : tenantId);
    }

    /**
     * Get a mock configuration by request details.
     *
     * @param httpMethod HTTP method
     * @param uri URI
     * @param tenantId Optional tenant ID
     * @return MockConfig or null if not found
     */
    public MockConfig getMockConfig(HttpMethod httpMethod, String uri, String tenantId) {
        MockConfig mockConfig = mockConfigMap.get(getKey(httpMethod, uri, tenantId));
        if (null == mockConfig) {
            mockConfig = mockConfigMap.get(getKey(httpMethod, uri, null));
        }
        return mockConfig;
    }

    /**
     * Store a mock configuration.
     *
     * @param mockConfig Mock configuration to store
     */
    public void setMockConfig(MockConfig mockConfig) {
        if (null != mockConfig.getTenantIds() && !mockConfig.getTenantIds().isEmpty()) {
            mockConfig.getTenantIds().forEach(tenantId ->
                    mockConfigMap.put(
                            getKey(mockConfig.getRequest().getHttpMethod(),
                                    mockConfig.getRequest().getUri(),
                                    tenantId),
                            mockConfig));
        } else {
            mockConfigMap.put(
                    getKey(mockConfig.getRequest().getHttpMethod(),
                            mockConfig.getRequest().getUri(),
                            mockConfig.getTenantId()),
                    mockConfig);
        }
    }

    /**
     * Remove a mock configuration.
     *
     * @param mockConfig Mock configuration to remove
     * @return The removed configuration, or null if not found
     */
    public MockConfig unsetMockConfig(MockConfig mockConfig) {
        return mockConfigMap.remove(
                getKey(mockConfig.getRequest().getHttpMethod(),
                        mockConfig.getRequest().getUri(),
                        mockConfig.getTenantId()));
    }

    /**
     * Get all registered mock configurations.
     *
     * @return Map of all mock configurations
     */
    public Map<String, MockConfig> getAllMockConfigs() {
        return Map.copyOf(mockConfigMap);
    }

    /**
     * Clear all mock configurations.
     */
    public void clear() {
        mockConfigMap.clear();
        log.info("Cleared all mock configurations");
    }

    /**
     * Reload all mock configurations from configured files.
     */
    public void reload() throws IOException {
        init();
    }
}
