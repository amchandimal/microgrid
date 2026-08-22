package com.mirco_grid.backend.config;

import com.mirco_grid.backend.service.recaptcha.CreateAssessment;
import com.mirco_grid.backend.service.recaptcha.RecaptchaProperties;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Everything reCAPTCHA, wired in one place and only when it is switched on.
 *
 * <p>The three beans are declared here rather than component-scanned, and that
 * is the whole point of this class. {@code @WebMvcTest} pulls every
 * {@code HandlerInterceptor} into its slice but leaves plain components behind,
 * so a scanned {@code RecaptchaInterceptor} would be built in a slice without
 * the properties and client it needs. Declared as {@code @Bean}s on a
 * {@code @Configuration} the slice excludes, they arrive together or not at
 * all - and {@code WebConfig} asks for the interceptor through an
 * {@code ObjectProvider}, which is simply empty when they are absent.
 *
 * <p>{@code micro-grid.recaptcha.enabled} is false everywhere except the
 * {@code prod} profile, and the Maven build forces it false for the test JVM
 * (see the surefire configuration in {@code pom.xml}), so no test and no
 * developer machine ever needs Application Default Credentials.
 */
@Configuration
@ConditionalOnProperty(prefix = "micro-grid.recaptcha", name = "enabled", havingValue = "true")
public class RecaptchaConfig {

    @Bean
    RecaptchaProperties recaptchaProperties(
            @Value("${micro-grid.recaptcha.enabled:false}") boolean enabled,
            @Value("${micro-grid.recaptcha.project-id:}") String projectId,
            @Value("${micro-grid.recaptcha.site-key:}") String siteKey,
            @Value("${micro-grid.recaptcha.min-score:0.5}") double minScore,
            @Value("${micro-grid.recaptcha.exempt-paths:}") String[] exemptPaths) {
        return new RecaptchaProperties(enabled, projectId, siteKey, minScore, exemptPaths);
    }

    /**
     * The Google client is opened here, at startup, so missing or unauthorised
     * credentials fail the boot loudly rather than becoming a site that
     * refuses every request.
     */
    @Bean(destroyMethod = "close")
    CreateAssessment createAssessment(RecaptchaProperties properties) throws IOException {
        return new CreateAssessment(properties);
    }

    @Bean
    RecaptchaInterceptor recaptchaInterceptor(
            RecaptchaProperties properties, CreateAssessment assessments) {
        return new RecaptchaInterceptor(properties, assessments);
    }
}
