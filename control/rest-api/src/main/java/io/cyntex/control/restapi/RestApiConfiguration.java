package io.cyntex.control.restapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP presentation configuration: the single Spring context's Web MVC face over the control
 * core. It is imported into the one service context (there is no second application context and no
 * second port); the control verbs are projected onto HTTP here.
 *
 * <p>Every {@code @RestController} handler is mounted under the single {@code /api} prefix, so the
 * verb surface lives at one route root. A plain {@code @Controller} (not annotated
 * {@code @RestController}) is left unprefixed, so a root-level probe can be served outside
 * {@code /api}.
 */
@Configuration
public class RestApiConfiguration {

    @Bean
    WebMvcConfigurer apiPathPrefix() {
        return new WebMvcConfigurer() {
            @Override
            public void configurePathMatch(PathMatchConfigurer configurer) {
                configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(RestController.class));
            }
        };
    }
}
