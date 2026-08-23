package com.mirco_grid.backend.service.recaptcha;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the assessment call against a stub of Google's REST endpoint, so the
 * suite stays offline and needs no API key. The canned bodies are trimmed
 * copies of real reCAPTCHA Enterprise responses, including their habit of
 * omitting false and zero rather than spelling them out.
 */
class CreateAssessmentTest {

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().toString());
        lastApiKey.set(exchange.getRequestHeaders().getFirst("X-goog-api-key"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private CreateAssessment assessments() {
        return new CreateAssessment(
                new RecaptchaProperties(
                        true, "sustainalens-506320", "site-key", "api-key", 0.5, new String[0]),
                baseUrl);
    }

    private void googleAnswers(int status, String body) {
        responseStatus.set(status);
        responseBody.set(body);
    }

    @Test
    void allowsATokenThatScoresAboveTheThreshold() {
        googleAnswers(200, """
                {"tokenProperties":{"valid":true,"hostname":"sustainalens.com","action":"COUNCIL"},
                 "riskAnalysis":{"score":0.9,"reasons":[]},
                 "name":"projects/sustainalens-506320/assessments/abc"}""");

        RecaptchaVerdict verdict = assessments().createAssessment("good-token", "COUNCIL");

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.score()).isEqualTo(0.9f);
    }

    /** The site key, token and action all have to reach Google, and the key must be a header. */
    @Test
    void sendsTheKeyAsAHeaderAndTheEventAsTheBody() {
        googleAnswers(200, """
                {"tokenProperties":{"valid":true,"action":"LOGIN"},"riskAnalysis":{"score":0.8}}""");

        assessments().createAssessment("a-token", "LOGIN");

        assertThat(lastApiKey.get()).isEqualTo("api-key");
        assertThat(lastPath.get())
                .isEqualTo("/projects/sustainalens-506320/assessments")
                // A key in the query string leaks into access logs; it goes in the header.
                .doesNotContain("api-key");
        assertThat(lastBody.get())
                .contains("\"siteKey\":\"site-key\"")
                .contains("\"token\":\"a-token\"")
                .contains("\"expectedAction\":\"LOGIN\"");
    }

    /** proto3 JSON omits a false {@code valid}, so the field is simply not there. */
    @Test
    void refusesATokenGoogleCallsInvalid() {
        googleAnswers(200, """
                {"tokenProperties":{"invalidReason":"EXPIRED"},"riskAnalysis":{}}""");

        RecaptchaVerdict verdict = assessments().createAssessment("stale-token", "GRID");

        assertThat(verdict.allowed()).isFalse();
        // Not the "could not reach Google" answer - we did reach it, and it said no.
        assertThat(verdict.reason()).isEqualTo("That request failed its reCAPTCHA check.");
    }

    /** A token minted for a cheap call must not be replayable against a guarded one. */
    @Test
    void refusesATokenMintedForAnotherAction() {
        googleAnswers(200, """
                {"tokenProperties":{"valid":true,"action":"GRID"},"riskAnalysis":{"score":0.9}}""");

        RecaptchaVerdict verdict = assessments().createAssessment("wrong-action", "COUNCIL");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("another action");
    }

    @Test
    void refusesAScoreBelowTheThreshold() {
        googleAnswers(200, """
                {"tokenProperties":{"valid":true,"action":"WIZARD"},
                 "riskAnalysis":{"score":0.1,"reasons":["AUTOMATION"]}}""");

        RecaptchaVerdict verdict = assessments().createAssessment("bot-token", "WIZARD");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.score()).isEqualTo(0.1f);
        assertThat(verdict.reason()).contains("automated");
    }

    /** proto3 JSON leaves out a zero score, and zero means certainly a bot. */
    @Test
    void treatsAnAbsentScoreAsZero() {
        googleAnswers(200, """
                {"tokenProperties":{"valid":true,"action":"GRID"},"riskAnalysis":{}}""");

        RecaptchaVerdict verdict = assessments().createAssessment("token", "GRID");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.score()).isEqualTo(0f);
        // Scored and refused, not "could not verify" - an omitted score is a real zero.
        assertThat(verdict.reason()).contains("automated");
    }

    /** Fail closed: if Google cannot answer, we cannot tell a person from a bot. */
    @Test
    void refusesWhenGoogleReturnsAnError() {
        googleAnswers(403, """
                {"error":{"code":403,"message":"API key not valid","status":"PERMISSION_DENIED"}}""");

        RecaptchaVerdict verdict = assessments().createAssessment("token", "GRID");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("Try again shortly");
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        RecaptchaProperties noKey =
                new RecaptchaProperties(true, "project", "site-key", "", 0.5, new String[0]);

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> new CreateAssessment(noKey, baseUrl)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECAPTCHA_API_KEY");
    }
}
