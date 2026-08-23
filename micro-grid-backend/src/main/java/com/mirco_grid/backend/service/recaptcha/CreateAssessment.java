package com.mirco_grid.backend.service.recaptcha;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Scores one client token against reCAPTCHA Enterprise.
 *
 * <p>Speaks to the REST endpoint directly rather than through
 * {@code google-cloud-recaptchaenterprise}. The client library authenticates
 * only with Application Default Credentials, which a container on a VPS does
 * not have and would need a mounted service-account key to fake; an API key in
 * a header needs nothing but an environment variable. Dropping the library also
 * took roughly 70MB of gRPC and protobuf out of the jar.
 *
 * <p>The key goes in {@code X-goog-api-key}, not the {@code ?key=} query
 * parameter the reCAPTCHA samples use. Google recommends the header for
 * exactly the reason it matters here: a key in a URL ends up in access logs
 * and proxy traces. Nothing in this class logs the request.
 *
 * <p>Only built when {@code micro-grid.recaptcha.enabled} is true - see
 * {@code RecaptchaConfig} - so a developer machine and the test JVM never call
 * Google at all.
 */
public class CreateAssessment {

    private static final Logger log = LoggerFactory.getLogger(CreateAssessment.class);

    public static final String ASSESSMENTS_URL =
            "https://recaptchaenterprise.googleapis.com/v1";

    /** What a refused-because-we-could-not-tell answer says. */
    private static final String UNAVAILABLE = "Could not verify this request. Try again shortly.";

    /**
     * Every guarded request waits on this call, so it is deliberately short:
     * better to refuse a handful of requests during a Google blip than to hold
     * every connection open for half a minute.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RecaptchaProperties properties;
    private final RestClient client;

    public CreateAssessment(RecaptchaProperties properties, String baseUrl) {
        if (properties.projectId().isEmpty()
                || properties.siteKey().isEmpty()
                || properties.apiKey().isEmpty()) {
            throw new IllegalStateException(
                    "micro-grid.recaptcha.project-id, .site-key and .api-key are all required "
                            + "when micro-grid.recaptcha.enabled is true. Set RECAPTCHA_API_KEY.");
        }
        this.properties = properties;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory())
                .build();
        log.info(
                "reCAPTCHA Enterprise assessments enabled for project {} (minimum score {}).",
                properties.projectId(),
                properties.minScore());
    }

    /**
     * Assess one token.
     *
     * @param token the token {@code grecaptcha.enterprise.execute} produced in
     *     the browser, taken off the {@code X-Recaptcha-Token} header
     * @param expectedAction the action the token should have been minted for -
     *     see {@link RecaptchaActions#forPath(String)}
     * @return the verdict; refused, never thrown, so the caller has one path
     */
    public RecaptchaVerdict createAssessment(String token, String expectedAction) {
        AssessmentResponse response;
        try {
            response = client.post()
                    .uri("/projects/{project}/assessments", properties.projectId())
                    .header("X-goog-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    // Naming the expected action lets Google flag a mismatch as
                    // a risk reason too; the explicit check below enforces it.
                    .body(new AssessmentRequest(
                            new Event(properties.siteKey(), token, expectedAction)))
                    .retrieve()
                    .body(AssessmentResponse.class);
        } catch (RestClientException e) {
            // Fail closed. If Google cannot be reached we cannot tell a person
            // from a bot, and the profile that turns this on is the one that
            // says every URL is verified. The escape hatch is deliberately the
            // blunt one: set micro-grid.recaptcha.enabled=false and restart.
            log.error("reCAPTCHA assessment failed for action {}", expectedAction, e);
            return RecaptchaVerdict.refused(UNAVAILABLE);
        }

        if (response == null) {
            log.error("reCAPTCHA returned an empty assessment for action {}", expectedAction);
            return RecaptchaVerdict.refused(UNAVAILABLE);
        }

        // Everything below treats absent as the unfavourable answer, which is
        // both safe and what the wire actually looks like: the API is proto3
        // JSON, so it omits "valid" and "score" when they are false and zero -
        // exactly the bot cases. That is also why the wire records below box
        // their primitives; a record component of type float cannot take the
        // null an absent field deserialises to.
        TokenProperties tokenProperties = response.tokenProperties();
        if (tokenProperties == null || !tokenProperties.isValid()) {
            log.debug(
                    "Rejected token for action {}: {}",
                    expectedAction,
                    tokenProperties == null ? "no tokenProperties" : tokenProperties.invalidReason());
            return RecaptchaVerdict.refused("That request failed its reCAPTCHA check.");
        }

        // A token is minted for one action. Accepting any action would let a
        // token taken from a cheap public call be replayed against an
        // expensive or guarded one.
        if (!expectedAction.equals(tokenProperties.action())) {
            log.debug(
                    "Rejected token minted for {} against {}",
                    tokenProperties.action(),
                    expectedAction);
            return RecaptchaVerdict.refused("That reCAPTCHA token was issued for another action.");
        }

        float score =
                response.riskAnalysis() == null ? 0f : response.riskAnalysis().scoreOrZero();
        if (score < properties.minScore()) {
            if (log.isDebugEnabled() && response.riskAnalysis() != null) {
                log.debug(
                        "Low score on {} ({}): {}",
                        expectedAction,
                        score,
                        response.riskAnalysis().reasons());
            }
            return RecaptchaVerdict.refused(
                    score, "That request looked automated and was not allowed through.");
        }
        return RecaptchaVerdict.allowed(score);
    }

    private static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    // -----------------------------------------------------------------------
    // Wire shapes - only the fields this uses
    // -----------------------------------------------------------------------

    record AssessmentRequest(Event event) {}

    record Event(String siteKey, String token, String expectedAction) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssessmentResponse(TokenProperties tokenProperties, RiskAnalysis riskAnalysis) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenProperties(Boolean valid, String invalidReason, String action, String hostname) {
        /** Absent means false: proto3 JSON leaves out a false boolean. */
        boolean isValid() {
            return Boolean.TRUE.equals(valid);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RiskAnalysis(Float score, List<String> reasons) {
        /** Absent means 0.0, which reCAPTCHA reads as certainly a bot. */
        float scoreOrZero() {
            return score == null ? 0f : score;
        }
    }
}
