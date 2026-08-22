package com.mirco_grid.backend.config;

import com.mirco_grid.backend.service.council.CouncilAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The door on {@code /api/council/**}.
 *
 * <p>An interceptor rather than a security filter chain: there is one role and
 * one token, and putting Spring Security in front of an app whose other
 * endpoints are deliberately public would cost more configuration than it
 * buys. It refuses anything without the bearer token the login endpoint hands
 * out, and answers with JSON so the Angular client gets a body it can read
 * rather than a servlet container error page.
 */
@Component
public class CouncilAuthInterceptor implements HandlerInterceptor {

    private final CouncilAuth auth;

    public CouncilAuthInterceptor(CouncilAuth auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // CORS preflight carries no Authorization header by design; Spring's
        // CORS handling has already answered it by the time we get here.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (auth.isAuthorised(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return true;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"Sign in as Council to view this data.\"}");
        return false;
    }
}
