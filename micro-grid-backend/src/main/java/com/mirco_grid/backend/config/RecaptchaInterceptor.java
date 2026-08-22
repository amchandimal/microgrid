package com.mirco_grid.backend.config;

import com.mirco_grid.backend.service.recaptcha.CreateAssessment;
import com.mirco_grid.backend.service.recaptcha.RecaptchaActions;
import com.mirco_grid.backend.service.recaptcha.RecaptchaProperties;
import com.mirco_grid.backend.service.recaptcha.RecaptchaVerdict;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * reCAPTCHA Enterprise in front of the whole API.
 *
 * <p>{@code WebConfig} puts this on {@code /api/**} and ahead of the council
 * door, so an unverified request is refused before it gets as far as being
 * asked for a session token. Every URL, not a chosen few: the endpoints behind
 * this read spreadsheets and rebuild a grid model per call, and the public
 * ones are the expensive ones.
 *
 * <p>Only a bean when {@code micro-grid.recaptcha.enabled} is true - the
 * {@code prod} profile. Under {@code dev}, and in every test, it does not
 * exist and {@code WebConfig} registers nothing.
 */
@Component
@ConditionalOnProperty(prefix = "micro-grid.recaptcha", name = "enabled", havingValue = "true")
public class RecaptchaInterceptor implements HandlerInterceptor {

    /** Where the Angular reCAPTCHA interceptor puts the token. */
    public static final String TOKEN_HEADER = "X-Recaptcha-Token";

    private final RecaptchaProperties properties;
    private final CreateAssessment assessments;

    public RecaptchaInterceptor(RecaptchaProperties properties, CreateAssessment assessments) {
        this.properties = properties;
        this.assessments = assessments;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // A CORS preflight is the browser asking whether the real request is
        // allowed; it carries no headers of ours by design, and Spring's CORS
        // handling has already answered it by the time we are called.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String path = pathWithinApplication(request);
        if (properties.isExempt(path)) {
            return true;
        }

        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return refuse(response, "This request carried no reCAPTCHA token.");
        }

        RecaptchaVerdict verdict =
                assessments.createAssessment(token.trim(), RecaptchaActions.forPath(path));
        return verdict.allowed() ? true : refuse(response, verdict.reason());
    }

    /** The path the client asked for, with any context path taken off. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context == null || context.isEmpty() ? uri : uri.substring(context.length());
    }

    /**
     * 403 with a JSON body, matching {@code CouncilAuthInterceptor} - the
     * Angular client gets something it can read rather than a container error
     * page. Deliberately not 401: there is no credential the caller could
     * supply to fix this.
     */
    private static boolean refuse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        return false;
    }
}
