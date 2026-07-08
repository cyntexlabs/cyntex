package io.cyntex.control.restapi;

import io.cyntex.messages.MessageCatalog;
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

    /**
     * The shared error-code catalog + renderer, so this HTTP face renders coded errors the same way every
     * other presentation face does (the offline CLI, the assembly root). The bundled {@code en} catalog is
     * immutable and stateless, so a single instance is shared.
     */
    @Bean
    MessageCatalog messageCatalog() {
        return MessageCatalog.bundled();
    }
}
