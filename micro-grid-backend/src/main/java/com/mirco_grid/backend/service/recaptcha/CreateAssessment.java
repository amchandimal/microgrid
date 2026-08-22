package com.mirco_grid.backend.service.recaptcha;

import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
import com.google.recaptchaenterprise.v1.Assessment;
import com.google.recaptchaenterprise.v1.CreateAssessmentRequest;
import com.google.recaptchaenterprise.v1.Event;
import com.google.recaptchaenterprise.v1.ProjectName;
import com.google.recaptchaenterprise.v1.RiskAnalysis.ClassificationReason;
import com.google.recaptchaenterprise.v1.TokenProperties;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores one client token against reCAPTCHA Enterprise.
 *
 * <p>Grown out of Google's {@code CreateAssessment} sample, with the three
 * things the sample leaves as exercises done: the client is created once and
 * reused rather than per call (a gRPC channel is expensive and the sample says
 * so), the project and key come from configuration instead of literals, and
 * the outcome is returned as a {@link RecaptchaVerdict} instead of printed.
 *
 * <p>Only built when {@code micro-grid.recaptcha.enabled} is true - see
 * {@code RecaptchaConfig}. That is what keeps a developer machine and the test
 * JVM from needing Application Default Credentials: with the property false
 * this class is never instantiated, so
 * {@code RecaptchaEnterpriseServiceClient.create()} never runs.
 *
 * <p>Conversely, in production the client is built at startup, so missing or
 * unauthorised credentials fail the boot loudly rather than turning into a
 * site that refuses every request at runtime.
 */
public class CreateAssessment implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CreateAssessment.class);

    private final RecaptchaProperties properties;
    private final RecaptchaEnterpriseServiceClient client;

    public CreateAssessment(RecaptchaProperties properties) throws IOException {
        if (properties.projectId().isEmpty() || properties.siteKey().isEmpty()) {
            throw new IllegalStateException(
                    "micro-grid.recaptcha.project-id and .site-key are required when "
                            + "micro-grid.recaptcha.enabled is true.");
        }
        this.properties = properties;
        this.client = RecaptchaEnterpriseServiceClient.create();
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
        // Naming the expected action on the event lets Google flag a mismatch
        // as a risk reason as well; the explicit check below is what enforces it.
        Event event = Event.newBuilder()
                .setSiteKey(properties.siteKey())
                .setToken(token)
                .setExpectedAction(expectedAction)
                .build();

        CreateAssessmentRequest request = CreateAssessmentRequest.newBuilder()
                .setParent(ProjectName.of(properties.projectId()).toString())
                .setAssessment(Assessment.newBuilder().setEvent(event).build())
                .build();

        Assessment response;
        try {
            response = client.createAssessment(request);
        } catch (RuntimeException e) {
            // Fail closed. If Google cannot be reached we cannot tell a person
            // from a bot, and the profile that turns this on is the one that
            // says every URL is verified. The escape hatch is deliberately the
            // blunt one: set micro-grid.recaptcha.enabled=false and restart.
            log.error("reCAPTCHA assessment failed for action {}", expectedAction, e);
            return RecaptchaVerdict.refused("Could not verify this request. Try again shortly.");
        }

        TokenProperties tokenProperties = response.getTokenProperties();
        if (!tokenProperties.getValid()) {
            log.debug(
                    "Rejected token for action {}: {}",
                    expectedAction,
                    tokenProperties.getInvalidReason().name());
            return RecaptchaVerdict.refused("This request's reCAPTCHA token was not valid.");
        }

        // A token is minted for one action. Accepting any action would let a
        // token taken from a cheap public call be replayed against an
        // expensive or guarded one.
        if (!expectedAction.equals(tokenProperties.getAction())) {
            log.debug(
                    "Rejected token minted for {} against {}",
                    tokenProperties.getAction(),
                    expectedAction);
            return RecaptchaVerdict.refused("This request's reCAPTCHA token was for another action.");
        }

        float score = response.getRiskAnalysis().getScore();
        if (score < properties.minScore()) {
            if (log.isDebugEnabled()) {
                for (ClassificationReason reason : response.getRiskAnalysis().getReasonsList()) {
                    log.debug("Low score on {} ({}): {}", expectedAction, score, reason);
                }
            }
            return RecaptchaVerdict.refused(
                    score, "This request looked automated and was not allowed through.");
        }
        return RecaptchaVerdict.allowed(score);
    }

    /** Spring calls this on shutdown; the gRPC channel has to be released. */
    @Override
    public void close() {
        client.close();
    }
}
