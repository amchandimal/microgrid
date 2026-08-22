package com.mirco_grid.backend.service.recaptcha;

import java.util.Arrays;
import java.util.List;
import org.springframework.util.AntPathMatcher;

/**
 * How reCAPTCHA Enterprise is configured, per profile.
 *
 * <p>{@code enabled} is the switch everything else hangs off: while it is
 * false neither {@link CreateAssessment} nor the interceptor in front of the
 * API is created at all, so a developer machine never needs Google Cloud
 * credentials. {@code application-prod.properties} is the only file that turns
 * it on.
 *
 * <p>Built by {@code RecaptchaConfig} rather than component-scanned, so it can
 * never be missing from a context that has the interceptor - see that class.
 */
public class RecaptchaProperties {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final boolean enabled;
    private final String projectId;
    private final String siteKey;
    private final double minScore;
    private final List<String> exemptPaths;

    public RecaptchaProperties(
            boolean enabled,
            String projectId,
            String siteKey,
            double minScore,
            String[] exemptPaths) {
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
