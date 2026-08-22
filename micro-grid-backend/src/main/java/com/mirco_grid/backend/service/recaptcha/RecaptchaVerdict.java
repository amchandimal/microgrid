package com.mirco_grid.backend.service.recaptcha;

/**
 * What one assessment concluded about one token.
 *
 * @param allowed whether the request may proceed
 * @param score the risk score, 0.0 (certainly a bot) to 1.0 (certainly a
 *     person), or 0 when the token never got as far as being scored
 * @param reason why it was refused, phrased for a client; null when allowed
 */
public record RecaptchaVerdict(boolean allowed, float score, String reason) {

    public static RecaptchaVerdict allowed(float score) {
        return new RecaptchaVerdict(true, score, null);
    }

    public static RecaptchaVerdict refused(String reason) {
        return new RecaptchaVerdict(false, 0f, reason);
    }

    public static RecaptchaVerdict refused(float score, String reason) {
        return new RecaptchaVerdict(false, score, reason);
    }
}
