package com.mirco_grid.backend.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the Angular app call the API from its own origin, and puts the two
 * doors on it.
 *
 * <p>The allowed origins are a property so this does not have to be edited for
 * deployment: {@code application-dev.properties} names the dev server,
 * {@code application-prod.properties} names the deployed site.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final CouncilAuthInterceptor councilAuth;
    private final ObjectProvider<RecaptchaInterceptor> recaptcha;

    public WebConfig(
            @Value("${micro-grid.cors.allowed-origins:http://localhost:4200}")
            String[] allowedOrigins,
            CouncilAuthInterceptor councilAuth,
            ObjectProvider<RecaptchaInterceptor> recaptcha) {
        this.allowedOrigins = allowedOrigins;
        this.councilAuth = councilAuth;
        this.recaptcha = recaptcha;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                // The wizard posts its answers, so GET alone is not enough.
                .allowedMethods("GET", "POST")
                // Authorization carries the council session token, and
                // X-Recaptcha-Token the per-request reCAPTCHA token. Both are
                // non-simple headers, so both have to survive the preflight.
                .allowedHeaders("Content-Type", "Authorization", RecaptchaInterceptor.TOKEN_HEADER)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // reCAPTCHA first, and over the whole API: in production nothing here
        // is served to an unverified caller, guarded or not. The provider is
        // empty unless micro-grid.recaptcha.enabled is true, which is what
        // leaves development and the tests untouched.
        recaptcha.ifAvailable(
                interceptor -> registry.addInterceptor(interceptor).addPathPatterns("/api/**"));

        // Everything else on this API is public on purpose; the council data is
        // the one part that is not.
        registry.addInterceptor(councilAuth).addPathPatterns("/api/council/**");
    }
}
