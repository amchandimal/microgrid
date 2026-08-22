import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { from, switchMap } from 'rxjs';
import { Recaptcha } from '../services/recaptcha';
import { apiPathOf, recaptchaActionFor } from '../core/api';

/** What the backend's RecaptchaInterceptor reads the token out of. */
export const RECAPTCHA_HEADER = 'X-Recaptcha-Token';

/**
 * Puts a fresh reCAPTCHA Enterprise token on every call to our own API.
 *
 * <p>Every call, because the backend verifies every URL under `/api` when it
 * runs with the `prod` profile - there is no public half of the API to leave
 * unscored. Scoped to our API for the same reason the council token is: a
 * token minted for sustainalens.com has no business being sent to Nominatim.
 *
 * <p>In development `Recaptcha.enabled` is false and this passes every request
 * straight through, adding nothing and loading nothing.
 */
export const recaptchaInterceptor: HttpInterceptorFn = (request, next) => {
  const recaptcha = inject(Recaptcha);
  const path = apiPathOf(request.url);

  if (!recaptcha.enabled || !path) {
    return next(request);
  }

  return from(recaptcha.execute(recaptchaActionFor(path))).pipe(
    switchMap((token) =>
      next(
        token
          ? request.clone({ setHeaders: { [RECAPTCHA_HEADER]: token } })
          : // No token: let it go and be refused by the backend, so "reCAPTCHA
            // said no" is reported from one place rather than two.
            request,
      ),
    ),
  );
};
