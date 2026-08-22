package com.mirco_grid.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirco_grid.backend.service.recaptcha.CreateAssessment;
import com.mirco_grid.backend.service.recaptcha.RecaptchaProperties;
import com.mirco_grid.backend.service.recaptcha.RecaptchaVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The door reCAPTCHA puts on the API, without Google behind it.
 *
 * <p>A unit test rather than a slice: the interceptor and its assessment
 * client only exist when {@code micro-grid.recaptcha.enabled} is true, and a
 * context with that set would try to open a real gRPC channel with
 * Application Default Credentials that no build machine has.
 */
class RecaptchaInterceptorTest {

    private final CreateAssessment assessments = mock(CreateAssessment.class);
    private final RecaptchaInterceptor interceptor =
            new RecaptchaInterceptor(properties("/api/health/**"), assessments);

    private static RecaptchaProperties properties(String... exempt) {
        return new RecaptchaProperties(true, "project", "site-key", 0.5, exempt);
    }

    private static MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (token != null) {
            request.addHeader(RecaptchaInterceptor.TOKEN_HEADER, token);
        }
        return request;
    }

    @Test
    void letsAScoredRequestThrough() throws Exception {
        when(assessments.createAssessment("good-token", "COUNCIL"))
                .thenReturn(RecaptchaVerdict.allowed(0.9f));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceeded = interceptor.preHandle(
                request("GET", "/api/council/summary", "good-token"), response, new Object());

        assertThat(proceeded).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    /** The action is derived from the path, not taken from the client. */
    @Test
    void scoresTheLoginUnderItsOwnAction() throws Exception {
        when(assessments.createAssessment(any(), any())).thenReturn(RecaptchaVerdict.allowed(0.9f));

        interceptor.preHandle(
                request("POST", "/api/auth/login", "good-token"),
                new MockHttpServletResponse(),
                new Object());

        verify(assessments).createAssessment("good-token", "LOGIN");
    }

    @Test
    void refusesARequestWithNoToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceeded = interceptor.preHandle(
                request("GET", "/api/grid/region", null), response, new Object());

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("reCAPTCHA");
        verify(assessments, never()).createAssessment(any(), any());
    }

    @Test
    void refusesARequestReCaptchaScoredDown() throws Exception {
        when(assessments.createAssessment(eq("bot-token"), any()))
                .thenReturn(RecaptchaVerdict.refused(0.1f, "This request looked automated."));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceeded = interceptor.preHandle(
                request("GET", "/api/grid/region", "bot-token"), response, new Object());

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("looked automated");
    }

    /** Preflight carries none of our headers, and Spring has already answered it. */
    @Test
    void leavesCorsPreflightAlone() throws Exception {
        boolean proceeded = interceptor.preHandle(
                request("OPTIONS", "/api/council/summary", null),
                new MockHttpServletResponse(),
                new Object());

        assertThat(proceeded).isTrue();
        verify(assessments, never()).createAssessment(any(), any());
    }

    @Test
    void skipsThePathsConfiguredAsExempt() throws Exception {
        boolean proceeded = interceptor.preHandle(
                request("GET", "/api/health/live", null),
                new MockHttpServletResponse(),
                new Object());

        assertThat(proceeded).isTrue();
        verify(assessments, never()).createAssessment(any(), any());
    }

    /** A context path is not part of the path the action is derived from. */
    @Test
    void ignoresTheContextPath() throws Exception {
        when(assessments.createAssessment(any(), any())).thenReturn(RecaptchaVerdict.allowed(0.9f));

        MockHttpServletRequest request = request("GET", "/backend/api/wizard/plan", "good-token");
        request.setContextPath("/backend");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(assessments).createAssessment("good-token", "WIZARD");
    }
}
