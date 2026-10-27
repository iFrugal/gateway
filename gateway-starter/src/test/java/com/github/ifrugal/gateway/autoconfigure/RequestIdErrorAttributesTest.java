package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestIdErrorAttributes")
class RequestIdErrorAttributesTest {

    private static final List<HttpMessageReader<?>> READERS = ServerCodecConfigurer.create().getReaders();

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RequestIdErrorAttributesAutoConfiguration.class,
                    ErrorWebFluxAutoConfiguration.class,
                    WebFluxAutoConfiguration.class));

    private static Map<String, Object> errorAttributesFor(ErrorAttributes errorAttributes,
                                                          MockServerWebExchange exchange) {
        errorAttributes.storeErrorInformation(new ResponseStatusException(HttpStatus.CONFLICT), exchange);
        ServerRequest request = ServerRequest.create(exchange, READERS);
        return errorAttributes.getErrorAttributes(request, ErrorAttributeOptions.defaults());
    }

    @Test
    @DisplayName("should replace Boot's requestId with the gateway request id")
    void replacesRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        exchange.getAttributes().put(LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE, "gateway-id-0001");

        Map<String, Object> attributes = errorAttributesFor(new RequestIdErrorAttributes(), exchange);

        assertThat(attributes).containsEntry("requestId", "gateway-id-0001").containsEntry("status", 409);
    }

    @Test
    @DisplayName("should keep Boot's requestId when the gateway attribute is absent")
    void keepsBootRequestIdWithoutAttribute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        Map<String, Object> attributes = errorAttributesFor(new RequestIdErrorAttributes(), exchange);

        assertThat(attributes).containsEntry("requestId", exchange.getRequest().getId());
    }

    @Test
    @DisplayName("should win over Boot's DefaultErrorAttributes")
    void registeredBeforeBootDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ErrorAttributes.class);
            assertThat(context.getBean(ErrorAttributes.class)).isInstanceOf(RequestIdErrorAttributes.class);
        });
    }

    @Test
    @DisplayName("should back off when the application defines its own ErrorAttributes")
    void backsOffForUserBean() {
        contextRunner
                .withBean("customErrorAttributes", ErrorAttributes.class, DefaultErrorAttributes::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorAttributes.class);
                    assertThat(context.getBean(ErrorAttributes.class)).isNotInstanceOf(RequestIdErrorAttributes.class);
                });
    }
}
