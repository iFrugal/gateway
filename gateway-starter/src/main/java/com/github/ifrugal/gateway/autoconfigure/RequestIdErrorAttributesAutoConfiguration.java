package com.github.ifrugal.gateway.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link RequestIdErrorAttributes} so WebFlux error bodies carry the gateway
 * request id.
 *
 * <p>Runs before Boot's {@link ErrorWebFluxAutoConfiguration}, whose own
 * {@code DefaultErrorAttributes} bean then backs off. Applications that define their own
 * {@link ErrorAttributes} bean keep it.</p>
 */
@AutoConfiguration(before = ErrorWebFluxAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(ErrorAttributes.class)
public class RequestIdErrorAttributesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ErrorAttributes.class)
    public RequestIdErrorAttributes requestIdErrorAttributes() {
        return new RequestIdErrorAttributes();
    }
}
