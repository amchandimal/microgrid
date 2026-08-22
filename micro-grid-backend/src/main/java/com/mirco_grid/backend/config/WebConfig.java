package com.mirco_grid.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the Angular dev server call the API from its own origin. The allowed
 * origins are a property so this does not have to be edited for deployment.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final CouncilAuthInterceptor councilAuth;

    public WebConfig(
            @Value("${micro-grid.cors.allowed-origins:http://localhost:4200}")
            String[] allowedOrigins,
            CouncilAuthInterceptor councilAuth) {
        this.allowedOrigins = allowedOrigins;
        this.councilAuth = councilAuth;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                // The wizard posts its answers, so GET alone is not enough.
                .allowedMethods("GET", "POST")
                // Authorization carries the council session token.
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Everything else on this API is public on purpose; the council data is
        // the one part that is not.
        registry.addInterceptor(councilAuth).addPathPatterns("/api/council/**");
    }
}
