package com.mirco_grid.backend.service.recaptcha;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The other half of this contract is {@code recaptchaActionFor} in the Angular
 * app's {@code core/api.ts}, and there is a matching spec beside it. A token is
 * minted for one action and refused against any other, so if these two ever
 * disagree the production API answers 403 to everything.
 */
class RecaptchaActionsTest {

    @Test
    void namesTheLoginOnItsOwn() {
        assertThat(RecaptchaActions.forPath("/api/auth/login")).isEqualTo("LOGIN");
    }

    @Test
    void otherwiseUsesTheApiArea() {
        assertThat(RecaptchaActions.forPath("/api/council/summary")).isEqualTo("COUNCIL");
        assertThat(RecaptchaActions.forPath("/api/council/suburbs/Port%20Kembla"))
                .isEqualTo("COUNCIL");
        assertThat(RecaptchaActions.forPath("/api/grid/cells")).isEqualTo("GRID");
        assertThat(RecaptchaActions.forPath("/api/wizard/plan")).isEqualTo("WIZARD");
        assertThat(RecaptchaActions.forPath("/api/solar/2500")).isEqualTo("SOLAR");
    }

    @Test
    void fallsBackWhenThereIsNoArea() {
        assertThat(RecaptchaActions.forPath("/api")).isEqualTo("API");
        assertThat(RecaptchaActions.forPath("/api/")).isEqualTo("API");
        assertThat(RecaptchaActions.forPath("")).isEqualTo("API");
        assertThat(RecaptchaActions.forPath(null)).isEqualTo("API");
    }

    /** Actions are restricted to letters, digits, slash, underscore and dash. */
    @Test
    void keepsActionsToCharactersRecaptchaAccepts() {
        assertThat(RecaptchaActions.forPath("/api/odd.name/thing")).isEqualTo("ODD_NAME");
    }
}
