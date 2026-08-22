package com.mirco_grid.backend.service.recaptcha;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * How reCAPTCHA Enterprise is configured, per profile.
 *
 * <p>{@code enabled} is the switch everything else hangs off: while it is
 * false neither {@link CreateAssessment} nor the interceptor in front of the
 * API is created at all, so a developer machine never needs Google Cloud
 * credentials. {@code application-prod.properties} is the only file that turns
 * it on.
 */
@Component
public class RecaptchaProperties {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final boolean enabled;
    private final String projectId;
    private final String siteKey;
    private final double minScore;
    private final List<String> exemptPaths;

    public RecaptchaProperties(
            @Value("${micro-grid.recaptcha.enabled:false}") boolean enabled,
            @Value("${micro-grid.recaptcha.project-id:}") String projectId,
            @Value("${micro-grid.recaptcha.site-key:}") String siteKey,
            @Value("${micro-grid.recaptcha.min-score:0.5}") double minScore,
            @Value("${micro-grid.recaptcha.exempt-paths:}") String[] exemptPaths) {
        this.enabled = enabled;
        this.projectId = projectId.trim();
        this.siteKey = siteKey.trim();
        this.minScore = minScore;
        this.exemptPaths = Arrays.stream(exemptPaths)
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    }

    public boolean enabled() {
        return enabled;
    }

    public String projectId() {
        return projectId;
    }

    public String siteKey() {
        return siteKey;
    }

    public double minScore() {
        return minScore;
    }

    public List<String> exemptPaths() {
        return exemptPaths;
    }

    /** Is this path one of the Ant patterns configured to skip verification? */
    public boolean isExempt(String path) {
        return exemptPaths.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }
}
