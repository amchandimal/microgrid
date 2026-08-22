package com.mirco_grid.backend.service.council;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Demo auth, but the door still has to shut. */
class CouncilAuthTest {

    private final CouncilAuth auth = new CouncilAuth(
            "council", "Wollongong2026!", "council-demo-token", "Wollongong City Council");

    @Test
    void issuesASessionForTheRightCredentials() {
        assertThat(auth.login("council", "Wollongong2026!"))
                .hasValueSatisfying(session -> {
                    assertThat(session.token()).isEqualTo("council-demo-token");
                    assertThat(session.role()).isEqualTo("COUNCIL");
                    assertThat(session.displayName()).isEqualTo("Wollongong City Council");
                });
    }

    @Test
    void toleratesWhitespaceRoundTheUsernameButNotThePassword() {
        assertThat(auth.login("  council  ", "Wollongong2026!")).isPresent();
        assertThat(auth.login("council", " Wollongong2026! ")).isEmpty();
    }

    @Test
    void refusesTheWrongPassword() {
        assertThat(auth.login("council", "wollongong2026!")).isEmpty();
        assertThat(auth.login("council", "Wollongong2026")).isEmpty();
        assertThat(auth.login("council", "")).isEmpty();
    }

    @Test
    void refusesTheWrongUsername() {
        assertThat(auth.login("Council", "Wollongong2026!")).isEmpty();
        assertThat(auth.login("admin", "Wollongong2026!")).isEmpty();
    }

    @Test
    void refusesMissingCredentials() {
        assertThat(auth.login(null, "Wollongong2026!")).isEmpty();
        assertThat(auth.login("council", null)).isEmpty();
    }

    @Test
    void acceptsTheBearerTokenItIssued() {
        assertThat(auth.isAuthorised("Bearer council-demo-token")).isTrue();
        // Header names and schemes are case-insensitive per RFC 7235.
        assertThat(auth.isAuthorised("bearer council-demo-token")).isTrue();
        assertThat(auth.isAuthorised("Bearer  council-demo-token ")).isTrue();
    }

    @Test
    void refusesAnythingElse() {
        assertThat(auth.isAuthorised(null)).isFalse();
        assertThat(auth.isAuthorised("")).isFalse();
        assertThat(auth.isAuthorised("council-demo-token")).isFalse();
        assertThat(auth.isAuthorised("Bearer council-demo-token-2")).isFalse();
        assertThat(auth.isAuthorised("Basic council-demo-token")).isFalse();
        // The token is not the password, and neither substitutes for the other.
        assertThat(auth.isAuthorised("Bearer Wollongong2026!")).isFalse();
    }
}
