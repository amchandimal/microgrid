package com.mirco_grid.backend.service.recaptcha;

import java.util.Locale;

/**
 * The action a request's token is expected to have been minted for.
 *
 * <p>Deliberately coarse - one action per API area, plus LOGIN for the one
 * call worth watching on its own. Actions are what the reCAPTCHA console
 * groups scores by, so deriving one per URL (with locality names in it) would
 * give thousands of single-request buckets and no signal at all.
 *
 * <p>{@code recaptchaActionFor} in the Angular app's {@code core/api.ts}
 * derives the same string from the same path, and the interceptor refuses a
 * token whose action does not match. The two have to change together.
 */
public final class RecaptchaActions {

    private RecaptchaActions() {}

    /** @param path a path within the application, e.g. {@code /api/grid/cells} */
    public static String forPath(String path) {
        if (path == null || path.isBlank()) {
            return "API";
        }
        if ("/api/auth/login".equals(path)) {
            return "LOGIN";
        }
        // "/api/council/suburbs" -> ["", "api", "council", "suburbs"]
        String[] segments = path.split("/");
        if (segments.length < 3 || segments[2].isBlank()) {
            return "API";
        }
        return segments[2].toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
    }
}
